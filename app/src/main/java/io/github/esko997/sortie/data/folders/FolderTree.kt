package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.manifest.FAVOURITES_FOLDER_ID
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.FOLDER_POINTER
import io.github.esko997.sortie.data.manifest.FolderDef.Companion.SYSTEM_POINTER
import io.github.esko997.sortie.data.manifest.WANT_TO_PLAY_FOLDER_ID
import io.github.esko997.sortie.data.user.Shelf

/** Folders nest at most this deep: a folder on Home's top level is level 1. Systems may sit in a folder at any level. */
const val MAX_FOLDER_DEPTH = 4

/** Ids of the built-in folders (Favourites, Want to play, Finished, Shelved); no other folder may use them. */
val BUILT_IN_FOLDER_IDS: Set<String> = setOf(FAVOURITES_FOLDER_ID, WANT_TO_PLAY_FOLDER_ID) + Shelf.entries.map { it.folderId }

/**
 * Fills in each view's [FolderView.children], [FolderView.parents] and [FolderView.level] from the `folder:<id>` and
 * `system:<id>` pointers in the includes, so that the folders form a tree (a folder may have more than one parent) with
 * every folder reachable from Home:
 * - a pointer to an unknown folder or a system not in [systems] is skipped (it stays in the include);
 * - pointers are followed depth-first, from the folders nothing points to first, then in list order; a pointer back to a
 *   folder on the current path (a cycle, including a folder pointing to itself) is not followed;
 * - a folder at [MAX_FOLDER_DEPTH] shows no folders, only systems and items: deeper pointers are not followed.
 * A folder with no followed pointer to it stays on Home's top level, so a hand-edited cycle or chain never hides one. Pointers
 * not followed are reported in [FolderView.problems]. Parents are in list order. Of views sharing an id only the first takes part.
 */
internal fun nestFolders(views: List<FolderView>, systems: Set<String>): List<FolderView> {
    val index = HashMap<String, Int>()
    views.forEachIndexed { i, v -> index.putIfAbsent(v.def.id, i) }
    val first = BooleanArray(views.size) { index[views[it].def.id] == it }
    val out = Array(views.size) { i ->
        val pointers = if (first[i]) views[i].def.include.filter { it.startsWith(FOLDER_POINTER) } else emptyList()
        pointers.mapNotNull { index[it.removePrefix(FOLDER_POINTER)] }.distinct()
    }
    val problems = Array(views.size) { ArrayList<String>() }
    // depth-first over the pointers: 0 unseen, 1 on the current path, 2 done; back edges (cycles) are dropped
    val state = IntArray(views.size)
    val followed = Array(views.size) { ArrayList<Int>() }
    val finished = ArrayList<Int>(views.size)
    fun visit(i: Int) {
        state[i] = 1
        for (c in out[i]) {
            when (state[c]) {
                0 -> {
                    followed[i] += c
                    visit(c)
                }
                1 -> problems[i] += "\"$FOLDER_POINTER${views[c].def.id}\" would put a folder inside itself; it is not shown here"
                else -> followed[i] += c
            }
        }
        state[i] = 2
        finished += i
    }
    val pointedTo = BooleanArray(views.size).also { p -> out.forEach { it.forEach { c -> p[c] = true } } }
    views.indices.filter { first[it] && !pointedTo[it] }.forEach { if (state[it] == 0) visit(it) }
    views.indices.filter { first[it] }.forEach { if (state[it] == 0) visit(it) }
    // reverse finishing order puts every parent before its children: levels are final when their pointers are read
    val level = IntArray(views.size) { 1 }
    val parents = Array(views.size) { ArrayList<Int>() }
    val shown = Array(views.size) { HashSet<Int>() }
    for (p in finished.asReversed()) {
        for (c in followed[p]) {
            if (level[p] < MAX_FOLDER_DEPTH) {
                level[c] = maxOf(level[c], level[p] + 1)
                parents[c] += p
                shown[p] += c
            } else {
                problems[p] += "\"$FOLDER_POINTER${views[c].def.id}\" would nest folders more than $MAX_FOLDER_DEPTH deep; it is not shown here"
            }
        }
    }
    return views.mapIndexed { i, v ->
        if (!first[i]) return@mapIndexed v
        val children = v.def.include.filter { e ->
            when {
                e.startsWith(FOLDER_POINTER) -> index[e.removePrefix(FOLDER_POINTER)]?.let { it in shown[i] } == true
                e.startsWith(SYSTEM_POINTER) -> e.removePrefix(SYSTEM_POINTER) in systems
                else -> false
            }
        }.distinct()
        v.copy(
            children = children,
            parents = parents[i].sorted().map { views[it].def.id },
            level = level[i],
            problems = v.problems + problems[i]
        )
    }
}

/** Home tile ids shown inside a folder instead of on Home's top level: nested folders and every system a folder shows. */
fun List<FolderView>.nestedTileIds(): Set<String> = buildSet {
    this@nestedTileIds.forEach { v ->
        if (v.parents.isNotEmpty()) add(FOLDER_POINTER + v.def.id)
        v.children.filterTo(this) { it.startsWith(SYSTEM_POINTER) }
    }
}

/**
 * How many folder levels [tileId] spans: 0 for anything but a folder, 1 for a folder holding no folders, and so on. Moving it
 * into a folder at level L keeps within [MAX_FOLDER_DEPTH] when L + span ≤ MAX_FOLDER_DEPTH.
 */
fun List<FolderView>.span(tileId: String): Int {
    if (!tileId.startsWith(FOLDER_POINTER)) return 0
    val byId = associateBy { it.def.id }

    // children of a nested view never lead back up (nestFolders), and the depth bound ends the walk regardless
    fun span(id: String, depth: Int): Int {
        val v = byId[id] ?: return 1
        if (depth > MAX_FOLDER_DEPTH) return 1
        return 1 + (v.children.filter { it.startsWith(FOLDER_POINTER) }.maxOfOrNull { span(it.removePrefix(FOLDER_POINTER), depth + 1) } ?: 0)
    }
    return span(tileId.removePrefix(FOLDER_POINTER), 1)
}

/** True when folder [id] is the folder [tileId] names or is shown somewhere inside it (moving the tile there makes a cycle). */
fun List<FolderView>.isWithin(id: String, tileId: String): Boolean {
    if (!tileId.startsWith(FOLDER_POINTER)) return false
    val byId = associateBy { it.def.id }
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(tileId.removePrefix(FOLDER_POINTER)))
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        if (next == id) return true
        if (!seen.add(next)) continue
        byId[next]?.children?.filter { it.startsWith(FOLDER_POINTER) }?.mapTo(queue) { it.removePrefix(FOLDER_POINTER) }
    }
    return false
}

/** [view]'s label after its first parents' (`Lists › RPGs`), for choosing among folders of the same name. */
fun List<FolderView>.path(view: FolderView): String {
    val byId = associateBy { it.def.id }
    val labels = mutableListOf(view.def.label)
    var at = view
    repeat(MAX_FOLDER_DEPTH) {
        at = at.parents.firstOrNull()?.let(byId::get) ?: return labels.asReversed().joinToString(" › ")
        labels += at.def.label
    }
    return labels.asReversed().joinToString(" › ")
}
