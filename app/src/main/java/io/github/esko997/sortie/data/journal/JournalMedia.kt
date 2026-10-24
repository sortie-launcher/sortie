package io.github.esko997.sortie.data.journal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A picked file the journal refused or could not copy; [message] is shown to the user as is. */
class ImportRefused(message: String) : IOException(message)

/**
 * Screenshots attached to journal entries, stored on the card next to user.json under `sortie/journal/media/<entry id>/`.
 * Only files the user explicitly picks are copied in (nothing is harvested), and once copied they need neither the source
 * nor a network. Imports are bounded: a source over [MAX_SOURCE_BYTES] or wider/taller than [MAX_SOURCE_EDGE] is refused,
 * and anything larger than [MAX_STORED_EDGE] or [MAX_STORED_BYTES] (or rotated by EXIF) is re-encoded down to fit.
 */
class JournalMedia(private val sortieDir: File) {
    /** The attachment's file when [rel] is a well-formed journal media path inside the card folder, else null. */
    fun file(rel: String): File? = resolve(sortieDir, rel)

    /**
     * Copies [uri] into [entryId]'s folder and returns the attachment. Throws [ImportRefused] with a readable reason when the file
     * is too large, not an image Android can decode, or cannot be read or written; a partly written copy is removed.
     */
    suspend fun import(resolver: ContentResolver, uri: Uri, entryId: String): JournalAttachment = withContext(Dispatchers.IO) {
        val dir = entryDir(sortieDir, entryId) ?: throw ImportRefused("the journal folder for this note is not inside ${sortieDir.path}")
        if (!dir.isDirectory && !dir.mkdirs()) throw ImportRefused("could not create ${dir.path}")
        // re-checked once it exists: a symlink placed meanwhile must not redirect the write
        if (entryDir(sortieDir, entryId) != dir || !dir.isDirectory) throw ImportRefused("the journal folder for this note is not inside ${sortieDir.path}")
        val (name, declared) = describe(resolver, uri)
        if (declared != null && declared > MAX_SOURCE_BYTES) {
            throw ImportRefused(
                "$name is ${mb(declared)}; screenshots up to ${mb(MAX_SOURCE_BYTES)} can be attached"
            )
        }
        val fileId = UUID.randomUUID().toString()
        val tmp = File(dir, ".$fileId.part")
        val out = File(dir, "$fileId.tmp")
        try {
            val copied = try {
                copyBounded(resolver, uri, tmp, name)
            } catch (e: ImportRefused) {
                throw e
            } catch (e: Exception) {
                throw ImportRefused("could not read $name: ${e.message ?: e.javaClass.simpleName}")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) throw ImportRefused("$name is not an image Android can open")
            if (maxOf(w, h) > MAX_SOURCE_EDGE) throw ImportRefused("$name is $w×$h; images up to $MAX_SOURCE_EDGE px on a side can be attached")
            val rotation = runCatching { rotationOf(ExifInterface(tmp.path)) }.getOrDefault(0)
            val ext = KEEP_AS_IS[bounds.outMimeType]
            val attachment = if (ext != null && rotation == 0 && maxOf(w, h) <= MAX_STORED_EDGE && copied <= MAX_STORED_BYTES) {
                val final = target(entryId, "$fileId.$ext")
                if (!tmp.renameTo(final)) throw ImportRefused("could not store $name in ${dir.path}")
                JournalAttachment("$MEDIA_DIR/$entryId/${final.name}", w, h, copied, name)
            } else {
                reencode(tmp, out, w, h, rotation, name).let { (bitmapExt, ow, oh) ->
                    val final = target(entryId, "$fileId.$bitmapExt")
                    if (!out.renameTo(final)) throw ImportRefused("could not store $name in ${dir.path}")
                    JournalAttachment("$MEDIA_DIR/$entryId/${final.name}", ow, oh, final.length(), name)
                }
            }
            attachment
        } finally {
            tmp.delete()
            out.delete()
        }
    }

    /** The stored file `<entryId>/<fileName>`, refused unless it resolves exactly as a contained journal path (see [resolve]). */
    private fun target(entryId: String, fileName: String): File =
        resolve(sortieDir, "$MEDIA_DIR/$entryId/$fileName") ?: throw ImportRefused("could not store $fileName inside ${sortieDir.path}")

    /** Deletes the attachments at [paths] (ill-formed paths are ignored) and then any entry folder left empty. */
    suspend fun delete(paths: Collection<String>) = withContext(Dispatchers.IO) {
        paths.mapNotNull { file(it) }.forEach { f ->
            if (f.exists() && !f.delete()) Log.w("JournalMedia", "could not delete ${f.path}")
            f.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }
    }

    private fun describe(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
        var name = uri.lastPathSegment ?: "screenshot"
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        return name.replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(80) to size
    }

    /** Copies at most [MAX_SOURCE_BYTES]; a longer stream is refused (a provider's declared size is not trusted). */
    private fun copyBounded(resolver: ContentResolver, uri: Uri, to: File, name: String): Long {
        val input = resolver.openInputStream(uri) ?: throw ImportRefused("could not open $name")
        var total = 0L
        input.use { src ->
            FileOutputStream(to).use { dst ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SOURCE_BYTES) throw ImportRefused("$name is larger than ${mb(MAX_SOURCE_BYTES)}; pick a smaller screenshot")
                    dst.write(buf, 0, n)
                }
                dst.fd.sync()
            }
        }
        return total
    }

    /** Decodes [src] subsampled, scales its longest edge to at most [MAX_STORED_EDGE], applies [rotation] and writes [out]. */
    private fun reencode(src: File, out: File, w: Int, h: Int, rotation: Int, name: String): Triple<String, Int, Int> {
        // bound the allocation before decoding: the subsample alone keeps width×height within MAX_DECODE_PIXELS
        val sample = sampleFor(w, h)
        val decoded = try {
            BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        } ?: throw ImportRefused("$name could not be decoded")
        if (decoded.width.toLong() * decoded.height > MAX_DECODE_PIXELS) {
            decoded.recycle()
            throw ImportRefused("$name decoded larger than expected")
        }
        var bitmap: Bitmap = decoded
        try {
            val scale = minOf(1f, MAX_STORED_EDGE.toFloat() / maxOf(bitmap.width, bitmap.height))
            if (scale < 1f || rotation != 0) {
                val m = Matrix().apply {
                    postScale(scale, scale)
                    postRotate(rotation.toFloat())
                }
                bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            }
            val alpha = bitmap.hasAlpha()
            FileOutputStream(out).use { o ->
                val ok = if (alpha) bitmap.compress(Bitmap.CompressFormat.PNG, 100, o) else bitmap.compress(Bitmap.CompressFormat.JPEG, 92, o)
                if (!ok) throw ImportRefused("$name could not be re-encoded")
                o.fd.sync()
            }
            if (out.length() > MAX_STORED_BYTES) throw ImportRefused("$name is still larger than ${mb(MAX_STORED_BYTES)} after shrinking")
            return Triple(if (alpha) "png" else "jpg", bitmap.width, bitmap.height)
        } catch (e: OutOfMemoryError) {
            throw ImportRefused("$name is too large to shrink on this device")
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    companion object {
        const val MEDIA_DIR = "journal/media"
        const val MAX_SOURCE_BYTES = 40L * 1024 * 1024
        const val MAX_SOURCE_EDGE = 16_384
        const val MAX_STORED_BYTES = 16L * 1024 * 1024
        const val MAX_STORED_EDGE = 3840

        /** Most pixels ever decoded for re-encoding: a 3840×3840 square, ~59 MB as ARGB_8888 (the output is never larger). */
        const val MAX_DECODE_PIXELS = MAX_STORED_EDGE.toLong() * MAX_STORED_EDGE

        private val ID = Regex("[A-Za-z0-9-]{1,64}")
        private val PATH = Regex("journal/media/[A-Za-z0-9-]{1,64}/[A-Za-z0-9-]{1,64}\\.(png|jpg|webp)")
        private val KEEP_AS_IS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")

        /**
         * [rel] as a file under [sortieDir], or null unless it is exactly `journal/media/<id>/<id>.<png|jpg|webp>` and contained
         * (see [contained]). user.json may come from a backup or a hand edit, so nothing else (absolute paths, `..`, other
         * folders, symlinks out of the card folder) is ever read or deleted.
         */
        fun resolve(sortieDir: File, rel: String): File? = if (PATH.matches(rel)) contained(sortieDir, rel) else null

        /** The folder [entryId]'s screenshots are written to, or null for an ill-formed id or an uncontained folder. */
        fun entryDir(sortieDir: File, entryId: String): File? = if (ID.matches(entryId)) contained(sortieDir, "$MEDIA_DIR/$entryId") else null

        /**
         * [rel] under the canonical card folder, only when its canonical path is that very path: a symlink anywhere below the card
         * folder (`journal`, `media`, an entry folder or the file) would make them differ, so it is refused, wherever it points.
         * Missing trailing parts are fine (the folder about to be created is checked before and after `mkdirs`).
         */
        private fun contained(sortieDir: File, rel: String): File? {
            val base = runCatching { sortieDir.canonicalFile }.getOrNull() ?: return null
            val f = File(base, rel)
            val canonical = runCatching { f.canonicalPath }.getOrNull() ?: return null
            return f.takeIf { canonical == it.path }
        }

        /** Smallest power-of-two subsample keeping the decoded bitmap within [MAX_DECODE_PIXELS] (decoders may round sizes up). */
        internal fun sampleFor(w: Int, h: Int): Int {
            var sample = 1
            while (((w + sample - 1) / sample).toLong() * ((h + sample - 1) / sample) > MAX_DECODE_PIXELS) sample *= 2
            return sample
        }

        private fun mb(bytes: Long): String = "${(bytes + 512 * 1024) / (1024 * 1024)} MB"

        /** Clockwise rotation an EXIF orientation asks for (mirrored variants are drawn unmirrored). */
        private fun rotationOf(exif: ExifInterface): Int = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }
}
