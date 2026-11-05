package io.github.esko997.sortie.data.folders

import android.util.Log
import androidx.room.withTransaction
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.FolderPreferences
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A folder evaluated against the current library, installed apps, launcher shortcuts, play data and favourites. [items] are its
 * games, apps and shortcuts, in [displaySort] order; [children] the `folder:<id>` and `system:<id>` pointers it shows ahead of
 * them, in include order, and [parents] the folders that show it, with [level] its depth (1 on Home's top level); see
 * [nestFolders].
 * [inApp] marks a folder made on the device (user.json `folders`); [editable] ones are what Sortie renames, deletes or files
 * things into. [displaySort] is the order shown: the user's Sort by… choice for it ([UserData.folderViews]), else [def]'s own.
 */
data class FolderView(
    val def: FolderDef,
    val items: List<LibraryItem>,
    val problems: List<String>,
    val inApp: Boolean = false,
    val children: List<String> = emptyList(),
    val parents: List<String> = emptyList(),
    val level: Int = 1,
    val displaySort: String = def.sort
) {
    /** Every folder but the built-in ones: a `folders.json` folder moves into user.json on its first edit (see SortieViewModel). */
    val editable: Boolean get() = inApp || def.id !in BUILT_IN_FOLDER_IDS

    /**
     * The tile's art: the chosen [FolderDef.cover] game's cover while it is a member, else the first game's in folder order with
     * one. Hidden shows none: a hidden game's art on Home would undo hiding it.
     */
    val coverArt: String?
        get() {
            if (def.id == HIDDEN_FOLDER_ID) return null
            val games = items.filterIsInstance<LibraryItem.Game>()
            return games.firstOrNull { it.id == def.cover }?.game?.cover ?: games.firstNotNullOfOrNull { it.game.cover }
        }
}

/**
 * Evaluates every folder in `folders.json` and user.json's in-app folders, plus the built-in Favourites, Want to play, Finished,
 * Shelved, Recently added and Hidden folders, and nests them ([nestFolders]) whenever the library, apps, shortcuts, play stats,
 * favourites, the queue, the shelves, the hidden ids, the in-app folders or the manifests change.
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
            val items = folderCandidates(games, appList, shortcutList, u)
            val byId = items.associateBy { it.id }
            val subjects = items.map { subjectOf(it, stats[it.id], it.id in u.favourites, now) }
            val views = evaluateFolders(snap.folders, u.wantToPlay, u.gameProgress, subjects, byId, u.folders, u.hidden, u.folderViews)
            nestFolders(views, snap.systems.mapTo(HashSet()) { it.id })
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

    companion object {
        private const val TAG = "Folders"
    }
}

/**
 * Every launchable a folder may hold: indexed games, installed apps and only the shortcuts the user chose — opted in to Apps,
 * favourited, placed on home or listed in an in-app folder (not every app's manifest action). Hidden ones are left in for the
 * Hidden folder; [evaluateFolders] keeps them out of every other folder.
 */
internal fun folderCandidates(games: List<GameEntity>, apps: List<AppEntry>, shortcuts: List<ShortcutEntry>, user: UserData): List<LibraryItem> {
    val filed = user.folders.flatMapTo(HashSet()) { it.include }
    return buildList<LibraryItem> {
        games.mapTo(this) { LibraryItem.Game(it) }
        apps.mapTo(this) { LibraryItem.App(it) }
        shortcuts.filter { it.id in user.shortcuts || it.id in user.favourites || it.id in user.layout || it.id in filed }
            .mapTo(this) { LibraryItem.Shortcut(it) }
    }
}

internal fun subjectOf(item: LibraryItem, stat: PlayStat?, favourite: Boolean, now: Long): Subject {
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

private const val FAVOURITES_LABEL = "Favourites"
private const val WANT_TO_PLAY_LABEL = "Want to play"
private const val RECENTLY_ADDED_LABEL = "Recently added"
private const val HIDDEN_LABEL = "Hidden"

/** Recently added's rule: games indexed (first seen) less than 14 days ago. */
private val RECENTLY_ADDED_RULE = JsonObject(mapOf("daysSinceAdded" to JsonObject(mapOf("lt" to JsonPrimitive(14)))))

/** A built-in folder's definition; [gamesOnly] drops apps and shortcuts from its members (the personal game shelves). */
private class BuiltInFolder(val def: FolderDef, val gamesOnly: Boolean)

/**
 * The manifest folders, then the in-app folders ([inApp], user.json), then the built-in folders: Favourites (every favourited
 * game, app and shortcut in [subjects]), Want to play ([wantToPlay]), Finished and Shelved (games whose [progress] names that
 * shelf; these three list games only), Recently added (games added in the last 14 days, newest first) and Hidden (the
 * [hidden] games, apps and shortcuts). [subjects] are already limited to indexed games, installed apps and chosen shortcuts;
 * the hidden ones are members of Hidden only. Each built-in lists its members in title order unless said otherwise, through
 * the same [RuleEngine] include/rule path as any folder. They are
 * always present — empty when nothing resolves — so their home tiles keep working after the last removal; unresolved ids are
 * skipped here and stay in user.json. So that `folder:<id>` names one folder only, a `folders.json` entry claiming a reserved
 * id is dropped (reported on that built-in folder); an in-app folder with a reserved id is dropped, one sharing a
 * `folders.json` entry's id wins over that entry, and of in-app folders sharing an id the first is kept.
 * A folder's Sort by… choice in [folderViews] only reorders the members its own definition chose (sort, limit and all); an
 * unknown choice is ignored.
 */
internal fun evaluateFolders(
    defs: List<FolderDef>,
    wantToPlay: Set<String>,
    progress: Map<String, GameProgress>,
    subjects: List<Subject>,
    byId: Map<String, LibraryItem>,
    inApp: List<FolderDef> = emptyList(),
    hidden: Set<String> = emptySet(),
    folderViews: Map<String, FolderPreferences> = emptyMap()
): List<FolderView> {
    fun view(def: FolderDef, members: List<Subject>, problems: List<String>, isInApp: Boolean = false): FolderView {
        val sort = folderSort(folderViews[def.id]?.sort)
        val ordered = if (sort == null || sort == def.sort) members else members.sortedWith(RuleEngine.comparator(sort))
        return FolderView(def, ordered.mapNotNull { byId[it.id] }, problems, inApp = isInApp, displaySort = sort ?: def.sort)
    }
    val shown = subjects.filter { it.id !in hidden }
    val favourites = shown.filter { it.favourite }.map { it.id }
    val builtIn = listOf(
        BuiltInFolder(FolderDef(FAVOURITES_FOLDER_ID, FAVOURITES_LABEL, include = favourites, sort = "title:asc"), gamesOnly = false),
        BuiltInFolder(FolderDef(WANT_TO_PLAY_FOLDER_ID, WANT_TO_PLAY_LABEL, include = wantToPlay.toList(), sort = "title:asc"), gamesOnly = true)
    ) + Shelf.entries.map {
        BuiltInFolder(FolderDef(it.folderId, it.label, include = progress.idsOn(it).toList(), sort = "title:asc"), gamesOnly = true)
    } + listOf(
        BuiltInFolder(FolderDef(RECENTLY_ADDED_FOLDER_ID, RECENTLY_ADDED_LABEL, rule = RECENTLY_ADDED_RULE, sort = "added:desc,title:asc"), gamesOnly = true),
        BuiltInFolder(FolderDef(HIDDEN_FOLDER_ID, HIDDEN_LABEL, include = hidden.toList(), sort = "title:asc"), gamesOnly = false)
    )
    val (reserved, manifest) = defs.partition { it.id in BUILT_IN_FOLDER_IDS }
    val own = inApp.filter { it.id !in BUILT_IN_FOLDER_IDS }.distinctBy { it.id }
    val ownIds = own.mapTo(HashSet()) { it.id }
    val views = manifest.filter { it.id !in ownIds }.map { it to false } + own.map { it to true }
    return views.map { (def, isInApp) ->
        val result = RuleEngine.evaluate(def, shown)
        view(def, result.subjects, result.problems, isInApp)
    } + builtIn.map { folder ->
        val def = folder.def
        val pool = if (def.id == HIDDEN_FOLDER_ID) subjects else shown
        val members = RuleEngine.evaluate(def, pool).subjects.filter { !folder.gamesOnly || it.kind == Subject.Kind.GAME }
        val problems = if (reserved.none { it.id == def.id }) {
            emptyList()
        } else {
            listOf("folders.json defines \"${def.id}\", which is reserved for this built-in folder; that entry is ignored")
        }
        view(def, members, problems)
    }
}
