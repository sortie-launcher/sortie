package io.github.esko997.sortie.data.user

import android.util.Log
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.manifest.DOCK_SLOTS
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Favourites, the Want to play queue, hidden entries, layout and per-game profile overrides, persisted as `<root>/sortie/user.json`.
 * Writes are serialised through one collector that always writes the newest state: concurrent writers of their own snapshots
 * would race on the same `.tmp` file and could leave an older state on disk after a burst of edits (layout moves, favourites).
 *
 * Durable operations ([replace], [commit]) share the collector's lock and never publish before their fsynced write succeeded.
 * Ordinary edits made while one is writing are applied to the published state as usual and also journaled; once the durable
 * write succeeds they are replayed onto its result (and saved by the collector), so neither side loses the other's changes.
 */
class UserStore(private val file: File, private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data

    /** One slot that is kept until the writer takes it (a SharedFlow would drop a signal emitted before the writer subscribes). */
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** Held for every write of user.json / user.json.tmp. */
    private val writeLock = Mutex()

    /** Emits each snapshot once it is on disk; a failed write does not emit (see [writeError]). Tests await it to observe the file. */
    val written = MutableSharedFlow<UserData>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _writeError = MutableStateFlow<String?>(null)

    /** Null after a successful write; a message after a failed one (the in-memory state is then ahead of the card). */
    val writeError: StateFlow<String?> = _writeError

    init {
        scope.launch { for (unit in dirty) writeLock.withLock { write(_data.value, sync = false) } }
    }

    private fun load(): UserData = runCatching { json.decodeFromString(UserData.serializer(), file.readText()) }.getOrDefault(UserData())

    /** Guards [_data] mutations against [inFlight] bookkeeping. */
    private val lock = Any()

    /** Ordinary edits made while a durable write is in progress, oldest first; null when none is. Guarded by [lock]. */
    private var inFlight: MutableList<(UserData) -> UserData>? = null

    private fun update(f: (UserData) -> UserData) {
        synchronized(lock) {
            _data.update(f)
            inFlight?.add(f)
        }
        dirty.trySend(Unit)
    }

    /** Writes [snapshot] via `.tmp` + rename; [sync] also fsyncs the temp file first. Returns the failure, or null. Caller holds [writeLock]. */
    private fun write(snapshot: UserData, sync: Boolean): Throwable? {
        val failure = runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(UserData.serializer(), snapshot).toByteArray())
                out.flush()
                if (sync) out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
        }.exceptionOrNull()
        if (failure == null) {
            _writeError.value = null
            written.tryEmit(snapshot)
        } else {
            Log.w("UserStore", "write user.json", failure)
            _writeError.value = "user.json not saved: ${failure.message ?: failure.javaClass.simpleName}"
        }
        return failure
    }

    /**
     * Writes [compute] of the current state (fsynced) and only then publishes it, with any ordinary edit made during the write
     * replayed on top. Returns the published state, or null without writing when [check] rejects the current state. On a write
     * failure nothing is published (user.json still holds the previous state: the rename is the last step) and it is thrown;
     * edits made meanwhile stay published and the collector saves them.
     */
    private suspend fun writeThenPublish(check: (UserData) -> Boolean, compute: (UserData) -> UserData): UserData? = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val base = synchronized(lock) {
                val current = _data.value
                if (!check(current)) return@withLock null
                inFlight = ArrayList()
                current
            }
            val outcome = runCatching { compute(base).also { next -> write(next, sync = true)?.let { throw it } } }
            synchronized(lock) {
                val pending = inFlight.orEmpty()
                inFlight = null
                val next = outcome.getOrThrow()
                val published = pending.fold(next) { acc, f -> f(acc) }
                _data.value = published
                if (pending.isNotEmpty()) dirty.trySend(Unit)
                published
            }
        }
    }

    /**
     * Replaces every preference with [next] (backup restore) and returns once it is fsynced to the card. Returns false,
     * writing nothing, when the state is no longer [expected] (an edit landed after the caller read it; re-read and retry).
     * Throws the write failure with the published state untouched.
     */
    suspend fun replace(expected: UserData, next: UserData): Boolean = writeThenPublish({ it == expected }, { next }) != null

    /**
     * Applies [f] to the current state and returns once the result is fsynced and published. On a write failure nothing is
     * published and the failure is thrown, so an unsaved change never lingers in memory to be persisted by a later write.
     */
    suspend fun commit(f: (UserData) -> UserData): UserData = checkNotNull(writeThenPublish({ true }, f))

    /** Saves [id]'s personal annotation durably (see [commit]); an empty one ([GameAnnotation.isEmpty]) removes the entry. */
    suspend fun saveGameAnnotation(id: String, annotation: GameAnnotation): UserData = commit {
        it.copy(gameAnnotations = if (annotation.isEmpty()) it.gameAnnotations - id else it.gameAnnotations + (id to annotation))
    }

    fun toggleFavourite(id: String) = update { it.copy(favourites = if (id in it.favourites) it.favourites - id else it.favourites + id) }

    /**
     * Adds game [id] to the Want to play queue or removes it, returning the new membership (apps and shortcuts are refused: false,
     * nothing changes). The layout is untouched: the built-in folder is always on the custom home. The journaled edit is "set
     * membership", not "flip", so replaying it over a concurrent restore cannot invert the user's choice.
     */
    fun toggleWantToPlay(id: String): Boolean {
        if (!LibraryItem.isGameId(id)) return false
        return synchronized(lock) {
            val add = id !in _data.value.wantToPlay
            update { u -> u.copy(wantToPlay = if (add) u.wantToPlay + id else u.wantToPlay - id) }
            add
        }
    }

    fun setOverride(id: String, profileId: String?) =
        update { it.copy(profileOverrides = if (profileId == null) it.profileOverrides - id else it.profileOverrides + (id to profileId)) }

    fun hide(id: String, hidden: Boolean) = update { it.copy(hidden = if (hidden) it.hidden + id else it.hidden - id) }

    fun setHomeLayout(layout: String) = update { it.copy(homeLayout = layout) }

    fun setGridColumns(n: Int) = update { it.copy(gridColumns = n.coerceIn(2, 10)) }

    fun setGridHorizontal(on: Boolean) = update { it.copy(gridHorizontal = on) }

    fun setAppColumns(n: Int) = update { it.copy(appColumns = n.coerceIn(4, 14)) }

    fun setGridRows(n: Int) = update { it.copy(gridRows = n.coerceIn(2, 6)) }

    fun setBrowseLayout(layout: String) = update { it.copy(browseLayout = layout) }

    fun setHomeSort(sort: String) = update { it.copy(homeSort = sort) }

    /** Appends to the custom layout (no duplicates). */
    fun addToLayout(id: String) = update { if (id in it.layout) it else it.copy(layout = it.layout + id) }

    fun removeFromLayout(id: String) = update { it.copy(layout = it.layout - id) }

    fun setLayout(ids: List<String>) = update { it.copy(layout = ids.distinct()) }

    fun setSystemProfile(systemId: String, profileId: String?) =
        update { it.copy(systemProfiles = if (profileId == null) it.systemProfiles - systemId else it.systemProfiles + (systemId to profileId)) }

    /** Pins [itemId] (null clears) into dock [slot]; the list is padded to [DOCK_SLOTS] with empty strings. */
    fun setDockSlot(slot: Int, itemId: String?) = update {
        val slots = MutableList(DOCK_SLOTS) { i -> it.dock.getOrNull(i).orEmpty() }
        if (slot in slots.indices) slots[slot] = itemId.orEmpty()
        it.copy(dock = slots)
    }

    fun setHeroRotation(on: Boolean) = update { it.copy(heroRotation = on) }

    fun setOverlaySwitcher(on: Boolean) = update { it.copy(overlaySwitcher = on) }

    fun showShortcut(id: String, shown: Boolean) = update { it.copy(shortcuts = if (shown) it.shortcuts + id else it.shortcuts - id) }
}
