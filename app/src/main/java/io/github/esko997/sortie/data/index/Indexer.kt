package io.github.esko997.sortie.data.index

import android.util.Log
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.IndexMetaEntity
import io.github.esko997.sortie.data.db.SQLITE_CHUNK
import io.github.esko997.sortie.data.db.Signature
import io.github.esko997.sortie.data.gamelist.GamelistParser
import io.github.esko997.sortie.data.guide.CuratedCatalog
import io.github.esko997.sortie.data.guide.CuratedGuide
import io.github.esko997.sortie.data.guide.GuidePrecedence
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
 * unless the system's metadata (gamelist mtime, guide, bundled catalogue and hack-rules stamp, media folder mtime) or its folder
 * changed; rows for files that disappeared are deleted. Never runs when the card cannot be read, so an unmounted card or a
 * missing permission cannot empty the index. The same holds per system: a configured folder that is missing or unreadable is
 * not an empty folder, so while any of a system's folders is unavailable none of its rows are deleted (files found elsewhere are
 * still added and updated), and the report says which folder is unavailable.
 *
 * Editorial fields (fit, hook, flags, tags, controls, hand-written) come from the card's guide.json and, where it leaves gaps,
 * from the [curated] catalogue bundled with the app; see [GuidePrecedence].
 */
class Indexer(private val dao: GameDao, private val manifests: ManifestStore, private val curated: CuratedGuide) {
    data class Report(val systems: Int, val files: Int, val updated: Int, val removed: Int, val millis: Long, val problems: List<String>, val variants: Int = 0)

    private val lock = Mutex()

    suspend fun run(force: Boolean = false): Report = lock.withLock { withContext(Dispatchers.IO) { runLocked(force) } }

    /** Startup checks only metadata, not ROM folders. A catalog upgrade also refreshes a nonempty cached library. */
    suspend fun needsMetadataRefresh(): Boolean = withContext(Dispatchers.IO) {
        val snap = manifests.snapshot.value
        val expected = metaStamp(snap.guideStamp, curated.catalog.stamp, snap.library)
        snap.systems.any { dao.meta(it.id)?.guideStamp != expected }
    }

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
        val catalog = curated.catalog
        for (sys in snap.systems) {
            try {
                val r = indexSystem(root, sys, snap.guide, snap.guideStamp, catalog, snap.library, force)
                files += r.files
                updated += r.updated
                removed += r.removed
                variants += r.variants
                problems += r.problems
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "system ${sys.id}", e)
                problems += "${sys.id}: ${e.message}"
            }
        }
        // Only a complete, valid configuration can remove systems, including intentionally removing the last one.
        // Missing/corrupt manifests and interrupted setup recovery must never empty a cached library.
        if (snap.errors.isEmpty()) removed += dao.deleteSystemsNotIn(snap.systems.map { it.id })
        return Report(snap.systems.size, files, updated, removed, System.currentTimeMillis() - t0, problems, variants).also { Log.i(TAG, "indexed: $it") }
    }

    private class SystemResult(val files: Int, val updated: Int, val removed: Int, val variants: Int, val problems: List<String>)

    private suspend fun indexSystem(
        root: File,
        sys: SystemDef,
        guide: Map<String, GuideEntry>,
        guideStamp: String,
        catalog: CuratedCatalog,
        rules: LibraryRules,
        force: Boolean
    ): SystemResult {
        val gamelistFile = sys.gamelist?.let { resolve(root, it) }?.takeIf { it.isFile }
        val mediaDir = sys.media?.let { resolve(root, it) }?.takeIf { it.isDirectory }
        val meta = IndexMetaEntity(sys.id, gamelistFile?.lastModified() ?: 0L, metaStamp(guideStamp, catalog.stamp, rules), mediaDir?.lastModified() ?: 0L)
        val metaChanged = force || dao.meta(sys.id) != meta
        val existing = dao.signatures(sys.id).associateBy { it.id }
        val scan = scan(root, sys, rules)
        val plan = plan(existing, scan, metaChanged, sys.folder)
        val hackRx = HackMarks.compile(rules)
        val gamelist = if (plan.build.isNotEmpty()) gamelistFile?.let { GamelistParser.parse(it) } ?: emptyMap() else emptyMap()
        val media = MediaIndex(mediaDir)
        val rows = ArrayList<GameEntity>(plan.build.size)
        for (fd in plan.build) {
            val sig = existing[fd.id]
            val stem = fd.file.name.substringBeforeLast('.')
            val gl = gamelist[fd.file.name]
            val external = guide[fd.id]
            val title = gl?.name?.takeIf { it.isNotBlank() } ?: cleanTitle(stem)
            // an individually authored guide.json entry is complete; otherwise the bundled catalogue may fill its gaps
            val bundled = if (external?.auto == false) {
                null
            } else {
                catalog.matchFile(sys.id, fd.file)
            }
            val g = GuidePrecedence.effective(external, bundled)
            rows += GameEntity(
                id = fd.id, systemId = sys.id, folder = sys.folder, fileName = fd.file.name, path = fd.file.path, size = fd.size, mtime = fd.mtime,
                title = title, sortTitle = sortKey(title), desc = gl?.desc, rating = gl?.rating, year = gl?.releaseYear,
                developer = gl?.developer, publisher = gl?.publisher, genre = gl?.genre, players = gl?.players,
                playersMax = gl?.players?.let { PLAYERS_RX.findAll(it).lastOrNull()?.value?.toIntOrNull() } ?: 1,
                fit = g?.fit ?: 0, hook = g?.hook?.takeIf { it.isNotBlank() }, flags = g?.flags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "",
                tags = g?.tags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "", handWritten = g?.auto == false,
                cover = media.find("covers", stem), screenshot = media.find("screenshots", stem), marquee = media.find("marquees", stem),
                placeholder = fd.size == 0L, arriving = fd.file.name in scan.arriving, hack = HackMarks.isHack(fd.file.name, hackRx, g),
                // first seen = the file's own mtime (adb and Syncthing preserve it)
                addedAt = sig?.addedAt ?: fd.mtime, controls = g?.controls?.takeIf { it.isNotBlank() }
            )
        }
        rows.chunked(SQLITE_CHUNK).forEach { dao.upsertAll(it) }
        plan.arriving.forEach { (arriving, ids) -> ids.chunked(SQLITE_CHUNK).forEach { dao.setArriving(it, arriving) } }
        var removed = 0
        plan.delete.chunked(SQLITE_CHUNK).forEach { removed += dao.deleteByIds(it) }
        var nVariants = 0
        run { // cheap enough to do every pass: one narrow query per system
            val names = dao.namesOf(sys.id)
            val variants = Variants.resolve(names.map { Variants.Entry(it.id, it.fileName, !it.placeholder) })
            dao.clearVariants(sys.id)
            variants.entries.groupBy({ it.value }, { it.key }).forEach { (primary, ids) -> ids.chunked(SQLITE_CHUNK).forEach { dao.setVariantOf(it, primary) } }
            nVariants = variants.size
        }
        // Rows kept from an unavailable folder missed this metadata change: leave the old stamp so they refresh once it is back.
        if (scan.complete || !metaChanged) dao.putMeta(meta)
        return SystemResult(scan.found.size, rows.size, removed, nVariants, problems(sys, scan, plan))
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

    /** How one configured root looked this pass: [LISTED] is the only state in which its absent files really are gone. */
    internal enum class RootState { LISTED, MISSING, UNREADABLE }

    internal class RootScan(val dir: File, val state: RootState)

    internal class Found(val file: File, val id: String, val mtime: Long, val size: Long)

    /** A system's files across its roots; [arriving] holds names Syncthing is still writing. */
    internal class Scan(val found: Map<String, Found>, val arriving: Set<String>, val roots: List<RootScan>) {
        val unavailable: List<RootScan> get() = roots.filter { it.state != RootState.LISTED }
        val complete: Boolean get() = roots.all { it.state == RootState.LISTED }
    }

    /** What to write: rows to (re)build, arriving-flag flips, rows to delete and stale rows [kept] because a root is unavailable. */
    internal class Plan(val build: List<Found>, val arriving: Map<Boolean, List<String>>, val delete: List<String>, val kept: List<String>)

    companion object {
        private const val TAG = "Indexer"
        private val SKIP_EXT =
            setOf("txt", "nfo", "dat", "db", "jpg", "png", "sav", "srm", "m3u8", "cht", "ngf", "sta", "state", "json", "xml", "tmp", "titleid", "psvita")
        private val PLAYERS_RX = Regex("\\d+")
        private val TAG_RX = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

        private val ARTICLE_RX = Regex("""^(.*?), (The|A|An)(?=$| - )(.*)$""")

        /** "Legend of Zelda, The - Spirit Tracks (USA)" -> "The Legend of Zelda - Spirit Tracks". */
        fun cleanTitle(stem: String): String {
            val t = stem.replace(TAG_RX, "").trim()
            val fixed = ARTICLE_RX.replace(t) { m -> "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}" }
            return fixed.ifBlank { stem }
        }

        fun sortKey(title: String): String = title.lowercase(Locale.ROOT).removePrefix("the ").removePrefix("a ").trim()

        /** A manifest path: absolute (a root may live on another volume) or relative to the card root. */
        internal fun resolve(cardRoot: File, path: String): File = if (path.startsWith("/")) File(path) else File(cardRoot, path)

        /**
         * The stored guide stamp: guide.json's own stamp, the bundled curated catalogue's ([CuratedCatalog.stamp], its content
         * digest) and the hack rules ([HackMarks.stamp]), so an app upgrade that changes the shipped prose or the rules, or a new
         * `hackRegex`, rebuilds cached rows on the next ordinary pass, not only after a forced rescan.
         */
        internal fun metaStamp(guideStamp: String, curatedStamp: String, rules: LibraryRules): String = "$guideStamp|$curatedStamp|${HackMarks.stamp(rules)}"

        /** Lists every root of [sys] once (immediate files only). Missing and unreadable roots are recorded, never read as empty. */
        internal fun scan(cardRoot: File, sys: SystemDef, rules: LibraryRules): Scan {
            val exts = sys.extensions.map { it.lowercase(Locale.ROOT) }.toSet()
            val skipFiles = rules.skipFiles.map { it.lowercase(Locale.ROOT) }.toSet()
            val found = LinkedHashMap<String, Found>()
            val arriving = HashSet<String>()
            val roots = ArrayList<RootScan>(sys.roots.size)
            for (rootRel in sys.roots) {
                val dir = resolve(cardRoot, rootRel)
                if (!dir.isDirectory) {
                    roots += RootScan(dir, RootState.MISSING)
                    continue
                }
                val listing = dir.listFiles()
                if (listing == null) {
                    roots += RootScan(dir, RootState.UNREADABLE)
                    continue
                }
                roots += RootScan(dir, RootState.LISTED)
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
                    val size = f.length()
                    // the same filename in two roots: prefer the one with bytes (a shed placeholder elsewhere)
                    if (prev == null || (prev.size == 0L && size > 0L)) found[id] = Found(f, id, f.lastModified(), size)
                }
            }
            return Scan(found, arriving, roots)
        }

        /**
         * Decides the writes for one system. A row is current only if its file kept its size, mtime and path (a root moved to
         * another folder or volume keeps size and mtime but must update the stored path) and the system's [folder]. Stale rows
         * are deleted only after every root listed: otherwise a removed card or revoked access would erase them.
         */
        internal fun plan(existing: Map<String, Signature>, scan: Scan, metaChanged: Boolean, folder: String): Plan {
            val build = ArrayList<Found>()
            val arriving = HashMap<Boolean, MutableList<String>>()
            for (fd in scan.found.values) {
                val sig = existing[fd.id]
                val current = !metaChanged && sig != null && sig.mtime == fd.mtime && sig.size == fd.size && sig.path == fd.file.path && sig.folder == folder
                if (!current) {
                    build += fd
                    continue
                }
                val isArriving = fd.file.name in scan.arriving
                if (sig!!.arriving != isArriving) arriving.getOrPut(isArriving) { ArrayList() } += fd.id
            }
            val stale = existing.keys.filter { it !in scan.found }
            return if (scan.complete) Plan(build, arriving, stale, emptyList()) else Plan(build, arriving, emptyList(), stale)
        }

        /** One report line per unavailable root, saying what was kept so the user knows nothing was lost. */
        internal fun problems(sys: SystemDef, scan: Scan, plan: Plan): List<String> {
            if (scan.complete) return emptyList()
            val kept = if (plan.kept.isEmpty()) "" else "; kept ${plan.kept.size} game(s) from the last scan until it is back"
            return scan.unavailable.map { r ->
                val why = when (r.state) {
                    RootState.MISSING -> "is missing (storage removed, or the folder was moved or renamed)"
                    else -> "can't be read (check that Sortie still has All files access)"
                }
                "${sys.label}: folder ${r.dir.path} $why$kept"
            }
        }
    }
}
