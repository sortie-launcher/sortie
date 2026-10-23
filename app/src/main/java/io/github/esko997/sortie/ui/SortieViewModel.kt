package io.github.esko997.sortie.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.db.byIds
import io.github.esko997.sortie.data.db.likeEscape
import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.SystemDef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

    /** Activity start: re-check the all-files permission, recover manifests after a grant, and refresh the index. */
    fun onStart(granted: Boolean) {
        _storageGranted.value = granted
        if (!granted) return
        if (manifests.value.errors.isNotEmpty()) graph.manifests.reload()
        reindex()
    }

    val systems: StateFlow<List<SystemRow>> =
        combine(manifests, dao.countsBySystem()) { snap, counts ->
            val byId = counts.associate { it.systemId to it.n }
            snap.systems.map { SystemRow(it, byId[it.id] ?: 0) }.filter { it.count > 0 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playStats: StateFlow<Map<String, PlayStat>> =
        dao.playStats().map { l -> l.associateBy { it.gameId } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val total: StateFlow<Int> = dao.total().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun gamesOf(systemId: String): Flow<List<GameEntity>> = dao.bySystem(systemId)

    fun game(id: String): Flow<GameEntity?> = dao.observe(id)

    @OptIn(ExperimentalCoroutinesApi::class)
    val favourites: Flow<List<GameEntity>> =
        combine(user.map { it.favourites }, dao.total()) { favs, _ -> favs }.flatMapLatest { favs -> flow { emit(dao.byIds(favs).sortedBy { it.sortTitle }) } }

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

    fun toggleFavourite(id: String) = graph.user.toggleFavourite(id)

    fun setOverride(id: String, profileId: String?) = graph.user.setOverride(id, profileId)

    fun reindex(force: Boolean = false) {
        if (_index.value.running) return
        _index.update { it.copy(running = true) }
        viewModelScope.launch {
            val r = graph.indexer.run(force)
            _index.update { IndexState(running = false, report = r) }
        }
    }

    fun reloadManifests() = viewModelScope.launch { graph.manifests.reloadNow() }

    fun onForeground() = viewModelScope.launch { graph.playLog.end() }
}
