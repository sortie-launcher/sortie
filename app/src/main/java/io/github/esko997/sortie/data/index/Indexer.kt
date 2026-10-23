package io.github.esko997.sortie.data.index

import android.util.Log
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.gamelist.GamelistParser
import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the games table from the card. Incremental: a file whose (path, size, mtime) signature is unchanged keeps its
 * row untouched unless the gamelist or guide changed; rows for files that disappeared are deleted per system.
 */
class Indexer(private val dao: GameDao, private val manifests: ManifestStore) {
    data class Report(
        val systems: Int,
        val files: Int,
        val updated: Int,
        val removed: Int,
        val millis: Long,
        val problems: List<String>
    )

    suspend fun run(force: Boolean = false): Report = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val snap = manifests.snapshot.value
        val root = File(snap.device.root)
        val gen = t0
        var files = 0
        var updated = 0
        var removed = 0
        val problems = mutableListOf<String>()
        for (sys in snap.systems) {
            try {
                val (f, u) = indexSystem(root, sys, snap.guide, gen, force)
                files += f
                updated += u
                removed += dao.deleteStale(sys.id, gen)
            } catch (e: Exception) {
                Log.w(TAG, "system ${sys.id}", e)
                problems += "${sys.id}: ${e.message}"
            }
        }
        Report(snap.systems.size, files, updated, removed, System.currentTimeMillis() - t0, problems)
    }

    private suspend fun indexSystem(
        root: File,
        sys: SystemDef,
        guide: Map<String, GuideEntry>,
        gen: Long,
        force: Boolean
    ): Pair<Int, Int> {
        val gamelistFile = sys.gamelist?.let { File(root, it) }
        val gamelistMtime = gamelistFile?.takeIf { it.isFile }?.lastModified() ?: 0L
        val existing = dao.signatures(sys.id).associateBy { it.id }
        val found = ArrayList<Triple<File, String, Long>>() // file, id, mtime
        val arriving = HashSet<String>()
        for (rootRel in sys.roots) {
            val dir = File(root, rootRel)
            if (!dir.isDirectory) continue
            val exts = sys.extensions.map { it.lowercase(Locale.ROOT) }.toSet()
            dir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.startsWith(".syncthing.") && name.endsWith(".tmp")) {
                    arriving += name.removePrefix(".syncthing.").removeSuffix(".tmp")
                    return@forEach
                }
                if (name.startsWith(".") || !f.isFile) return@forEach
                if (exts.isNotEmpty() && name.substringAfterLast('.', "").lowercase(Locale.ROOT).let { ".$it" !in exts }) return@forEach
                if (name.lowercase(Locale.ROOT) in SKIP_FILES) return@forEach
                if (name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SKIP_EXT) return@forEach
                found += Triple(f, "${sys.folder}/$name", f.lastModified())
            }
        }
        // Metadata changed (gamelist/guide) -> rewrite every row; otherwise only new/changed files.
        val metaChanged = force || gamelistMtime > (
            existing.values.maxOfOrNull {
                it.addedAt
            } ?: 0L
            ) - 1 // cheap heuristic, revisit with a meta table
        val gamelist = if (found.isNotEmpty()) gamelistFile?.let { GamelistParser.parse(it) } ?: emptyMap() else emptyMap()
        val mediaDir = sys.media?.let { File(root, it) }
        val rows = ArrayList<GameEntity>()
        var keep = 0
        for ((f, id, mtime) in found) {
            val sig = existing[id]
            if (!metaChanged && sig != null && sig.mtime == mtime && sig.size == f.length()) {
                keep++
                continue
            }
            val stem = f.name.substringBeforeLast('.')
            val gl = gamelist[f.name]
            val g = guide[id]
            val title = gl?.name?.takeIf { it.isNotBlank() } ?: cleanTitle(stem)
            val hack = HACK_RX.containsMatchIn(f.name)
            rows += GameEntity(
                id = id, systemId = sys.id, folder = sys.folder, fileName = f.name, path = f.path, size = f.length(), mtime = mtime,
                title = title, sortTitle = sortKey(title), desc = gl?.desc, rating = gl?.rating, year = gl?.releaseYear,
                developer = gl?.developer, publisher = gl?.publisher, genre = gl?.genre, players = gl?.players,
                playersMax = gl?.players?.let { PLAYERS_RX.findAll(it).lastOrNull()?.value?.toIntOrNull() } ?: 1,
                fit = g?.fit ?: 0,
                hook = g?.hook?.takeIf {
                    it.isNotBlank()
                },
                flags = g?.flags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "",
                tags = g?.tags?.joinToString(",")?.lowercase(Locale.ROOT) ?: "", handWritten = g?.auto == false,
                cover = media(mediaDir, "covers", stem), screenshot = media(mediaDir, "screenshots", stem), marquee = media(mediaDir, "marquees", stem),
                placeholder = f.length() == 0L, arriving = f.name in arriving, hack = hack,
                addedAt = sig?.addedAt ?: System.currentTimeMillis(), scanGen = gen
            )
        }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
        if (keep > 0) dao.touch(sys.id, gen, found.map { it.second }) // keep unchanged rows in this generation
        return found.size to rows.size
    }

    private fun media(dir: File?, kind: String, stem: String): String? {
        dir ?: return null
        for (ext in MEDIA_EXT) {
            val f = File(dir, "$kind/$stem$ext")
            if (f.isFile) return f.path
        }
        return null
    }

    companion object {
        private const val TAG = "Indexer"
        private val MEDIA_EXT = listOf(".png", ".jpg", ".webp")
        private val SKIP_EXT =
            setOf("txt", "nfo", "dat", "db", "jpg", "png", "sav", "srm", "m3u8", "cht", "ngf", "sta", "state", "cue2", "json", "xml")
        private val SKIP_FILES =
            setOf("neogeo.zip", "neogeo.7z", "pgm.zip", "ngp.zip", "stvbios.zip", "naomi.zip", "awbios.zip", "decocass.zip", "skns.zip")
        private val HACK_RX =
            Regex(
                "(?i)\\((hack|translation|en\\)|v\\d|redux|patched|plus v|one vision|true analogs|un-working|undub|mod )|\\[.*(hack|patch)"
            )
        private val PLAYERS_RX = Regex("\\d+")
        private val TAG_RX = Regex("\\s*[(\\[][^)\\]]*[)\\]]")

        fun cleanTitle(stem: String): String {
            var t = stem.replace(TAG_RX, "").trim()
            val comma = t.indexOf(", The")
            if (comma > 0 && t.endsWith(", The")) t = "The " + t.removeSuffix(", The")
            return t.ifBlank { stem }
        }

        fun sortKey(title: String): String = title.lowercase(Locale.ROOT).removePrefix("the ").removePrefix("a ").trim()
    }
}
