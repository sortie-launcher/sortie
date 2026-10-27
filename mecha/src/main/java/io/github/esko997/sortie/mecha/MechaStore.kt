package io.github.esko997.sortie.mecha

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.Log
import io.github.esko997.sortie.ui.SortiePalette
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
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
import kotlinx.coroutines.flow.update
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
 * chooses [startFresh]. Play credit comes from the host's completed sessions ([sessions]: in Sortie the Room sessions table,
 * re-emitted whenever it changes; no polling); [refreshSessions] re-reads it on demand.
 *
 * The public constructor takes the app [Context] (for `files/` and the parts in the assets) and `sessions(from)`: the completed
 * (ended, not abandoned) sessions that started at or after `from`, re-emitted whenever they change.
 */
class MechaStore internal constructor(
    locate: () -> File,
    private val loadCatalog: () -> List<MechaPart>,
    private val sessions: (from: Long) -> Flow<List<PlaySession>>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default
) {
    constructor(context: Context, sessions: (from: Long) -> Flow<List<PlaySession>>) : this(
        locate = { File(context.applicationContext.filesDir, "mecha/state.json") },
        loadCatalog = { assetCatalog(context.applicationContext.assets) },
        sessions = sessions
    )

    private val json = Json { encodeDefaults = true }

    /** Resolved on the worker: Context.getFilesDir may touch the disk. */
    private val file: File by lazy(locate)

    /** When the last pet that counted toward bond was, beside the save ([pet]). */
    private val petFile: File by lazy { File(file.parentFile, "pet.txt") }

    private val _view = MutableStateFlow(MechaView())
    val view: StateFlow<MechaView> = _view

    internal val arcadeSession = MechaArcadeSession()

    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pendingChanges = AtomicInteger()

    // Touched only by the worker.
    private var parts: List<MechaPart> = emptyList()
    private var state: MechaState? = null
    private var damaged = false
    private var migrationPending = false
    private var observer: Job? = null
    private var lastPetAt: Long? = null

    init {
        scope.launch {
            for (job in jobs) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "mecha job", e)
                    _view.update { it.copy(error = "Hangar trouble: ${describe(e)}", message = null) }
                }
            }
        }
        enqueue { open() }
    }

    internal fun rename(name: String) = change { it.renamed(name) }

    internal fun equip(partId: String) = change { it.equipping(partId, parts) }

    internal fun care(action: MechaCare) = change { it.cared(action) }

    internal fun buyPart(partId: String) = change { it.buyingPart(partId, parts) }

    internal fun buyFinish(finish: MechaFinish) = change { it.buyingFinish(finish) }

    internal fun setFinish(slot: MechaSlot, finish: MechaFinish) = change { it.finishing(slot, finish) }

    internal fun buyTheme(palette: SortiePalette) = change { it.buyingTheme(palette) }

    internal fun setTheme(palette: SortiePalette) = change { it.selectingTheme(palette) }

    internal fun completeIntroduction(hideCompanion: Boolean = false) = change { it.introduced(hideCompanion) }

    internal fun insertArcadeCoin(expectedSequence: Int) = change { current ->
        val problem = current.arcadeEntryProblem(expectedSequence)
        if (problem != null) {
            MechaResult(current, error = problem)
        } else {
            current.insertingArcadeCoin(expectedSequence, random.nextLong())
        }
    }

    internal fun selectArcadeGame(ticket: MechaArcadeTicket, game: MechaArcadeGame) = change { it.selectingArcadeGame(ticket, game) }

    internal fun finishArcade(ticket: MechaArcadeTicket, score: Int) = change { it.finishingArcade(ticket, score) }

    internal fun abandonArcade(ticket: MechaArcadeTicket) = change { it.abandoningArcade(ticket) }

    internal fun fitBuild(equipped: Map<MechaSlot, String>) {
        val selection = equipped.toMap()
        change { it.fittingBuild(selection, parts) }
    }

    internal fun spin(tier: MechaCabinetTier, expectedSequence: Int) = change { current ->
        val available = if (tier == MechaCabinetTier.PART) parts.count { it.id !in current.owned } else 0
        val problem = when {
            expectedSequence != current.cabinetSpins -> "That cabinet turn has already finished."
            current.cabinetSpins == Int.MAX_VALUE -> "The cabinet's turn counter is full."
            current.salvage < tier.stake -> "Not enough salvage credits."
            tier == MechaCabinetTier.PART && available == 0 -> "Every part is already owned."
            tier.finish != null && tier.finish in current.ownedFinishes -> "That finish is already owned."
            else -> null
        }
        if (problem != null) {
            MechaResult(current, error = problem)
        } else {
            // Resolve once on the serialized worker; the receipt, unlock and wallet are committed together.
            val roll = random.nextInt(100)
            val rewardIndex = if (available > 0) random.nextInt(available) else 0
            current.cabinetSpun(tier, parts, roll, rewardIndex)
        }
    }

    internal fun setEnabled(enabled: Boolean) = change { it.withEnabled(enabled) }

    internal fun setMotion(enabled: Boolean) = change { it.withMotion(enabled) }

    /**
     * A tap on him: a little bond at most once per [MECHA_PET_BOND_MS] ([mechaPetCounts]). When the last counted pet was is
     * kept beside the save in `pet.txt` (written first, so a crash between the two can only lose a point, never allow an
     * extra one), not in state.json, so the save format stays readable by every build. Pets inside the window write nothing.
     */
    internal fun pet() = enqueue {
        val current = state ?: return@enqueue
        val now = clock()
        val last = lastPetAt ?: readPetTime().also { lastPetAt = it }
        if (!mechaPetCounts(now, last)) return@enqueue
        // the window must be on disk before the bond is: a pet that can't be recorded earns nothing
        if (writeAtomically(petFile, now.toString().toByteArray()) != null) return@enqueue
        lastPetAt = now
        commit(current, current.petted())
    }

    /** The saved hangar, or null when absent/unreadable; a pending valid migration must not disappear from a backup. */
    suspend fun backupSave(): MechaState? = onWorker {
        check(!migrationPending) { "Retry opening the Hangar before exporting or restoring a backup; its saved upgrade is still pending." }
        state
    }

    /** Why [backup] (a companion save from a backup) can't be restored by this build, or null when it can. */
    suspend fun restoreProblem(backup: MechaState): String? = onWorker {
        if (migrationPending) {
            "open the Hangar and retry its pending save upgrade before restoring"
        } else if (parts.isEmpty()) {
            "this build's mecha parts catalog is incomplete"
        } else {
            mechaStateProblem(backup, parts)?.let { "the companion save in it can't be used: $it" }
        }
    }

    /**
     * Restores from a backup, before its play history is merged in: [backup] replaces this hangar (play either hangar already
     * credited stays credited, [restoredOver]); with no [backup] save this hangar stays and [merged], the sessions the
     * restore is about to add, count as credited elsewhere ([creditedElsewhere]). An unreadable save is set aside first, as
     * [startFresh] does. Throws when [backup] is invalid or nothing could be written (then nothing changed). The result undoes
     * it with [undoRestore] if the rest of the restore fails.
     */
    suspend fun restore(backup: MechaState?, merged: List<PlaySession>): MechaRestore = onWorker {
        check(!migrationPending) { "Open the Hangar and retry its pending save upgrade before restoring." }
        val before = state
        if (backup == null) {
            if (before != null) commitOrThrow(before, before.creditedElsewhere(merged))
            return@onWorker MechaRestore(before, replaced = false)
        }
        require(parts.isNotEmpty()) { "this build's mecha parts catalog is incomplete" }
        mechaStateProblem(backup, parts)?.let { throw IllegalArgumentException("the companion save in it can't be used: $it") }
        if (before == null && damaged) {
            val kept = File(file.parentFile, "state.damaged-${clock()}.json")
            Files.copy(file.toPath(), kept.toPath())
        }
        val next = backup.upgraded().restoredOver(before)
        save(next)?.let { throw it }
        damaged = false
        migrationPending = false
        state = next
        publish(MechaView(next, parts, message = "${next.name} is back from the backup."))
        observe(next.initializedAt)
        MechaRestore(before, replaced = true)
    }

    /** Puts back what [restore] replaced, when the rest of a restore failed. Throws when it can't be written. */
    suspend fun undoRestore(done: MechaRestore) = onWorker {
        val previous = done.previous ?: return@onWorker
        save(previous)?.let { throw it }
        state = previous
        publish(MechaView(previous, parts))
        observe(previous.initializedAt)
    }

    private fun commitOrThrow(current: MechaState, result: MechaResult) {
        if (result.state == current) return
        save(result.state)?.let { throw it }
        state = result.state
        publish(MechaView(result.state, parts))
    }

    /** Re-reads completed sessions now (e.g. when Sortie comes back to the foreground); already credited ones are skipped. */
    internal fun refreshSessions() = enqueue {
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

    /** Retries a failed open without treating a valid legacy file as damaged or replacing it. */
    internal fun retryOpen() = enqueue {
        if (state == null) open()
    }

    /**
     * Only when the hangar could not be opened: sets an unreadable save aside as `state.damaged-<time>.json` (same bytes) and
     * opens a new hangar, which counts play from now on. Does nothing while a hangar is open or the parts catalog is incomplete.
     */
    internal fun startFresh() = enqueue {
        if (state != null || parts.isEmpty() || migrationPending) return@enqueue
        if (damaged) {
            val kept = File(file.parentFile, "state.damaged-${clock()}.json")
            try {
                Files.copy(file.toPath(), kept.toPath())
            } catch (e: Exception) {
                Log.w(TAG, "set damaged save aside", e)
                publish(MechaView(parts = parts, error = "Couldn't set the damaged save aside (${describe(e)}); it is still at ${file.path}."))
                return@enqueue
            }
            create("Fresh hangar opened; the damaged save is kept as ${kept.name}.")
            if (state != null) damaged = false
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

    /** Runs [job] on the worker after everything queued before it and returns its result (or throws its failure). */
    private suspend fun <T> onWorker(job: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        enqueue {
            try {
                done.complete(job())
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return done.await()
    }

    private fun change(rule: (MechaState) -> MechaResult) {
        pendingChanges.incrementAndGet()
        _view.update { it.copy(busy = true) }
        enqueue {
            try {
                // No hangar: preserve the existing loading, incomplete catalog or unreadable-save explanation.
                val current = state ?: return@enqueue
                commit(current, rule(current))
            } finally {
                pendingChanges.decrementAndGet()
                _view.update { it.copy(busy = pendingChanges.get() > 0) }
            }
        }
    }

    private fun publish(next: MechaView) {
        _view.update { next.copy(busy = pendingChanges.get() > 0) }
    }

    private fun open() {
        parts = try {
            loadCatalog()
        } catch (e: Exception) {
            Log.w(TAG, "catalog", e)
            publish(MechaView(error = "The mecha parts catalog is incomplete: ${describe(e)}"))
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
            publish(
                MechaView(
                    parts = parts,
                    error = "The hangar save can't be used: ${describe(e)}. It was left untouched at ${file.path}; " +
                        "Start fresh keeps a copy and opens a new hangar.",
                    canStartFresh = true
                )
            )
            return
        }
        val upgraded = loaded.upgraded()
        if (upgraded != loaded) {
            save(upgraded)?.let { e ->
                // This is a valid legacy save, not damage: never offer to replace it after an upgrade IO failure.
                migrationPending = true
                publish(MechaView(parts = parts, error = "Couldn't upgrade the hangar; its save was left untouched: ${describe(e)}"))
                return
            }
        }
        migrationPending = false
        damaged = false
        state = upgraded
        publish(MechaView(upgraded, parts))
        observe(upgraded.initializedAt)
    }

    private fun create(message: String?) {
        val fresh = starterMechaState(parts, clock())
        save(fresh)?.let { e ->
            publish(MechaView(parts = parts, error = "Couldn't set up the hangar: ${describe(e)}"))
            return
        }
        migrationPending = false
        damaged = false
        state = fresh
        publish(
            MechaView(
                fresh,
                parts,
                message = message ?: "${fresh.name} rolled out of the crate with $MECHA_STARTER_CREDITS salvage credits."
            )
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

    private fun credit(list: List<PlaySession>) {
        val current = state ?: return
        val result = current.credited(list)
        if (result.state != current) commit(current, result)
    }

    private fun sessionTrouble(e: Throwable) {
        Log.w(TAG, "sessions", e)
        _view.update { it.copy(error = "Couldn't read play sessions: ${describe(e)}") }
    }

    /** Saves [result]'s state when it changed and only then publishes it; on failure the previous state stays, with the error. */
    private fun commit(current: MechaState, result: MechaResult) {
        if (result.state != current) {
            save(result.state)?.let { e ->
                publish(MechaView(current, parts, error = "Not saved, nothing changed: ${describe(e)}"))
                return
            }
            state = result.state
        }
        publish(MechaView(result.state, parts, error = result.error, message = result.message))
    }

    /** Writes [next] to state.json via an fsynced temp file and an atomic move; returns the failure, or null. */
    private fun save(next: MechaState): Exception? = writeAtomically(file, json.encodeToString(MechaState.serializer(), next).toByteArray())

    /** Writes [bytes] to [target] via an fsynced temp file and an atomic move; returns the failure, or null. */
    private fun writeAtomically(target: File, bytes: ByteArray): Exception? {
        val dir = target.parentFile ?: return IOException("${target.path} has no folder")
        val tmp = File(dir, "${target.name}.tmp")
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("can't create ${dir.path}")
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            null
        } catch (e: Exception) {
            Log.w(TAG, "save ${target.path}", e)
            if (tmp.isFile) tmp.delete()
            e
        }
    }

    /** When the last counted pet was (0: never, or the file is missing or unreadable: a pet then simply counts). */
    private fun readPetTime(): Long = try {
        if (petFile.isFile) petFile.readText().trim().toLongOrNull()?.coerceAtLeast(0) ?: 0 else 0
    } catch (e: IOException) {
        Log.w(TAG, "read ${petFile.path}", e)
        0
    }

    private companion object {
        const val TAG = "MechaStore"
        const val SHEET_WIDTH = 512
        const val SHEET_HEIGHT = 64

        fun describe(e: Throwable): String = (e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName).take(240)

        /** All authored families' parts from the APK's assets, each sheet checked to exist and measure 512x64. */
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

/** What [MechaStore.restore] replaced, for [MechaStore.undoRestore]. [replaced]: the backup brought its own hangar. */
class MechaRestore internal constructor(internal val previous: MechaState?, val replaced: Boolean)
