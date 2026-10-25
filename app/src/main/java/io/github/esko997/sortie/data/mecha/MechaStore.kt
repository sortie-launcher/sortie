package io.github.esko997.sortie.data.mecha

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.SessionEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * The mecha companion, persisted in app-private `files/mecha/state.json` (never on the card, never in user.json or the play log).
 *
 * Every operation is queued and run in order on one worker, so a public call returns at once and its outcome arrives in [view].
 * A change is published only after it is on disk: written to `state.json.tmp`, fsynced and atomically moved over the previous
 * save, which stays intact when any step fails. Parts, credits and the record of credited sessions are one file, so they can
 * never disagree after a crash.
 *
 * An unreadable or inconsistent save is reported in [view] and left byte-for-byte as it is; nothing is written until the player
 * chooses [startFresh]. Play credit comes from the sessions table itself ([GameDao.completedSessionsFrom], re-read by Room when
 * sessions change; no polling); [refreshSessions] re-reads it on demand.
 */
class MechaStore internal constructor(
    locate: () -> File,
    private val loadCatalog: () -> List<MechaPart>,
    private val sessions: (Long) -> Flow<List<SessionEntity>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    constructor(context: Context, dao: GameDao) : this(
        locate = { File(context.applicationContext.filesDir, "mecha/state.json") },
        loadCatalog = { assetCatalog(context.applicationContext.assets) },
        sessions = dao::completedSessionsFrom
    )

    private val json = Json { encodeDefaults = true }

    /** Resolved on the worker: Context.getFilesDir may touch the disk. */
    private val file: File by lazy(locate)

    private val _view = MutableStateFlow(MechaView())
    val view: StateFlow<MechaView> = _view

    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Touched only by the worker.
    private var parts: List<MechaPart> = emptyList()
    private var state: MechaState? = null
    private var damaged = false
    private var observer: Job? = null

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "mecha job", e)
                    _view.value = _view.value.copy(error = "Hangar trouble: ${describe(e)}", message = null)
                }
            }
        }
        enqueue { open() }
    }

    fun rename(name: String) = change { it.renamed(name) }

    fun equip(partId: String) = change { it.equipping(partId, parts) }

    fun care(action: MechaCare) = change { it.cared(action) }

    fun salvage(slot: MechaSlot? = null) = change { it.salvaged(parts, slot) }

    fun setEnabled(enabled: Boolean) = change { it.withEnabled(enabled) }

    fun setMotion(enabled: Boolean) = change { it.withMotion(enabled) }

    /** Re-reads completed sessions now (e.g. when Sortie comes back to the foreground); already credited ones are skipped. */
    fun refreshSessions() = enqueue {
        val current = state ?: return@enqueue
        val list = try {
            sessions(current.initializedAt).first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sessionTrouble(e)
            return@enqueue
        }
        credit(list)
    }

    /**
     * Only when the hangar could not be opened: sets an unreadable save aside as `state.damaged-<time>.json` (same bytes) and
     * opens a new hangar, which counts play from now on. Does nothing while a hangar is open or the parts catalog is incomplete.
     */
    fun startFresh() = enqueue {
        if (state != null || parts.isEmpty()) return@enqueue
        if (damaged) {
            val kept = File(file.parentFile, "state.damaged-${clock()}.json")
            try {
                Files.move(file.toPath(), kept.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                Log.w(TAG, "set damaged save aside", e)
                _view.value = MechaView(parts = parts, error = "Couldn't set the damaged save aside (${describe(e)}); it is still at ${file.path}.")
                return@enqueue
            }
            damaged = false
            create("Fresh hangar opened; the damaged save is kept as ${kept.name}.")
        } else {
            create(null)
        }
    }

    /** Runs after every operation queued before it; tests use it to observe settled state. */
    internal suspend fun idle() = runAndAwait {}

    private fun enqueue(job: suspend () -> Unit) {
        jobs.trySend(job)
    }

    private suspend fun runAndAwait(job: suspend () -> Unit) {
        val done = CompletableDeferred<Unit>()
        enqueue {
            try {
                job()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    private fun change(rule: (MechaState) -> MechaResult) = enqueue {
        // No hangar: the view already says why (loading, incomplete catalog or unreadable save); nothing to change.
        val current = state ?: return@enqueue
        commit(current, rule(current))
    }

    private fun open() {
        parts = try {
            loadCatalog()
        } catch (e: Exception) {
            Log.w(TAG, "catalog", e)
            _view.value = MechaView(error = "The mecha parts catalog is incomplete: ${describe(e)}")
            return
        }
        if (!file.exists()) {
            create(null)
            return
        }
        val loaded = try {
            json.decodeFromString(MechaState.serializer(), file.readText()).also { s ->
                mechaStateProblem(s, parts)?.let { throw IllegalStateException(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "read ${file.path}", e)
            damaged = true
            _view.value = MechaView(
                parts = parts,
                error = "The hangar save can't be used: ${describe(e)}. It was left untouched at ${file.path}; " +
                    "Start fresh keeps a copy and opens a new hangar."
            )
            return
        }
        state = loaded
        _view.value = MechaView(loaded, parts)
        observe(loaded.initializedAt)
    }

    private fun create(message: String?) {
        val fresh = starterMechaState(parts, clock())
        save(fresh)?.let { e ->
            _view.value = MechaView(parts = parts, error = "Couldn't set up the hangar: ${describe(e)}")
            return
        }
        state = fresh
        _view.value = MechaView(
            fresh,
            parts,
            message = message ?: "${fresh.name} rolled out of the crate with $MECHA_STARTER_CREDITS salvage credits."
        )
        observe(fresh.initializedAt)
    }

    private fun observe(since: Long) {
        observer?.cancel()
        observer = scope.launch {
            sessions(since)
                .conflate()
                .catch { e -> runAndAwait { sessionTrouble(e) } }
                .collect { list -> runAndAwait { credit(list) } }
        }
    }

    private fun credit(list: List<SessionEntity>) {
        val current = state ?: return
        val result = current.credited(list)
        if (result.state != current) commit(current, result)
    }

    private fun sessionTrouble(e: Throwable) {
        Log.w(TAG, "sessions", e)
        _view.value = _view.value.copy(error = "Couldn't read play sessions: ${describe(e)}")
    }

    /** Saves [result]'s state when it changed and only then publishes it; on failure the previous state stays, with the error. */
    private fun commit(current: MechaState, result: MechaResult) {
        if (result.state != current) {
            save(result.state)?.let { e ->
                _view.value = MechaView(current, parts, error = "Not saved, nothing changed: ${describe(e)}")
                return
            }
            state = result.state
        }
        _view.value = MechaView(result.state, parts, error = result.error, message = result.message)
    }

    /** Writes [next] to state.json via an fsynced temp file and an atomic move; returns the failure, or null. */
    private fun save(next: MechaState): Exception? {
        val dir = file.parentFile ?: return IOException("${file.path} has no folder")
        val tmp = File(dir, "${file.name}.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create ${dir.path}")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(MechaState.serializer(), next).toByteArray())
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            null
        } catch (e: Exception) {
            Log.w(TAG, "save ${file.path}", e)
            if (tmp.isFile) tmp.delete()
            e
        }
    }

    private companion object {
        const val TAG = "MechaStore"
        const val SHEET_WIDTH = 512
        const val SHEET_HEIGHT = 64

        fun describe(e: Throwable): String = (e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(240)

        /** The three families' parts from the APK's assets, each sheet checked to exist and measure 512x64. */
        fun assetCatalog(assets: AssetManager): List<MechaPart> = MECHA_FAMILIES.flatMap { family ->
            val path = "mecha/$family/catalog.json"
            val text = try {
                assets.open(path).bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw IOException("$path is missing from the app")
            }
            parseMechaCatalog(family, text) { asset -> sheetProblem(assets, asset) }
        }

        fun sheetProblem(assets: AssetManager, asset: String): String? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                assets.open(asset).use { BitmapFactory.decodeStream(it, null, bounds) }
            } catch (e: IOException) {
                return "$asset is missing"
            }
            if (bounds.outWidth == SHEET_WIDTH && bounds.outHeight == SHEET_HEIGHT) return null
            return "$asset is ${bounds.outWidth}x${bounds.outHeight}, not ${SHEET_WIDTH}x$SHEET_HEIGHT"
        }
    }
}
