package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A smart folder evaluated against the current library, play data and favourites. */
data class FolderView(val def: FolderDef, val games: List<GameEntity>, val problems: List<String>)

/**
 * Evaluates every folder in `folders.json` whenever the library, play stats, favourites or the manifests change.
 * Evaluation is in memory over the playable rows (10k rows evaluate in tens of milliseconds); SQL pre-filtering can come later.
 */
class FolderRepository(private val dao: GameDao, private val manifests: ManifestStore, user: StateFlow<UserData>) {
    private val inputs: Flow<Triple<List<GameEntity>, Map<String, PlayStat>, UserData>> =
        combine(dao.total(), dao.playStats(), user) { _, stats, u -> Pair(stats.associateBy { it.gameId }, u) }
            .map { (stats, u) -> Triple(dao.allPlayable(), stats, u) }

    val folders: Flow<List<FolderView>> =
        combine(inputs, manifests.snapshot) { (games, stats, u), snap ->
            val now = System.currentTimeMillis()
            val subjects = games.filter { it.id !in u.hidden }.map { g ->
                Subject(
                    g,
                    stats[g.id]?.minutes ?: 0,
                    stats[g.id]?.lastPlayed,
                    g.id in u.favourites,
                    now
                )
            }
            snap.folders.map { def -> evaluate(def, subjects) }
        }.flowOn(Dispatchers.Default)

    private fun evaluate(def: FolderDef, subjects: List<Subject>): FolderView {
        val engine = RuleEngine(def.rule)
        val include = def.include.toSet()
        val exclude = def.exclude.toSet()
        val matched = subjects.filter { s -> s.game.id !in exclude && (s.game.id in include || (def.rule != null && engine.matches(s))) }
        val sorted = matched.sortedWith(RuleEngine.comparator(def.sort)).let { if (def.limit > 0) it.take(def.limit) else it }
        return FolderView(def, sorted.map { it.game }, engine.problems)
    }
}
