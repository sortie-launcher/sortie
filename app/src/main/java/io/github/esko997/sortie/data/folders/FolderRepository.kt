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
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.data.user.GameProgress
import io.github.esko997.sortie.data.user.Shelf
import io.github.esko997.sortie.data.user.idsOn
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
 * Evaluates every folder in `folders.json`, plus the built-in Want to play, Finished and Shelved folders, whenever the library,
 * apps, shortcuts, play stats, favourites, the queue, the shelves or the manifests change.
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
                // only shortcuts the user chose: opted in to Apps, favourited or placed on home (not every app's manifest action)
                shortcutList.filter { it.id in u.shortcuts || it.id in u.favourites || it.id in u.layout }.mapTo(this) { LibraryItem.Shortcut(it) }
            }.filter { it.id !in u.hidden }
            val byId = items.associateBy { it.id }
            val subjects = items.map { subjectOf(it, stats[it.id], it.id in u.favourites, now) }
            evaluateFolders(snap.folders, u.wantToPlay, u.gameProgress, subjects, byId)
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

private const val WANT_TO_PLAY_LABEL = "Want to play"

/**
 * The manifest folders, then the built-in folders: Want to play ([wantToPlay]), Finished and Shelved (games whose [progress]
 * names that shelf). Each lists its games found in [subjects] (already limited to indexed, unhidden entries), in title order,
 * through the same [RuleEngine] include path as any folder. They are always present — empty when nothing resolves — so their
 * home tiles keep working after the last removal; unresolved ids are skipped here and stay in user.json. A `folders.json`
 * entry claiming a reserved id is dropped (reported on that built-in folder) so `folder:<id>` names one folder only.
 */
internal fun evaluateFolders(
    defs: List<FolderDef>,
    wantToPlay: Set<String>,
    progress: Map<String, GameProgress>,
    subjects: List<Subject>,
    byId: Map<String, LibraryItem>
): List<FolderView> {
    val builtIn = listOf(FolderDef(WANT_TO_PLAY_FOLDER_ID, WANT_TO_PLAY_LABEL, include = wantToPlay.toList(), sort = "title:asc")) +
        Shelf.entries.map { FolderDef(it.folderId, it.label, include = progress.idsOn(it).toList(), sort = "title:asc") }
    val reservedIds = builtIn.mapTo(HashSet()) { it.id }
    val (reserved, manifest) = defs.partition { it.id in reservedIds }
    val views = manifest.map { def ->
        val result = RuleEngine.evaluate(def, subjects)
        FolderView(def, result.subjects.mapNotNull { byId[it.id] }, result.problems)
    }
    return views + builtIn.map { def ->
        val games = RuleEngine.evaluate(def, subjects).subjects.filter { it.kind == Subject.Kind.GAME }
        val problems = if (reserved.none { it.id == def.id }) {
            emptyList()
        } else {
            listOf("folders.json defines \"${def.id}\", which is reserved for this built-in folder; that entry is ignored")
        }
        FolderView(def, games.mapNotNull { byId[it.id] }, problems)
    }
}
