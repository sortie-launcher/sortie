package io.github.esko997.sortie.data.user

import android.util.Log
import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
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
import kotlinx.serialization.json.Json

/**
 * Favourites, hidden entries, layout and per-game profile overrides, persisted as `<root>/sortie/user.json` (device-owned).
 * Writes are serialised through one collector that always writes the newest state: concurrent writers of their own snapshots
 * would race on the same `.tmp` file and could leave an older state on disk after a burst of edits (layout moves, favourites).
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

    /** Completes after each write; tests await it to observe the file. */
    val written = MutableSharedFlow<UserData>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch { for (unit in dirty) write(_data.value) }
    }

    private fun load(): UserData = runCatching { json.decodeFromString(UserData.serializer(), file.readText()) }.getOrDefault(UserData())

    private fun update(f: (UserData) -> UserData) {
        _data.update(f)
        dirty.trySend(Unit)
    }

    private fun write(snapshot: UserData) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(UserData.serializer(), snapshot))
            if (!tmp.renameTo(file)) Log.w("UserStore", "rename failed for ${file.path}")
        }.onFailure { Log.w("UserStore", "write user.json", it) }
        written.tryEmit(snapshot)
    }

    fun toggleFavourite(id: String) = update { it.copy(favourites = if (id in it.favourites) it.favourites - id else it.favourites + id) }

    fun setOverride(id: String, profileId: String?) =
        update { it.copy(profileOverrides = if (profileId == null) it.profileOverrides - id else it.profileOverrides + (id to profileId)) }

    fun hide(id: String, hidden: Boolean) = update { it.copy(hidden = if (hidden) it.hidden + id else it.hidden - id) }

    fun setHomeLayout(layout: String) = update { it.copy(homeLayout = layout) }

    fun setGridColumns(n: Int) = update { it.copy(gridColumns = n.coerceIn(2, 10)) }

    fun setBrowseLayout(layout: String) = update { it.copy(browseLayout = layout) }

    fun setHomeSort(sort: String) = update { it.copy(homeSort = sort) }

    /** Appends to the custom layout (no duplicates). */
    fun addToLayout(id: String) = update { if (id in it.layout) it else it.copy(layout = it.layout + id) }

    fun removeFromLayout(id: String) = update { it.copy(layout = it.layout - id) }

    fun setLayout(ids: List<String>) = update { it.copy(layout = ids.distinct()) }

    fun setSystemProfile(systemId: String, profileId: String?) =
        update { it.copy(systemProfiles = if (profileId == null) it.systemProfiles - systemId else it.systemProfiles + (systemId to profileId)) }

    fun setHeroRotation(on: Boolean) = update { it.copy(heroRotation = on) }

    fun setOverlaySwitcher(on: Boolean) = update { it.copy(overlaySwitcher = on) }

    fun showShortcut(id: String, shown: Boolean) = update { it.copy(shortcuts = if (shown) it.shortcuts + id else it.shortcuts - id) }
}
