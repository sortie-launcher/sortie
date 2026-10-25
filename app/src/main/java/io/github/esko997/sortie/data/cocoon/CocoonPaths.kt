package io.github.esko997.sortie.data.cocoon

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Path conversions and the overlap guard. Pure; no Android imports except android.net.Uri for [fromTreeUri].
 *
 * Cocoon records everything as Storage Access Framework URIs on `com.android.externalstorage.documents`, whose document ids
 * are `<volume>:<path from the volume root>`. Sortie holds the all-files grant, so those map onto plain paths and are read
 * with [File] like the rest of the library; no URI grant is ever taken or persisted.
 */
object CocoonPaths {
    private const val AUTHORITY_PREFIX = "content://com.android.externalstorage.documents/"
    private const val PRIMARY = "primary"
    private const val HOME = "home"
    private val PRIMARY_ROOT = File("/storage/emulated/0")

    /** Removable volumes appear as `/storage/<uuid>` (e.g. `ABCD-1234`); anything else is not an externalstorage volume. */
    private val VOLUME = Regex("[A-Za-z0-9][A-Za-z0-9-]*")

    /**
     * content://com.android.externalstorage.documents/{tree,document}/<vol>%3A<path> → /storage/emulated/0/<path> (primary) or
     * /storage/<vol>/<path>; null for anything else (android.resource://, other authorities).
     */
    fun fromDocumentUri(uri: String): File? {
        if (!uri.startsWith(AUTHORITY_PREFIX)) return null
        val segments = uri.removePrefix(AUTHORITY_PREFIX).substringBefore('?').substringBefore('#').split('/')
        // tree/<treeId>/document/<docId> names the document; tree/<treeId> alone (a picker result) names the tree's root
        val encoded = when {
            segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
            segments.size >= 2 && (segments[0] == "tree" || segments[0] == "document") -> segments[1]
            else -> return null
        }
        return fromDocumentId(percentDecode(encoded) ?: return null)
    }

    /** SAF picker result; same mapping as [fromDocumentUri]. */
    fun fromTreeUri(uri: Uri): File? = fromDocumentUri(uri.toString())

    /**
     * A decoded externalstorage document id (`<vol>:<path>`, as Cocoon's backup `normalized_document_id` and tree grants hold
     * them) → its path; null for another shape or a path that climbs out of the volume (`..`).
     */
    fun fromDocumentId(id: String): File? {
        val volume = id.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val base = when {
            volume == PRIMARY -> PRIMARY_ROOT
            volume == HOME -> File(PRIMARY_ROOT, "Documents")
            VOLUME.matches(volume) -> File("/storage", volume)
            else -> return null
        }
        val parts = id.substringAfter(':').split('/').filter { it.isNotEmpty() }
        if (parts.any { it == ".." || it == "." }) return null
        return if (parts.isEmpty()) base else File(base, parts.joinToString("/"))
    }

    /** Canonical path (File.canonicalFile, falling back to absoluteFile) so /sdcard and /storage/emulated/0 compare equal. */
    fun canonical(f: File): File = try {
        f.canonicalFile
    } catch (_: IOException) {
        f.absoluteFile
    }

    /**
     * True when [candidate] is [tree], inside it, or the same file via Files.isSameFile (checked only when both exist). The
     * isSameFile walk covers what canonical paths cannot see: bind mounts and FUSE aliases of one directory under two names
     * (`/storage/emulated/0` vs `/data/media/0`). A candidate that does not exist yet (an artwork target) is judged by its
     * nearest existing ancestor.
     */
    fun isInside(candidate: File, tree: File): Boolean {
        val c = canonical(candidate)
        val t = canonical(tree)
        val prefix = if (t.path.endsWith("/")) t.path else t.path + "/"
        if (c.path == t.path || c.path.startsWith(prefix)) return true
        if (!t.exists()) return false
        val treePath = t.toPath()
        var a: File? = c
        while (a != null) {
            if (a.exists()) {
                val same = try {
                    Files.isSameFile(a.toPath(), treePath)
                } catch (_: IOException) {
                    false
                }
                if (same) return true
            }
            a = a.parentFile
        }
        return false
    }

    /** RFC 3986 percent-decoding as UTF-8 (`+` stays `+`: SAF ids are not form-encoded); null for a malformed escape. */
    private fun percentDecode(s: String): String? {
        if ('%' !in s) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                val hi = Character.digit(s.getOrElse(i + 1) { ' ' }, 16)
                val lo = Character.digit(s.getOrElse(i + 2) { ' ' }, 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else {
                // copy the literal run whole so a surrogate pair is encoded as one code point
                val end = s.indexOf('%', i).let { if (it < 0) s.length else it }
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
