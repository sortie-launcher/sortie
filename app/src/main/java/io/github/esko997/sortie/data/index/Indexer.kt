package io.github.esko997.sortie.data.index

import android.util.Log
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.IndexMetaEntity
import io.github.esko997.sortie.data.db.SQLITE_CHUNK
import io.github.esko997.sortie.data.gamelist.GamelistParser
import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.LibraryRules
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds the games table from the card. Incremental: a file whose (path, size, mtime) signature is unchanged keeps its row
 * unless the system's metadata (gamelist mtime, guide stamp, media folder mtime) changed; rows for files that disappeared are
 * deleted. Never runs when the card cannot be read, so an unmounted card or a missing permission cannot empty the index.
 */
class Indexer(private val dao: GameDao, private val manifests: ManifestStore) {
    data class Report(val systems: Int, val files: Int, val updated: Int, val removed: Int, val millis: Long, val problems: List<String>, val variants: Int = 0)

    private val lock = Mutex()

    suspend fun run(force: Boolean = false): Report = lock.withLock { withContext(Dispatchers.IO) { runLocked(force) } }

    private suspend fun runLocked(force: Boolean): Report {
        val t0 = System.currentTimeMillis()
        val snap = manifests.snapshot.value
        val root = File(snap.device.root)
        if (!root.isDirectory || root.list() == null) {
            return Report(0, 0, 0, 0, 0, listOf("card root ${root.path} is not readable; index left untouched"))
        }
        var files = 0
        var updated = 0
        var removed = 0
        var variants = 0
        val problems = mutableListOf<String>()
        for (sys in snap.systems) {
            try {
                val (f, u, r) = indexSystem(root, sys, snap.guide, snap.guideStamp, snap.library, force)
                files += f
                updated += u
                removed += r
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "system ${sys.id}", e)
                problems += "${sys.id}: ${e.message}"
            }
        }
        if (snap.systems.isNotEmpty()) removed += dao.deleteSystemsNotIn(snap.systems.map { it.id })
        return Report(snap.systems.size, files, updated, removed, System.currentTimeMillis() - t0, problems, variants).also { Log.i(TAG, "indexed: $it") }
    }

    private class Found(val file: File, val id: String, val mtime: Long, val size: Long)

    private class SystemResult(val files: Int, val updated: Int, val removed: Int, val variants: Int)

    private suspend fun indexSystem(
        root: File,
        sys: SystemDef,
        guide: Map<String, GuideEntry>,
        guideStamp: String,
        rules: LibraryRules,
        force: Boolean
    ): SystemResult {
        val gamelistFile = sys.gamelist?.let { File(root, it) }?.takeIf { it.isFile }
        val mediaDir = sys.media?.let { File(root, it) }?.takeIf { it.isDirectory }
        val meta = IndexMetaEntity(sys.id, gamelistFile?.lastModified() ?: 0L, guideStamp, mediaDir?.lastModified() ?: 0L)
        val metaChanged = force || dao.meta(sys.id) != meta
        val existing = dao.signatures(sys.id).associateBy { it.id }
        val exts = sys.extensions.map { it.lowercase(Locale.ROOT) }.toSet()
        val skipFiles = rules.skipFiles.map { it.lowercase(Locale.ROOT) }.toSet()
        val hackRx = rules.hackRegex?.let { runCatching { Regex(it) }.getOrNull() }
        val found = LinkedHashMap<String, Found>()
        val arriving = HashSet<String>()
        var unreadable = false
        for (rootRel in sys.roots) {
            val dir = File(root, rootRel)
            if (!dir.isDirectory) continue
            val listing = dir.listFiles() ?: run {
                unreadable = true
                null
            } ?: continue
            for (f in listing) {
                val name = f.name
                if (name.startsWith(".syncthing.") && name.endsWith(".tmp")) {
                    arriving += name.removePrefix(".syncthing.").removeSuffix(".tmp")
                    continue
                }
                if (name.startsWith(".") || !f.isFile) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (exts.isNotEmpty() && ".$ext" !in exts) continue
                if (ext in SKIP_EXT || name.lowercase(Locale.ROOT) in skipFiles) continue
                val id = "${sys.id}/$name"
                val prev = found[id]
                // the same filename in two roots: prefer the one with bytes (a shed placeholder elsewhere)
                if (prev == null || (prev.size == 0L && f.length() > 0L)) found[id] = Found(f, id, f.lastModified(), f.length())
            }
        }
        if (unreadable) return SystemResult(found.size, 0, 0, 0) // partial listing: do not delete anything this pass
        val gamelist = if (found.isNotEmpty()) gamelistFile?.let { GamelistParser.parse(it) } ?: emptyMap() else emptyMap()
        val media = MediaIndex(mediaDir)
        val rows = ArrayList<GameEntity>()
        for (fd in found.values) {
            val sig = existing[fd.id]
            val isArriving = fd.file.name in arriving
            if (!metaChanged && sig != null && sig.mtime == fd.mtime && sig.size == fd.size) {
                if (sig.arriving != isArriving) dao.setArriving(listOf(fd.id), isArriving)
                continue
            }
            val stem = fd.file.name.substringBeforeLast('.')
            val gl = gamelist[fd.file.name]
            val g = guide[fd.id]
            val title = gl?.name?.takeIf { it.isNotBlank() } ?: cleanTitle(stem)
            rows += GameEntity(
                id = fd.id, systemId = sys.id, folder = sys.folder, fileName = fd.file.name, path = fd.file.path, size = fd.size, mtime = fd.mtime,
                title = title, sortTitle = sortKey(title), desc = gl?.desc, rating = gl?.rating, year = gl?.releaseYear,
                developer = gl?.developer, publisher = gl?.publisher, genre = gl?.genre, players = gl?.players,
                playersMax = gl?.players?.let { PLAYERS_RX.findAll(it).lastOrNull()?.value?.toIntOrNull() } ?: 1,
                fit = g?.fit ?: 0, hook = g?.hook?.takeIf { it.isNotBlank() }, flags = g?.flags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "",
                tags = g?.tags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "", handWritten = g?.auto == false,
                cover = media.find("covers", stem), screenshot = media.find("screenshots", stem), marquee = media.find("marquees", stem),
                placeholder = fd.size == 0L, arriving = isArriving, hack = hackRx?.containsMatchIn(fd.file.name) ?: false,
                // first seen = the file's own mtime (adb and Syncthing preserve it)
                addedAt = sig?.addedAt ?: fd.mtime
            )
        }
        rows.chunked(SQLITE_CHUNK).forEach { dao.upsertAll(it) }
        val stale = existing.keys - found.keys
        var removed = 0
        stale.chunked(SQLITE_CHUNK).forEach { removed += dao.deleteByIds(it) }
        var nVariants = 0
        run { // cheap enough to do every pass: one narrow query per system
            val names = dao.namesOf(sys.id)
            val variants = Variants.resolve(names.map { Variants.Entry(it.id, it.fileName, !it.placeholder) })
            dao.clearVariants(sys.id)
            variants.entries.groupBy({ it.value }, { it.key }).forEach { (primary, ids) -> ids.chunked(SQLITE_CHUNK).forEach { dao.setVariantOf(it, primary) } }
            nVariants = variants.size
        }
        dao.putMeta(meta)
        return SystemResult(found.size, rows.size, removed, nVariants)
    }

    /** One directory listing per media kind instead of several `isFile` probes per game. */
    private class MediaIndex(dir: File?) {
        private val byKind = HashMap<String, Map<String, String>>()
        private val dir = dir

        fun find(kind: String, stem: String): String? {
            val map = byKind.getOrPut(kind) {
                val d = dir?.let { File(it, kind) } ?: return null
                d.listFiles()?.filter { it.isFile }?.associate { it.nameWithoutExtension to it.path } ?: emptyMap()
            }
            return map[stem]
        }
    }

    companion object {
        private const val TAG = "Indexer"
        private val SKIP_EXT = setOf("txt", "nfo", "dat", "db", "jpg", "png", "sav", "srm", "m3u8", "cht", "ngf", "sta", "state", "json", "xml", "tmp")
        private val PLAYERS_RX = Regex("\\d+")
        private val TAG_RX = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

        fun cleanTitle(stem: String): String {
            var t = stem.replace(TAG_RX, "").trim()
            if (t.endsWith(", The")) t = "The " + t.removeSuffix(", The")
            return t.ifBlank { stem }
        }

        fun sortKey(title: String): String = title.lowercase(Locale.ROOT).removePrefix("the ").removePrefix("a ").trim()
    }
}
