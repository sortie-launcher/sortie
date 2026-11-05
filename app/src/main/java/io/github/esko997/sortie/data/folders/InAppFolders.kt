package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.FOLDER_POINTER
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.SYSTEM_POINTER
import io.github.esko997.sortie.data.manifest.FolderPreferences
import io.github.esko997.sortie.data.manifest.UserData

/** Longest in-app folder name, in characters. */
const val FOLDER_LABEL_MAX = 60

private val NON_SLUG = Regex("[^a-z0-9]+")

/**
 * The id of a new in-app folder named [label]: its slug (lower-case ASCII letters and digits joined by hyphens, at most 40
 * characters; "folder" when nothing is left), with "-2", "-3"… appended while [taken] — every built-in, `folders.json` and
 * in-app id — has it. The same name and the same ids always give the same id.
 */
fun newFolderId(label: String, taken: Set<String>): String {
    val slug = label.lowercase().replace(NON_SLUG, "-").trim('-').take(40).trimEnd('-').ifEmpty { "folder" }
    if (slug !in taken) return slug
    return generateSequence(2) { it + 1 }.map { "$slug-$it" }.first { it !in taken }
}

/*
 * Edits of the in-app folders (UserData.folders) and of Home's custom layout, as pure functions UserStore applies. A tile id is
 * anything a folder include or the layout holds: a game, app or shortcut id, or a folder:/system: pointer. A container is an
 * in-app folder id, or null for Home; [home] is the custom home's current order (HomeIds.custom, nested tiles included),
 * saved as the layout whenever Home changes, as a Move on Home does, so automatic tiles keep their places.
 */

/**
 * Adds an empty in-app folder [id] named [label] to [into] (null: Home), just before [beside] when it is there. It starts with
 * the default view: display choices left under its id by a folder no longer here are not inherited.
 */
fun UserData.withNewFolder(id: String, label: String, into: String?, beside: String?, home: List<String>): UserData {
    val tile = FOLDER_POINTER + id
    val placed = if (into == null) {
        copy(layout = home.placed(tile, before = beside))
    } else {
        editFolder(into) { it.copy(include = it.include.placed(tile, before = beside)) }
    }
    return placed.copy(folders = placed.folders + FolderDef(id, folderLabel(label, id)), folderViews = placed.folderViews - id)
}

/**
 * Takes [tileId] out of [from] and puts it in [to] (each an in-app folder, or null for Home): just after [from]'s own tile when
 * [to] holds it (moving up to the parent or Home), else at the end.
 */
fun UserData.withMoved(tileId: String, from: String?, to: String?, home: List<String>): UserData {
    val out = if (from == null) copy(layout = home - tileId) else editFolder(from) { it.copy(include = it.include - tileId) }
    val anchor = from?.let { FOLDER_POINTER + it }
    return if (to == null) {
        out.copy(layout = home.placed(tileId, after = anchor))
    } else {
        out.editFolder(to) { it.copy(include = it.include.placed(tileId, after = anchor), exclude = it.exclude - tileId) }
    }
}

/** Appends [tileId] to in-app folder [to] (once; an `exclude` entry for it goes), leaving it wherever else it is. */
fun UserData.withAddedToFolder(tileId: String, to: String): UserData =
    editFolder(to) { it.copy(include = if (tileId in it.include) it.include else it.include + tileId, exclude = it.exclude - tileId) }

/** Takes [tileId] out of in-app folder [from]'s include; the game, app or shortcut stays in the library. */
fun UserData.withRemovedFromFolder(tileId: String, from: String): UserData = editFolder(from) { it.copy(include = it.include - tileId) }

fun UserData.withFolderRenamed(id: String, label: String): UserData = editFolder(id) { it.copy(label = folderLabel(label, id)) }

/**
 * Sort by… choices for any folder: what the chooser shows, and the order it saves in [UserData.folderViews]
 * ([RuleEngine.comparator]). The folder's own [FolderDef.sort] stays its default.
 */
val FOLDER_SORTS = listOf("Title" to "title:asc", "Recently played" to "lastPlayed:desc", "Recently added" to "added:desc", "Play time" to "playMinutes:desc")

/** What the chooser calls [sort]'s order: the Sort by… choice its first key matches, else "Folder default". */
fun folderSortLabel(sort: String): String {
    val first = sort.substringBefore(',').split(':').map { it.trim() }
    val key = "${first[0]}:${first.getOrNull(1)?.ifEmpty { null } ?: "asc"}"
    return FOLDER_SORTS.firstOrNull { it.second == key }?.first ?: "Folder default"
}

/** [sort] when it is a Sort by… choice, else null: an unknown or old value never reorders a folder. */
internal fun folderSort(sort: String?): String? = sort?.takeIf { s -> FOLDER_SORTS.any { it.second == s } }

/** [layout] when a folder may keep it apart from the browse layout ("list" or "grid"), else null. */
private fun folderLayoutOf(layout: String?): String? = layout?.takeIf { it == "list" || it == "grid" }

/**
 * Folder [id] (any folder, built-in ones included) shows its members in [sort] order, a [FOLDER_SORTS] value; null or any
 * other value goes back to the folder's own order. Only the view changes: the definition, and so its members, stay.
 */
fun UserData.withFolderSort(id: String, sort: String?): UserData = withFolderView(id) { it.copy(sort = folderSort(sort)) }

/** Folder [id] shows as [layout] ("list" or "grid"); null or any other value goes back to the browse layout. */
fun UserData.withFolderLayout(id: String, layout: String?): UserData = withFolderView(id) { it.copy(layout = folderLayoutOf(layout)) }

/** The layout folder [id] shows: its own choice, else [UserData.browseLayout]. */
fun UserData.folderLayout(id: String): String = folderLayoutOf(folderViews[id]?.layout) ?: browseLayout

/** Backup restore: blank ids and values this build does not know dropped, and with them entries left choosing nothing. */
fun Map<String, FolderPreferences>.cleaned(): Map<String, FolderPreferences> = filterKeys { it.isNotBlank() }
    .mapValues { (_, p) -> FolderPreferences(folderSort(p.sort), folderLayoutOf(p.layout)) }
    .filterValues { !it.isEmpty() }

private fun UserData.withFolderView(id: String, f: (FolderPreferences) -> FolderPreferences): UserData {
    val next = f(folderViews[id] ?: FolderPreferences())
    return copy(folderViews = if (next.isEmpty()) folderViews - id else folderViews + (id to next))
}

/** Sets (null: clears) the game whose cover folder [id]'s tile shows ([FolderView.coverArt]). */
fun UserData.withFolderCover(id: String, game: String?): UserData = editFolder(id) { it.copy(cover = game) }

/**
 * The first edit of `folders.json` folders [defs]: each becomes an in-app folder with the same id and every field, unless an
 * in-app folder has its id already. The caller then removes them from folders.json, or undoes this ([withoutAdopted]) when it
 * cannot.
 */
fun UserData.withAdopted(defs: List<FolderDef>): UserData = copy(folders = folders + defs.filter { d -> folders.none { it.id == d.id } })

/** Undoes [withAdopted]: each of [defs] still unchanged among the in-app folders goes again. */
fun UserData.withoutAdopted(defs: List<FolderDef>): UserData = copy(folders = folders - defs.toSet())

/**
 * Deletes in-app folder [id]. Its folder and system pointers take its place where it was shown: in each in-app folder among
 * [parents] (its resolved [FolderView.parents]), else on Home (where its tile was, or at the end), so nothing it held becomes
 * unreachable; any other pointer to it (a cycle that was not followed) just goes. The games, apps and shortcuts it listed stay
 * in the library; only this folder's list of them, and its display choices, go.
 */
fun UserData.withoutFolder(id: String, parents: Collection<String>, home: List<String>): UserData {
    val def = folders.firstOrNull { it.id == id } ?: return this
    val tile = FOLDER_POINTER + id
    val pointers = def.include.filter { (it.startsWith(FOLDER_POINTER) || it.startsWith(SYSTEM_POINTER)) && it != tile }
    val held = folders.any { it.id != id && it.id in parents && tile in it.include }
    val rest = folders.filter { it.id != id }.map { f ->
        when {
            tile !in f.include -> f
            f.id in parents -> f.copy(include = f.include.replaced(tile, pointers - (FOLDER_POINTER + f.id)))
            else -> f.copy(include = f.include - tile)
        }
    }
    // Home's order holds the pointers already (nested tiles keep a saved place): they move into the folder's slot
    return copy(
        folders = rest,
        layout = if (held) layout - tile else (home - pointers.toSet()).replaced(tile, pointers),
        folderViews = folderViews - id
    )
}

/**
 * The editable folders inside [id] that no other folder shows, deepest first: deleting [id] with its folders deletes these too
 * (each through [withoutFolder], so whatever they held moves up a level and only folders go). A subfolder another folder also
 * shows stays, reachable there, and so does everything below it; built-in folders are never deleted.
 */
fun List<FolderView>.ownedSubfolders(id: String): List<String> {
    val byId = associateBy { it.def.id }
    val order = ArrayList<String>()
    fun walk(f: String) {
        for (c in byId[f]?.children.orEmpty()) {
            val child = c.removePrefix(FOLDER_POINTER)
            if (c.startsWith(FOLDER_POINTER) && byId[child]?.editable == true && child != id && child !in order) {
                walk(child)
                order += child
            }
        }
    }
    walk(id)
    // owned: every parent goes too; repeated, since keeping one keeps what only it shows
    var owned = order.toSet()
    while (true) {
        val next = owned.filterTo(HashSet()) { f -> byId.getValue(f).parents.all { it == id || it in owned } }
        if (next.size == owned.size) return order.filter { it in owned }
        owned = next
    }
}

/**
 * Deletes in-app folder [id] as shown in [views] and, with [withFolders], its [ownedSubfolders] first, deepest first; each goes
 * through [withoutFolder], so the systems and other folders they held end up where [id] was. Only folders go.
 */
fun UserData.withoutFolder(id: String, views: List<FolderView>, home: List<String>, withFolders: Boolean): UserData {
    val subfolders = if (withFolders) views.ownedSubfolders(id) else emptyList()
    val order = home - subfolders.map { FOLDER_POINTER + it }.toSet()
    return (subfolders + id).fold(this) { u, f -> u.withoutFolder(f, views.firstOrNull { it.def.id == f }?.parents.orEmpty(), order) }
}

/** Backup restore: blank and repeated ids dropped (the first kept), names trimmed and bounded, include/exclude without repeats. */
fun List<FolderDef>.cleaned(): List<FolderDef> = filter { it.id.isNotBlank() }.distinctBy { it.id }.map {
    it.copy(label = folderLabel(it.label, it.id), include = it.include.distinct(), exclude = it.exclude.distinct(), limit = it.limit.coerceAtLeast(0))
}

private fun folderLabel(label: String, id: String) = label.trim().take(FOLDER_LABEL_MAX).ifEmpty { id }

private fun UserData.editFolder(id: String, f: (FolderDef) -> FolderDef): UserData = copy(folders = folders.map { if (it.id == id) f(it) else it })

/** [id] moved (or added) just before [before] or just after [after] when that is present, else at the end. */
private fun List<String>.placed(id: String, before: String? = null, after: String? = null): List<String> {
    val rest = this - id
    val i = before?.let { rest.indexOf(it) }?.takeIf { it >= 0 } ?: after?.let { rest.indexOf(it) }?.takeIf { it >= 0 }?.plus(1) ?: rest.size
    return rest.subList(0, i) + id + rest.subList(i, rest.size)
}

/** [ids] (those not already present) in [tile]'s place, or at the end when it is absent. */
private fun List<String>.replaced(tile: String, ids: List<String>): List<String> {
    val add = ids.filter { it !in this }.distinct()
    val i = indexOf(tile)
    return if (i < 0) this + add else subList(0, i) + add + subList(i + 1, size)
}
