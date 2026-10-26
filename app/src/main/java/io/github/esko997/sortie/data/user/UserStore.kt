package io.github.esko997.sortie.data.user

import android.util.Log
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.manifest.BOOKMARK_MAX_CHARS
import io.github.esko997.sortie.data.manifest.BrowseChromeMode
import io.github.esko997.sortie.data.manifest.DOCK_SLOTS
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.GameBookmark
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.stats.perfInterval
import io.github.esko997.sortie.stats.statsInterval
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
import kotlinx.serialization.json.JsonObject

/**
 * Favourites, the Want to play queue, hidden entries, layout and per-game profile overrides, persisted as `<root>/sortie/user.json`.
 * Writes are serialised through one collector that always writes the newest state: concurrent writers of their own snapshots
 * would race on the same `.tmp` file and could leave an older state on disk after a burst of edits (layout moves, favourites).
 *
 * Durable operations ([replace], [commit]) share the collector's lock and never publish before their fsynced write succeeded.
 * Ordinary edits made while one is writing are applied to the published state as usual and also journaled; once the durable
 * write succeeds they are replayed onto its result (and saved by the collector), so neither side loses the other's changes.
 *
 * user.json is never overwritten with state that did not come from it: until this store has read it (or written it itself), an
 * existing user.json (restored onto a card that was missing at startup, or one that could not be read or parsed then) is left
 * alone and writes fail with [writeError]; a restart loads it ([reloadable]). The directory is only created when
 * [mayCreateDirectory] allows it, so a missing remembered library folder is never recreated empty.
 *
 * A user.json from before bookmarks is migrated as it loads ([LegacyUserMigration]): its journal entries, mapping notes and
 * "where was I?" notes are first copied to a readable archive in [archiveDir] ([LegacyNotesArchive]), and only then is the
 * migrated state written. Until that archive exists every write of user.json is refused (retrying the archive first), so
 * the old writing is never overwritten without a copy.
 */
class UserStore(
    private val file: File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val mayCreateDirectory: () -> Boolean = { true },
    archiveDir: File = File(file.absoluteFile.parentFile, LegacyNotesArchive.DIR),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Whether user.json holds this store's state: it was loaded at startup, or this store has written it since. Declared before
     * [_data], whose initializer sets it. Changed under [writeLock] afterwards.
     */
    @Volatile private var owned = false

    /** Where legacy writing is archived; also used by backup restore for an older backup's journal and notes. */
    val archive = LegacyNotesArchive(archiveDir)

    /** Legacy writing found in the loaded user.json that is not archived yet: user.json must not be written until it is. */
    @Volatile private var pendingLegacy: LegacyNotes? = null

    /** The loaded user.json still had legacy fields: once archived, the migrated state is written back. */
    private var migratedOnLoad = false

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
        // rewrite a migrated user.json without its legacy fields (the write archives them first if that has not happened yet)
        if (migratedOnLoad) dirty.trySend(Unit)
    }

    private fun load(): UserData {
        val root = readRoot() ?: return UserData()
        val migrated = LegacyUserMigration.migrate(root)
        val data = decode(migrated.user) ?: return UserData()
        owned = true
        migratedOnLoad = migrated.user != root
        migrated.legacy?.let { legacy ->
            pendingLegacy = legacy
            runCatching { archivePending() }.onFailure { Log.w("UserStore", "archive legacy notes", it) }
        }
        return data
    }

    /** user.json as a JSON object, or null when it is missing, unreadable or not an object. */
    private fun readRoot(): JsonObject? = try {
        json.parseToJsonElement(file.readText()) as? JsonObject
    } catch (e: Exception) {
        if (file.exists()) Log.w("UserStore", "read user.json", e)
        null
    }

    private fun decode(root: JsonObject): UserData? = try {
        json.decodeFromJsonElement(UserData.serializer(), root)
    } catch (e: Exception) {
        Log.w("UserStore", "decode user.json", e)
        null
    }

    /** user.json's contents (migrated in memory, nothing archived or written), or null when it is missing, unreadable or not valid. */
    private fun decode(): UserData? = readRoot()?.let { decode(LegacyUserMigration.migrate(it).user) }

    /** Archives [pendingLegacy], if any, and clears it. Throws when the archive cannot be written. */
    private fun archivePending() {
        val legacy = pendingLegacy ?: return
        val saved = archive.save(legacy, file.name, clock())
        Log.i("UserStore", "archived ${legacy.describe()} to ${saved.path}")
        pendingLegacy = null
    }

    /**
     * Archives legacy writing found elsewhere (an older backup about to be restored) in [archive]; returns the readable file.
     * Throws when it cannot be written: the caller must then not drop that writing.
     */
    suspend fun archiveLegacy(legacy: LegacyNotes, source: String): File = withContext(Dispatchers.IO) { archive.save(legacy, source, clock()) }

    /**
     * Makes sure older journal entries and notes from the loaded user.json are archived (retrying now if that failed so far).
     * [data] no longer holds them, so anything that copies it elsewhere (a backup export, a safety copy) calls this first and
     * must stop when it throws: the copy would otherwise leave that writing out.
     */
    suspend fun ensureLegacyArchived() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            try {
                archivePending()
            } catch (e: Exception) {
                throw IOException(
                    "older journal entries and notes in ${file.name} could not be archived to ${archive.dir.path} " +
                        "(${e.message ?: e.javaClass.simpleName})",
                    e
                )
            }
        }
    }

    /** True when a user.json this store did not load now reads: only a restart picks it up. Reads the file. */
    fun reloadable(): Boolean = !owned && file.isFile && decode() != null

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

    /**
     * Writes [snapshot] via `.tmp` + rename; [sync] also fsyncs the temp file first. Returns the failure, or null. Caller holds
     * [writeLock]. Legacy writing from the loaded file is archived first; if that fails nothing is written.
     */
    private fun write(snapshot: UserData, sync: Boolean): Throwable? {
        val failure = runCatching {
            val dir = file.parentFile
            if (dir != null && !dir.isDirectory) {
                if (!mayCreateDirectory()) throw IOException("the library folder ${dir.path} is unavailable; reconnect its storage")
                dir.mkdirs()
            }
            if (!owned && file.exists()) {
                throw IOException("Sortie has not loaded ${file.path} and won't save over it; reopen Sortie, or fix or move that file")
            }
            try {
                archivePending()
            } catch (e: Exception) {
                throw IOException(
                    "older journal entries and notes in ${file.name} could not be archived to ${archive.dir.path} " +
                        "(${e.message ?: e.javaClass.simpleName}), so ${file.name} is left as it is",
                    e
                )
            }
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(UserData.serializer(), snapshot).toByteArray())
                out.flush()
                if (sync) out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
            owned = true
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

    /** Saves [id]'s controller requirement durably (see [commit]); an empty one ([GameAnnotation.isEmpty]) removes the entry. */
    suspend fun saveGameAnnotation(id: String, annotation: GameAnnotation): UserData = commit {
        it.copy(gameAnnotations = if (annotation.isEmpty()) it.gameAnnotations - id else it.gameAnnotations + (id to annotation))
    }

    /**
     * Saves [text] (trimmed) as [id]'s one bookmark durably, replacing the previous one; a blank [text] removes it (the explicit
     * Clear). Text over [BOOKMARK_MAX_CHARS] is refused with [IllegalArgumentException], never truncated.
     */
    suspend fun saveBookmark(id: String, text: String): UserData {
        val trimmed = text.trim()
        require(trimmed.length <= BOOKMARK_MAX_CHARS) { "a bookmark holds at most $BOOKMARK_MAX_CHARS characters (this one has ${trimmed.length})" }
        val at = clock()
        return commit { u -> u.copy(bookmarks = if (trimmed.isEmpty()) u.bookmarks - id else u.bookmarks + (id to GameBookmark(trimmed, at))) }
    }

    fun setBookmarkInvitation(on: Boolean) = update { it.copy(bookmarkInvitation = on) }

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

    /** Stores [mode] as a known [BrowseChromeMode] id; anything else becomes "auto". */
    fun setBrowseChrome(mode: String) = update { it.copy(browseChrome = BrowseChromeMode.of(mode).id) }

    fun setHomeSort(sort: String) = update { it.copy(homeSort = sort) }

    /** Appends to the custom layout (no duplicates). */
    fun addToLayout(id: String) = update { if (id in it.layout) it else it.copy(layout = it.layout + id) }

    fun removeFromLayout(id: String) = update { it.copy(layout = it.layout - id) }

    fun setLayout(ids: List<String>) = update { it.copy(layout = ids.distinct()) }

    fun setSystemProfile(systemId: String, profileId: String?) =
        update { it.copy(systemProfiles = if (profileId == null) it.systemProfiles - systemId else it.systemProfiles + (systemId to profileId)) }

    /** Remembers the Cocoon directory an import read from (null forgets it) so the next import offers it first. */
    fun setCocoonDir(path: String?) = update { it.copy(cocoonDir = path) }

    /** Pins [itemId] (null clears) into dock [slot]; the list is padded to [DOCK_SLOTS] with empty strings. */
    fun setDockSlot(slot: Int, itemId: String?) = update {
        val slots = MutableList(DOCK_SLOTS) { i -> it.dock.getOrNull(i).orEmpty() }
        if (slot in slots.indices) slots[slot] = itemId.orEmpty()
        it.copy(dock = slots)
    }

    fun setHeroRotation(on: Boolean) = update { it.copy(heroRotation = on) }

    fun setOverlaySwitcher(on: Boolean) = update { it.copy(overlaySwitcher = on) }

    fun setDeviceStats(mode: String) = update { it.copy(deviceStats = mode) }

    fun setDeviceStatsInterval(seconds: Int) = update { it.copy(deviceStatsInterval = statsInterval(seconds)) }

    fun setPerfInterval(seconds: Int) = update { it.copy(perfInterval = perfInterval(seconds)) }

    fun setCompanionView(view: String) = update { it.copy(companionView = view) }

    fun showShortcut(id: String, shown: Boolean) = update { it.copy(shortcuts = if (shown) it.shortcuts + id else it.shortcuts - id) }
}
