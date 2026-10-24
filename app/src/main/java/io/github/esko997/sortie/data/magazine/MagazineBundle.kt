package io.github.esko997.sortie.data.magazine

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Issues bundled in the APK under assets/magazines/: one folder per issue plus catalog.json, written by
 * tools/publish-magazines.py into the separate content repository and staged only by builds given -PsortieMagazinesDir
 * (app/build.gradle.kts; without it there is no catalog and no bundled issue):
 *   {"format": 1, "digest": "<sha256>", "issues": [{"id": "...", "revision": 2, "digest": "<sha256>",
 *     "files": [{"path": "issue.json", "size": 123, "sha256": "<hex>"}, ...]}]}
 * Assets cannot be read as files, so the packs are copied once per catalog digest into app storage: into a staging folder,
 * verified against the catalog, then renamed into place (`<root>/<digest>/`). Later opens only check that folder; an app
 * update with new content has a new digest and replaces it. Only this app-private root is ever written or deleted.
 */

internal const val CATALOG = "catalog.json"
private const val COMPLETE = ".complete"
private val HEX64 = Regex("[0-9a-f]{64}")

/** A bundled asset by its path under assets/magazines/; null when there is none. */
fun interface MagazineAssets {
    fun open(path: String): InputStream?
}

internal data class BundleFile(val path: String, val size: Long, val sha256: String)

internal data class BundleIssue(val id: String, val revision: Int, val digest: String, val files: List<BundleFile>)

internal data class BundleCatalog(val digest: String, val issues: List<BundleIssue>)

class BundledMagazines(private val assets: MagazineAssets, private val root: File) {
    private var prepared: MagazineShelf? = null

    /** Blocking (first call copies the packs): the bundled issues, read from app storage. */
    @Synchronized
    fun shelf(): MagazineShelf = prepared ?: prepare().also { if (it.problem == null) prepared = it }

    private fun prepare(): MagazineShelf {
        val text = try {
            assets.open(CATALOG)?.use { it.readBytes().decodeToString() } ?: return MagazineShelf(root, emptyList(), emptyList())
        } catch (e: IOException) {
            return MagazineShelf(root, emptyList(), emptyList(), "Could not read the built-in magazines: ${e.message}")
        }
        val catalog = parseCatalog(text) ?: return MagazineShelf(root, emptyList(), listOf(BrokenIssue(CATALOG, "The built-in magazine catalog is damaged")))
        val snapshot = File(root, catalog.digest)
        return try {
            val marker = File(snapshot, COMPLETE)
            if (!marker.isFile) extract(catalog, snapshot)
            val failed = marker.readLines().mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { BrokenIssue(it[0], it[1]) } }
            val shelf = readShelf(snapshot, IssueSource.BUNDLED)
            shelf.copy(broken = (shelf.broken + failed).sortedBy { it.folder })
        } catch (e: IOException) {
            MagazineShelf(root, emptyList(), emptyList(), "Could not prepare the built-in magazines: ${e.message}")
        }
    }

    /** Copies every catalogued issue into a staging folder, then renames it to [snapshot]; older snapshots are removed. */
    private fun extract(catalog: BundleCatalog, snapshot: File) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot create ${root.path}")
        val staging = File(root, ".staging-${catalog.digest}-${System.nanoTime()}")
        try {
            val failed = StringBuilder()
            for (issue in catalog.issues) {
                val dir = File(staging, issue.id)
                val problem = try {
                    issue.files.firstNotNullOfOrNull { copy(issue.id, it, File(dir, it.path)) }
                } catch (e: IOException) {
                    "could not be copied: ${e.message}"
                }
                if (problem != null) {
                    dir.deleteRecursively()
                    failed.append(issue.id).append('\t').append("The built-in copy of this issue $problem").append('\n')
                }
            }
            if (!staging.isDirectory && !staging.mkdirs()) throw IOException("cannot create ${staging.path}")
            File(staging, COMPLETE).writeText(failed.toString())
            if (snapshot.exists()) snapshot.deleteRecursively() // a leftover without its marker
            if (!staging.renameTo(snapshot)) throw IOException("cannot move the built-in magazines into place")
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        root.listFiles()?.forEach { if (it.name != snapshot.name) it.deleteRecursively() }
    }

    /** Copies one file, checking its size and digest; returns the problem, or null when it arrived intact. */
    private fun copy(issueId: String, file: BundleFile, target: File): String? {
        val input = assets.open("$issueId/${file.path}") ?: return "is missing ${file.path}"
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        target.parentFile?.mkdirs()
        input.use { inp ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                    out.write(buf, 0, n)
                    size += n
                }
            }
        }
        if (size != file.size || sha.digest().toHex() != file.sha256) return "has a damaged ${file.path}"
        return null
    }

    companion object {
        /** The APK's assets/magazines/, prepared into the app's private files. */
        fun forApp(context: Context): BundledMagazines {
            val app = context.applicationContext
            return BundledMagazines(
                { path ->
                    try {
                        app.assets.open("magazines/$path")
                    } catch (e: IOException) {
                        null
                    }
                },
                File(app.filesDir, "magazines-bundled")
            )
        }
    }
}

/** The catalog, or null when it is malformed (unknown format, bad ids, unsafe paths, bad digests). */
internal fun parseCatalog(text: String): BundleCatalog? = try {
    val root = Json.parseToJsonElement(text) as JsonObject
    require((root["format"] as JsonPrimitive).intOrNull == 1)
    val digest = (root["digest"] as JsonPrimitive).content
    require(HEX64.matches(digest))
    val seen = HashSet<String>()
    val issues = (root["issues"] as JsonArray).map { e ->
        val o = e as JsonObject
        val id = (o["id"] as JsonPrimitive).content
        require(SLUG.matches(id) && seen.add(id))
        val revision = (o["revision"] as JsonPrimitive).intOrNull!!
        require(revision > 0)
        val files = (o["files"] as JsonArray).map { f ->
            val fo = f as JsonObject
            val path = (fo["path"] as JsonPrimitive).content
            val sha = (fo["sha256"] as JsonPrimitive).content
            val size = (fo["size"] as JsonPrimitive).longOrNull!!
            require(isSafeIssuePath(path) && !path.split('/').any { it.startsWith('.') } && HEX64.matches(sha) && size >= 0)
            BundleFile(path, size, sha)
        }
        require(files.any { it.path == "issue.json" } && files.map { it.path }.toSet().size == files.size)
        BundleIssue(id, revision, (o["digest"] as JsonPrimitive).content.also { require(HEX64.matches(it)) }, files)
    }
    BundleCatalog(digest, issues)
} catch (e: Exception) {
    null
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
