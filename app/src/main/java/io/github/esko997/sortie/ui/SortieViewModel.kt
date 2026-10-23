package io.github.esko997.sortie.ui

import android.app.Application
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutCreator
import io.github.esko997.sortie.data.apps.ShortcutEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.db.byIds
import io.github.esko997.sortie.data.db.likeEscape
import io.github.esko997.sortie.data.folders.FolderView
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import io.github.esko997.sortie.launch.IntentBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SystemRow(val def: SystemDef, val count: Int)

data class IndexState(val running: Boolean = false, val report: Indexer.Report? = null)

/** One view model for the whole app; screens are thin. */
class SortieViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as SortieApp).graph
    private val dao = graph.db.games()
    val manifests = graph.manifests.snapshot
    val user = graph.user.data
    val launchLog = graph.launcher.log

    private val _index = MutableStateFlow(IndexState())
    val index: StateFlow<IndexState> = _index

    private val _storageGranted = MutableStateFlow(true)
    val storageGranted: StateFlow<Boolean> = _storageGranted

    private var deviceScanned = false

    /**
     * Activity start: re-check the all-files permission and recover manifests after a grant. The library is NOT re-walked here:
     * the index is fixed until the first run (empty table), a manifest deploy (hot reload) or an explicit re-index in Diagnostics.
     */
    fun onStart(granted: Boolean) {
        _storageGranted.value = granted
        if (!granted) return
        if (manifests.value.errors.isNotEmpty()) graph.manifests.reload()
        viewModelScope.launch { if (dao.total().first() == 0) reindex() }
        if (!deviceScanned) {
            deviceScanned = true
            viewModelScope.launch { graph.deviceState.refresh() }
        }
        refreshApps()
        refreshHeroes()
    }

    init {
        // A manifest deploy from the desktop is the explicit signal that the card changed.
        viewModelScope.launch { manifests.map { it.loadedAt }.drop(1).distinctUntilChanged().collect { reindex() } }
    }

    val apps: StateFlow<List<AppEntry>> = graph.apps.apps
    val shortcuts: StateFlow<List<ShortcutEntry>> = graph.shortcuts.shortcuts

    /** Installed apps and launcher shortcuts; called on every activity start. */
    fun refreshApps() = viewModelScope.launch {
        graph.apps.refresh()
        graph.shortcuts.refresh()
    }

    fun appIcon(pkg: String) = graph.apps.icon(pkg)

    /** The shortcut's own icon, else its publishing app's icon. */
    fun shortcutIcon(s: ShortcutEntry): Drawable? = graph.shortcuts.icon(s) ?: graph.apps.icon(s.pkg)

    fun launchApp(app: AppEntry) = viewModelScope.launch {
        val intent = graph.apps.launchIntent(app)
        graph.launcher.launchExternal(app.id, IntentBuilder.describe(intent)) { opts -> getApplication<Application>().startActivity(intent, opts) }
    }

    fun launchShortcut(s: ShortcutEntry) = viewModelScope.launch {
        graph.launcher.launchExternal(s.id, "startShortcut ${s.pkg}/${s.shortcutId}") { opts -> graph.shortcuts.launch(s, opts) }
    }

    /** Apps and shortcuts launch straight away; games return false so the caller opens their detail screen instead. */
    fun launchItem(item: LibraryItem): Boolean = when (item) {
        is LibraryItem.App -> launchApp(item.app).let { true }
        is LibraryItem.Shortcut -> launchShortcut(item.shortcut).let { true }
        is LibraryItem.Game -> false
    }

    suspend fun shortcutCreators(): List<ShortcutCreator> = graph.shortcuts.creators()

    fun createShortcutIntent(c: ShortcutCreator): Intent = Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(c.component)

    /**
     * Result of an `ACTION_CREATE_SHORTCUT` activity: accept its pin request and opt the shortcut in to the Apps screen (asking
     * an app for a shortcut is the user's explicit act). Returns the new shortcut's label, or null.
     */
    fun acceptShortcut(result: Intent?): String? = graph.shortcuts.accept(result)?.let {
        graph.user.showShortcut(it.id, true)
        it.label
    }

    /** Opts a launcher shortcut in to (or out of) the Apps screen. */
    fun showShortcut(id: String, shown: Boolean) = graph.user.showShortcut(id, shown)

    fun setHomeLayout(layout: String) = graph.user.setHomeLayout(layout)

    fun setGridColumns(n: Int) = graph.user.setGridColumns(n)

    /** List or grid for every browse screen (system, folder, search, apps); one setting, separate from home. */
    fun setBrowseLayout(layout: String) = graph.user.setBrowseLayout(layout)

    fun setHomeSort(sort: String) {
        if (sort == "console" && user.value.homeSort != "console") homeSortBefore = user.value.homeSort
        graph.user.setHomeSort(sort)
    }

    /** The sort to return to when "By Console" is left with B. */
    private var homeSortBefore = "alpha"

    fun leaveConsoleSort() = graph.user.setHomeSort(homeSortBefore.takeIf { it != "console" } ?: "alpha")

    /** L1/R1 on Home: the next/previous sort mode (list order from HomeScreen). */
    fun cycleHomeSort(delta: Int, sorts: List<String>) {
        val i = sorts.indexOf(user.value.homeSort).coerceAtLeast(0)
        setHomeSort(sorts[(i + delta + sorts.size) % sorts.size])
    }

    /** The console shown by the "By Console" home sort (session state; first system until chosen). */
    val homeConsole = MutableStateFlow<String?>(null)

    fun cycleHomeConsole(delta: Int) {
        val ids = systems.value.map { it.def.id }
        if (ids.isEmpty()) return
        val i = ids.indexOf(homeConsole.value)
        homeConsole.value = ids[((if (i < 0) 0 else i + delta) + ids.size) % ids.size]
    }

    fun setLayout(ids: List<String>) = graph.user.setLayout(ids)

    fun removeFromLayout(id: String) = graph.user.removeFromLayout(id)

    /** Places [id] on the custom home layout, or takes it off when already placed. Returns true when it is placed now. */
    fun toggleHome(id: String): Boolean {
        val placed = id in user.value.layout
        if (placed) graph.user.removeFromLayout(id) else graph.user.addToLayout(id)
        return !placed
    }

    /** Games placed on the custom home layout, by id; re-read when the layout's game ids or the index change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val layoutGames: StateFlow<Map<String, GameEntity>> =
        combine(user.map { u -> u.layout.filter { HomeIds.isGame(it) } }.distinctUntilChanged(), dao.total()) { ids, _ -> ids }
            .flatMapLatest { ids -> flow { emit(if (ids.isEmpty()) emptyMap() else dao.byIds(ids).associateBy { it.id }) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Hero rotation for the idle companion screen: well-rated, unplayed-ish games with a screenshot. */
    fun refreshHeroes() = viewModelScope.launch { Selection.heroes.value = dao.heroes().shuffled().take(60) }

    val shizuku = graph.screenMover.status
    val moveResult = graph.screenMover.lastResult

    fun shizukuRefresh() = graph.screenMover.refresh()

    fun shizukuRequest() = graph.screenMover.requestPermission()

    fun moveTopApp(from: Int, to: Int) = viewModelScope.launch { graph.screenMover.moveTopApp(from, to) }

    val biosMissing = graph.deviceState.biosMissing
    val saveStems = graph.deviceState.saveStems

    val systems: StateFlow<List<SystemRow>> =
        combine(manifests, dao.countsBySystem()) { snap, counts ->
            val byId = counts.associate { it.systemId to it.n }
            snap.systems.map { SystemRow(it, byId[it.id] ?: 0) }.filter { it.count > 0 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playStats: StateFlow<Map<String, PlayStat>> =
        dao.playStats().map { l -> l.associateBy { it.gameId } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val folders: StateFlow<List<FolderView>> = graph.folders.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun folder(id: String): Flow<FolderView?> = folders.map { l -> l.firstOrNull { it.def.id == id } }

    fun hide(id: String, hidden: Boolean) = graph.user.hide(id, hidden)

    fun variantsOf(id: String): Flow<List<GameEntity>> = flow { emit(dao.variantsOf(id)) }

    val tileCovers: StateFlow<Map<String, String>> =
        dao.tileCovers().map { l -> l.associate { it.systemId to it.cover } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val total: StateFlow<Int> = dao.total().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun gamesOf(systemId: String): Flow<List<GameEntity>> = dao.bySystem(systemId)

    fun game(id: String): Flow<GameEntity?> = dao.observe(id)

    /** Favourited games, apps and shortcuts in one list, sorted by label. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val favourites: Flow<List<LibraryItem>> =
        combine(user.map { it.favourites }, dao.total(), apps, shortcuts) { favs, _, a, s -> Triple(favs, a, s) }.flatMapLatest { (favs, a, s) ->
            flow {
                val games = dao.byIds(favs.filter { LibraryItem.isGameId(it) }).map { LibraryItem.Game(it) }
                val others = a.filter { it.id in favs }.map { LibraryItem.App(it) } + s.filter { it.id in favs }.map { LibraryItem.Shortcut(it) }
                emit((games + others).sortedBy { it.sortKey })
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recent: Flow<List<Pair<GameEntity, PlayStat>>> =
        combine(dao.recentlyPlayed(30), dao.total()) { stats, _ -> stats }.flatMapLatest { stats ->
            flow {
                val games = dao.byIds(stats.map { it.gameId }).associateBy { it.id }
                emit(stats.mapNotNull { s -> games[s.gameId]?.let { it to s } })
            }
        }

    fun search(q: String): Flow<List<GameEntity>> = dao.search(likeEscape(q))

    fun profilesFor(game: GameEntity): List<Pair<LaunchProfile, Boolean>> = graph.launcher.profilesFor(game).map { it to graph.launcher.isInstalled(it) }

    fun launch(game: GameEntity, only: LaunchProfile? = null) = viewModelScope.launch { graph.launcher.launch(game, only) }

    /** `adb shell am start -n <pkg>/.MainActivity --es launch "<folder>/<file>" [--es profile <id>]` for reproducible tests. */
    fun launchById(id: String, profileId: String?) = viewModelScope.launch {
        val game = dao.byId(id) ?: run {
            android.util.Log.w("SortieLaunch", "no such game: $id")
            return@launch
        }
        val only = profileId?.let { manifests.value.profiles[it] }
        graph.launcher.launch(game, only)
    }

    /** Returns whether [id] is a favourite after the toggle. */
    fun toggleFavourite(id: String): Boolean {
        val now = id !in user.value.favourites
        graph.user.toggleFavourite(id)
        return now
    }

    fun setOverride(id: String, profileId: String?) = graph.user.setOverride(id, profileId)

    fun reindex(force: Boolean = false) {
        if (_index.value.running) return
        _index.update { it.copy(running = true) }
        viewModelScope.launch {
            val r = graph.indexer.run(force)
            graph.deviceState.refresh()
            _index.update { IndexState(running = false, report = r) }
        }
    }

    fun reloadManifests() = viewModelScope.launch { graph.manifests.reloadNow() }

    /** Back from an emulator: close the open play session. Save and BIOS state stay as scanned until the next re-index. */
    /**
     * Back in the foreground. The open play session ends only if its app is gone: Home summons the switcher by resuming this
     * HOME activity while the game keeps running underneath (and is raised again), which must not stop the timer or replace
     * Now playing. The app's package comes from the session's profile (games) or its id (apps); without Shizuku, as before,
     * resuming ends the session.
     */
    fun onForeground() = viewModelScope.launch {
        val session = graph.playLog.current.value
        if (session != null) {
            val pkg = when {
                session.gameId.startsWith(LibraryItem.APP_PREFIX) -> session.gameId.removePrefix(LibraryItem.APP_PREFIX)
                session.gameId.startsWith(LibraryItem.SHORTCUT_PREFIX) -> session.gameId.removePrefix(LibraryItem.SHORTCUT_PREFIX).substringBefore('/')
                else -> session.profileId?.let { manifests.value.profiles[it]?.`package` }
            }
            val stillRunning = pkg != null && graph.screenMover.ready && graph.screenMover.appTasks().any { it.pkg == pkg }
            if (!stillRunning) graph.playLog.end()
        }
        graph.screenMover.sweepStaleLaunchers()
    }
}
