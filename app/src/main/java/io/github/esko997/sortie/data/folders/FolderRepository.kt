package io.github.esko997.sortie.data.folders

import android.util.Log
import androidx.room.withTransaction
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutEntry
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

/** A smart folder evaluated against the current library, installed apps, launcher shortcuts, play data and favourites. */
data class FolderView(val def: FolderDef, val items: List<LibraryItem>, val problems: List<String>)

/**
 * Evaluates every folder in `folders.json` whenever the library, apps, shortcuts, play stats, favourites or the manifests change.
 * The full-table read runs inside a transaction so the indexer cannot change rows under the cursor (that race crashed the
 * process once with "Couldn't read row N from CursorWindow"), inputs are debounced while an index pass is writing, and any
 * failure degrades to the previous result instead of propagating.
 */
class FolderRepository(
    private val db: SortieDb,
    private val manifests: ManifestStore,
    user: StateFlow<UserData>,
    apps: StateFlow<List<AppEntry>>,
    shortcuts: StateFlow<List<ShortcutEntry>>
) {
    private val dao = db.games()

    @OptIn(FlowPreview::class)
    private val inputs: Flow<Triple<List<GameEntity>, Map<String, PlayStat>, UserData>> =
        combine(dao.total(), dao.playStats(), user) { _, stats, u -> Pair(stats.associateBy { it.gameId }, u) }
            .debounce(400)
            .map { (stats, u) -> Triple(readAll(), stats, u) }

    val folders: Flow<List<FolderView>> =
        combine(inputs, manifests.snapshot, apps, shortcuts) { (games, stats, u), snap, appList, shortcutList ->
            val now = System.currentTimeMillis()
            val items = buildList<LibraryItem> {
                games.mapTo(this) { LibraryItem.Game(it) }
                appList.mapTo(this) { LibraryItem.App(it) }
                shortcutList.mapTo(this) { LibraryItem.Shortcut(it) }
            }.filter { it.id !in u.hidden }
            val byId = items.associateBy { it.id }
            val subjects = items.map { subjectOf(it, stats[it.id], it.id in u.favourites, now) }
            snap.folders.map { def ->
                val result = RuleEngine.evaluate(def, subjects)
                FolderView(def, result.subjects.mapNotNull { byId[it.id] }, result.problems)
            }
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

    private fun subjectOf(item: LibraryItem, stat: PlayStat?, favourite: Boolean, now: Long): Subject {
        val minutes = stat?.minutes ?: 0
        val last = stat?.lastPlayed
        return when (item) {
            is LibraryItem.Game -> Subject(item.game, minutes, last, favourite, now)
            is LibraryItem.App -> Subject(
                item.id,
                Subject.Kind.APP,
                item.label,
                pkg = item.app.pkg,
                playMinutes = minutes,
                lastPlayed = last,
                favourite = favourite,
                now = now
            )
            is LibraryItem.Shortcut -> Subject(
                item.id,
                Subject.Kind.SHORTCUT,
                item.label,
                pkg = item.shortcut.pkg,
                source = item.shortcut.pkg,
                playMinutes = minutes,
                lastPlayed = last,
                favourite = favourite,
                now = now
            )
        }
    }

    companion object {
        private const val TAG = "Folders"
    }
}
