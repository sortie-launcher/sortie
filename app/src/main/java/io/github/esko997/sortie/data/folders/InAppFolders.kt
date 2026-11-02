package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.FOLDER_POINTER
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.SYSTEM_POINTER
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

/** Adds an empty in-app folder [id] named [label] to [into] (null: Home), just before [beside] when it is there. */
fun UserData.withNewFolder(id: String, label: String, into: String?, beside: String?, home: List<String>): UserData {
    val tile = FOLDER_POINTER + id
    val placed = if (into == null) {
        copy(layout = home.placed(tile, before = beside))
    } else {
        editFolder(into) { it.copy(include = it.include.placed(tile, before = beside)) }
    }
    return placed.copy(folders = placed.folders + FolderDef(id, folderLabel(label, id)))
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
 * Deletes in-app folder [id]. Its folder and system pointers take its place where it was shown: in each in-app folder among
 * [parents] (its resolved [FolderView.parents]), else on Home (where its tile was, or at the end), so nothing it held becomes
 * unreachable; any other pointer to it (a cycle that was not followed) just goes. The games, apps and shortcuts it listed stay
 * in the library; only this folder's list of them goes.
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
    return copy(folders = rest, layout = if (held) layout - tile else (home - pointers.toSet()).replaced(tile, pointers))
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
