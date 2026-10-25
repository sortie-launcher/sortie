package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.setup.LibrarySetupStore
import io.github.esko997.sortie.data.setup.ManifestCommit
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The import's overlap guard: every file and folder [CocoonImporter] (and the stores it calls) writes, checked against the
 * Cocoon folder and the chosen backup before the first write. Pure JVM, so it is tested without an app graph.
 *
 * Two kinds of write are told apart. A file *opened* for writing ([ManifestCommit]'s `<manifest>.setup-new` copies and journal
 * temp file, UserStore's `user.json.tmp`, PlayLog's appended `playlog.jsonl`, BackupManager's `<safety copy>.json.tmp`, the
 * artwork temp files) changes whatever is already at that name: a symbolic link there redirects the write and a hard link
 * shares its content with another name, which may be in Cocoon's folder, so an existing one must be a plain file with a single
 * name. A file *renamed onto* (the manifests, the journal, user.json, safety copies, artwork targets) only replaces the
 * directory entry, so a link there is harmless, and those, like the folders written into, need only to resolve outside the
 * protected trees ([CocoonPaths.isInside] follows links and same-file aliases).
 */
internal object CocoonWriteGuard {
    /** As SortieApp.Graph names them in Sortie's folder. */
    const val USER_FILE = "user.json"
    const val PLAY_LOG = "playlog.jsonl"

    /** BackupManager.writeSafetyCopy's prefix for the import's safety copy. */
    const val SAFETY_PREFIX = "pre-cocoon-import"

    private val MANIFESTS = with(LibrarySetupStore) { listOf(DEVICE, SYSTEMS, LAUNCHERS, FOLDERS, GUIDE) }

    /** The sibling CocoonImporter's artwork copy writes before renaming it onto [target]. */
    fun tempOf(target: File) = File(target.path + ".tmp")

    /**
     * Why the import must not run: one line per destination that could change something under [protectedTrees], empty when
     * none can. [cardRoot] resolves the plan's card-relative media paths; [linkCount] is a file's number of names (null when it
     * cannot be read, which counts as a hazard).
     */
    fun hazards(
        protectedTrees: List<File>,
        sortieDir: File,
        safetyDir: File,
        cardRoot: File?,
        plan: CocoonImportPlan,
        options: ImportOptions,
        linkCount: (File) -> Int? = ::nioLinkCount
    ): List<String> {
        fun resolve(path: String) = if (path.startsWith("/") || cardRoot == null) File(path) else File(cardRoot, path)
        val folders = buildList {
            add(sortieDir)
            add(safetyDir)
            if (options.artwork) plan.mediaDirs.values.forEach { add(resolve(it)) }
            if (options.systems) plan.newSystems.mapNotNull { it.system.media }.forEach { add(resolve(it)) }
        }
        val renamedOnto = buildList {
            MANIFESTS.forEach { add(File(sortieDir, it)) }
            add(File(sortieDir, ManifestCommit.JOURNAL))
            add(File(sortieDir, USER_FILE))
            if (options.artwork) plan.artwork.forEach { add(it.target) }
        }
        val opened = buildList {
            MANIFESTS.forEach { add(File(sortieDir, it + ManifestCommit.STAGED_SUFFIX)) }
            add(File(sortieDir, ManifestCommit.JOURNAL_TMP))
            add(File(sortieDir, "$USER_FILE.tmp"))
            add(File(sortieDir, PLAY_LOG))
            // the copy's time-stamped name is not known yet; leftovers of earlier attempts are what can be there
            safetyDir.listFiles()?.filter { it.name.startsWith("$SAFETY_PREFIX-") && it.name.endsWith(".tmp") }?.forEach { add(it) }
            if (options.artwork) plan.artwork.forEach { add(tempOf(it.target)) }
        }

        val bad = LinkedHashMap<String, String>()
        fun flag(f: File, why: String) {
            bad.putIfAbsent(f.path, "${f.path}: $why")
        }
        for (f in folders + renamedOnto + opened) {
            protectedTrees.firstOrNull { CocoonPaths.isInside(f, it) }?.let { flag(f, "inside ${it.path}") }
        }
        for (f in opened) linkHazard(f, linkCount)?.let { flag(f, it) }
        if (options.artwork) {
            for (a in plan.artwork) {
                for (w in listOf(a.target, tempOf(a.target))) if (CocoonPaths.isInside(w, a.source)) flag(w, "is the file being copied")
            }
        }
        return bad.values.toList()
    }

    /** Why writing through what is at [f] could change another file, or null when nothing (or a plain single-name file) is there. */
    private fun linkHazard(f: File, linkCount: (File) -> Int?): String? {
        val path = f.toPath()
        if (Files.isSymbolicLink(path)) return "a symbolic link; writing it would change the file it points to"
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val names = linkCount(f) ?: return "its links could not be checked"
        return if (names > 1) "a hard link ($names names); writing it would change the other names' content too" else null
    }

    /** st_nlink through NIO's "unix" view; null where the platform does not offer it. */
    fun nioLinkCount(f: File): Int? = try {
        (Files.getAttribute(f.toPath(), "unix:nlink", LinkOption.NOFOLLOW_LINKS) as? Number)?.toInt()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
