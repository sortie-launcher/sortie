package io.github.esko997.sortie.data.folders

import android.util.Log
import androidx.room.withTransaction
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** A smart folder evaluated against the current library, play data and favourites. */
data class FolderView(val def: FolderDef, val games: List<GameEntity>, val problems: List<String>)

/**
 * Evaluates every folder in `folders.json` whenever the library, play stats, favourites or the manifests change.
 * The full-table read runs inside a transaction so the indexer cannot change rows under the cursor (that race crashed the
 * process once with "Couldn't read row N from CursorWindow"), inputs are debounced while an index pass is writing, and any
 * failure degrades to the previous result instead of propagating.
 */
class FolderRepository(private val db: SortieDb, private val manifests: ManifestStore, user: StateFlow<UserData>) {
    private val dao = db.games()

    @OptIn(FlowPreview::class)
    private val inputs: Flow<Triple<List<GameEntity>, Map<String, PlayStat>, UserData>> =
        combine(dao.total(), dao.playStats(), user) { _, stats, u -> Pair(stats.associateBy { it.gameId }, u) }
            .debounce(400)
            .map { (stats, u) -> Triple(readAll(), stats, u) }

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
        }.catch { e ->
            Log.w(TAG, "folder evaluation failed", e)
            emit(emptyList())
        }
            .flowOn(Dispatchers.Default)

    private suspend fun readAll(): List<GameEntity> {
        repeat(3) { attempt ->
            try {
                return db.withTransaction { dao.allPlayable() }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "readAll attempt $attempt", e)
            }
        }
        return emptyList()
    }

    private fun evaluate(def: FolderDef, subjects: List<Subject>): FolderView {
        val engine = RuleEngine(def.rule)
        val include = def.include.toSet()
        val exclude = def.exclude.toSet()
        val matched = subjects.filter { s -> s.game.id !in exclude && (s.game.id in include || (def.rule != null && engine.matches(s))) }
        val sorted = matched.sortedWith(RuleEngine.comparator(def.sort)).let { if (def.limit > 0) it.take(def.limit) else it }
        return FolderView(def, sorted.map { it.game }, engine.problems)
    }

    companion object {
        private const val TAG = "Folders"
    }
}
