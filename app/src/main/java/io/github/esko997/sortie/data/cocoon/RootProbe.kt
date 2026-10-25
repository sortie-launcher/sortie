package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import java.util.Locale

/**
 * Finds ROM folders for Cocoon platforms. Lists directories ≤3 levels deep per volume (skips Android/, dot dirs, [exclude]),
 * reads each directory's immediate file names once, and scores a directory per platform by case-insensitive matches with
 * the platform's game file names. A directory qualifies with ≥1 hit and ≥10 % of the platform's games (≥1 hit when <10 games).
 * [hints] (persisted-grant dirs, backup roots) are scored first and always included when they hold ≥1 hit.
 *
 * Cocoon's gamelists record a file name relative to a root they do not name, so the roots have to be rediscovered; the 10 %
 * floor keeps a stray copy of one game (a download folder) from becoming a library root. Only reads directories.
 */
class RootProbe(private val volumes: List<File>, private val exclude: List<File>) {
    /** A directory and its immediate (visible) file names. */
    class Listing(val dir: File, val files: List<String>)

    private val excluded = exclude.map { CocoonPaths.canonical(it).path }
    private var cache: LinkedHashMap<String, Listing>? = null
    private val lowered = HashMap<String, Set<String>>()

    /**
     * Every listed directory holding at least one file, cached after the first call. Hint directories outside the walk (deeper
     * than three levels, another mount) are added by [discover], so the planner sees every directory a root was taken from.
     */
    fun listings(): List<Listing> = walked().values.toList()

    fun discover(platforms: Map<String, List<String>>, hints: Map<String, List<File>>): Map<String, List<File>> {
        val all = walked()
        val out = LinkedHashMap<String, List<File>>()
        for ((platform, names) in platforms) {
            val wanted = names.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
            if (wanted.isEmpty()) continue
            val need = if (wanted.size < 10) 1 else (wanted.size + 9) / 10
            val found = LinkedHashMap<String, File>()
            for (hint in hints[platform].orEmpty()) {
                val (key, listing) = listingOf(hint) ?: continue
                if (hits(key, listing, wanted) >= 1) found.putIfAbsent(key, listing.dir)
            }
            all.entries.toList()
                .map { (key, listing) -> Triple(key, listing, hits(key, listing, wanted)) }
                .filter { it.third >= need }
                .sortedByDescending { it.third }
                .forEach { (key, listing, _) -> found.putIfAbsent(key, listing.dir) }
            if (found.isNotEmpty()) out[platform] = found.values.toList()
        }
        return out
    }

    private fun hits(key: String, listing: Listing, wanted: Set<String>): Int =
        lowered.getOrPut(key) { listing.files.mapTo(HashSet()) { it.lowercase(Locale.ROOT) } }.count { it in wanted }

    private fun isExcluded(canonicalPath: String): Boolean = excluded.any { canonicalPath == it || canonicalPath.startsWith("$it/") }

    /** The cached listing of [dir], listing it now when the walk did not reach it; null when excluded or unreadable. */
    private fun listingOf(dir: File): Pair<String, Listing>? {
        val key = CocoonPaths.canonical(dir).path
        if (isExcluded(key)) return null
        val cached = walked()[key]
        if (cached != null) return key to cached
        val children = dir.listFiles() ?: return null
        val listing = Listing(dir, children.filter { !it.name.startsWith(".") && it.isFile }.map { it.name })
        walked()[key] = listing
        return key to listing
    }

    private fun walked(): LinkedHashMap<String, Listing> = cache ?: LinkedHashMap<String, Listing>().also { map ->
        cache = map
        val seen = HashSet<String>()
        for (volume in volumes) walk(volume, 0, map, seen)
    }

    private fun walk(dir: File, depth: Int, into: MutableMap<String, Listing>, seen: MutableSet<String>) {
        val key = CocoonPaths.canonical(dir).path
        if (!seen.add(key) || isExcluded(key)) return
        val children = dir.listFiles() ?: return
        val files = ArrayList<String>()
        val subdirs = ArrayList<File>()
        for (child in children) {
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isFile) {
                files += name
            } else if (depth < MAX_DEPTH && child.isDirectory && !(depth == 0 && name == "Android")) {
                subdirs += child
            }
        }
        if (files.isNotEmpty()) into[key] = Listing(dir, files)
        subdirs.sortedBy { it.name }.forEach { walk(it, depth + 1, into, seen) }
    }

    companion object {
        private const val MAX_DEPTH = 3
        private val TREE_URI = Regex("content://com\\.android\\.externalstorage\\.documents/tree/[^\\s\"'<>)\\],;]+")

        /** Persisted tree grants in <cocoonDir>/permissions_debug.txt ("[PROTECTED] content://…/tree/<vol>%3A<path>") → dirs; empty when absent. Hints only. */
        fun grantHints(cocoonDir: File): List<File> {
            val file = File(cocoonDir, "permissions_debug.txt")
            if (!file.isFile) return emptyList()
            return try {
                file.bufferedReader().useLines { lines ->
                    lines.flatMap { line -> TREE_URI.findAll(line).map { it.value } }
                        .mapNotNull { CocoonPaths.fromDocumentUri(it) }
                        .filter { it.isDirectory }
                        .distinctBy { it.path }
                        .toList()
                }
            } catch (_: Exception) {
                emptyList() // a diagnostics file a Cocoon update may change or drop; never required
            }
        }

        /**
         * Hints per platform for [discover]: the [grants] (from [grantHints]) named after the platform's alias group — the grant
         * itself or, for a parent grant such as `primary:roms`, its child folder of that name — the [backup]'s roots for the
         * group, and the roots of configured [systems] in the group (resolved against [cardRoot]) so an existing system's
         * folders are always listed and claimed even when the walk does not reach them. Grants are filtered by name because a
         * hint counts with a single hit: one file name shared across platforms (gyruss.zip in fbneo and colecovision) must not
         * make another platform's folder a root.
         */
        fun hints(platforms: Collection<String>, grants: List<File>, backup: CocoonBackup?, systems: List<SystemDef>, cardRoot: File): Map<String, List<File>> =
            platforms.associateWith { platform ->
                val group = CocoonAliases.group(platform) + (CocoonAliases.PLATFORM_TO_PRESET[platform] ?: platform)
                val fromBackup = group.flatMap { backup?.roots?.get(it).orEmpty() }.mapNotNull { CocoonPaths.fromDocumentId(it) }
                val configured = systems.filter { it.id in group || it.folder in group }.flatMap { s -> s.roots.map { Indexer.resolve(cardRoot, it) } }
                val granted = grants.flatMap { g ->
                    if (g.name in group) listOf(g) else group.map { File(g, it) }.filter { it.isDirectory }
                }
                (configured + fromBackup + granted).distinctBy { it.path }
            }
    }
}
