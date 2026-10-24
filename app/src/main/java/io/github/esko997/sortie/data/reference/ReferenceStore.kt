package io.github.esko997.sortie.data.reference

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.user.UserStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A card operation that was refused or failed; [message] is shown to the user as is. */
class ReferenceException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A file the user picked: the picker's display name and declared size (may be absent or wrong), and how to read it. */
class PickedDocument(val name: String, val declaredSize: Long?, val open: () -> InputStream) {
    companion object {
        /** Queries the content resolver; call off the main thread. */
        fun from(context: Context, uri: Uri): PickedDocument {
            val resolver = context.contentResolver
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        size = if (c.isNull(1)) null else c.getLong(1)
                    }
                }
            }
            val shown = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "the file"
            return PickedDocument(shown, size) { resolver.openInputStream(uri) ?: throw ReferenceException("The storage app returned no file.") }
        }
    }
}

/** What an image decoder reports without decoding pixels; [mimeType] null when the bytes are not an image it knows. */
data class ImageBounds(val width: Int, val height: Int, val mimeType: String?)

/** [ImageBounds] from the platform decoder's header pass. */
fun decodeImageBounds(file: File): ImageBounds {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, o)
    return ImageBounds(o.outWidth, o.outHeight, o.outMimeType)
}

/**
 * Durable edits of `user.referenceCards`: every operation returns once user.json holds the result (see [UserStore.commit]) and
 * throws otherwise, with nothing published; a commit that started finishes even if the caller is cancelled. Images are copied
 * into `<sortieDir>/reference/<game key>/<card id>.<ext>` (inside the real Sortie directory, see [ReferenceFiles.directory])
 * before the card that points at them is saved, and removed again when that save fails; a deleted card's image is removed
 * after the card is gone. [probe] reads an image's header (replaceable for tests).
 */
class ReferenceStore(
    private val user: UserStore,
    private val sortieDir: File,
    private val probe: (File) -> ImageBounds = ::decodeImageBounds
) {

    /**
     * Saves a new text card or an edit of an existing card for [gameId]. A card's image is fixed at import: an edit keeps the
     * stored image whatever [card] says, and an image card that is no longer stored (deleted meanwhile; its file is gone) is
     * refused rather than brought back. Title and body over the limits are refused, not clipped.
     */
    suspend fun save(gameId: String, card: ReferenceCard): ReferenceCard = put(gameId, card, newImage = false)

    /**
     * [newImage]: [card] is a fresh import whose image file was just placed; it must not exist yet. Otherwise an absent card is
     * created only when it has no image.
     */
    private suspend fun put(gameId: String, card: ReferenceCard, newImage: Boolean): ReferenceCard {
        if (card.title.length > ReferenceLimits.MAX_TITLE) throw ReferenceException("The title is longer than ${ReferenceLimits.MAX_TITLE} characters.")
        if (card.body.length > ReferenceLimits.MAX_BODY) throw ReferenceException("The card is longer than ${ReferenceLimits.MAX_BODY} characters.")
        if (!ReferenceFiles.isCardId(card.id)) throw ReferenceException("This card has an invalid id.")
        val now = System.currentTimeMillis()
        var saved = card
        commit { u ->
            val list = u.referenceCards[gameId].orEmpty()
            val existing = list.firstOrNull { it.id == card.id }
            when {
                newImage && existing != null -> throw ReferenceException("A card with this id already exists.")
                !newImage && existing == null && card.image != null -> throw ReferenceException("This card was deleted; its image is gone.")
                existing == null && list.size >= ReferenceLimits.MAX_CARDS_PER_GAME ->
                    throw ReferenceException("A game holds at most ${ReferenceLimits.MAX_CARDS_PER_GAME} cards.")
            }
            saved = card.copy(
                title = card.title.trim().ifEmpty { "Untitled" },
                image = if (existing != null) existing.image else card.image,
                source = existing?.source ?: card.source,
                created = existing?.created ?: card.created.takeIf { it > 0 } ?: now,
                updated = now
            )
            u.copy(referenceCards = ReferenceCards.withCards(u.referenceCards, gameId, ReferenceCards.upsert(list, saved)))
        }
        return saved
    }

    /** Removes the card, then its image file (a file that cannot be removed only wastes space and is logged). */
    suspend fun delete(gameId: String, cardId: String) {
        var removed: ReferenceCard? = null
        commit { u ->
            val list = u.referenceCards[gameId].orEmpty()
            removed = list.firstOrNull { it.id == cardId }
            u.copy(referenceCards = ReferenceCards.withCards(u.referenceCards, gameId, list.filterNot { it.id == cardId }))
        }
        val file = ReferenceFiles.resolve(sortieDir, removed?.image) ?: return
        withContext(Dispatchers.IO) { if (file.exists() && !file.delete()) Log.w(TAG, "could not delete $file") }
    }

    /** A text card from a Markdown/plain-text document, titled by its first heading or file name. */
    suspend fun importMarkdown(gameId: String, document: PickedDocument): ReferenceCard {
        val name = document.name
        val bytes = withContext(Dispatchers.IO) {
            val size = document.declaredSize
            if (size != null && size > ReferenceLimits.MAX_MARKDOWN_BYTES) throw tooLarge(name, size, ReferenceLimits.MAX_MARKDOWN_BYTES.toLong())
            readFailure(name) {
                document.open().use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > ReferenceLimits.MAX_MARKDOWN_BYTES) throw tooLarge(name, null, ReferenceLimits.MAX_MARKDOWN_BYTES.toLong())
                    }
                    out.toByteArray()
                }
            }
        }
        val text = ReferenceCards.decodeMarkdown(bytes) ?: throw ReferenceException("$name is not a UTF-8 text file.")
        if (text.length > ReferenceLimits.MAX_BODY) {
            throw ReferenceException("$name has ${text.length} characters; a card holds up to ${ReferenceLimits.MAX_BODY}. Split it into several files.")
        }
        if (text.isBlank()) throw ReferenceException("$name is empty.")
        return save(gameId, ReferenceCard(id = newId(), title = ReferenceCards.titleFor(text, name), body = text, source = name))
    }

    /**
     * An image card from a picked PNG/JPEG/WebP/GIF: copied (size-capped, fsynced) under a temporary name, checked by its
     * header, renamed into place, then saved. Any failure removes the copy. Runs to completion once started, so leaving the
     * screen cannot separate a saved card from its file.
     */
    suspend fun importImage(gameId: String, document: PickedDocument): ReferenceCard = withContext(NonCancellable + Dispatchers.IO) {
        val id = newId()
        val target = copyImage(gameId, id, document)
        val card = ReferenceCard(
            id = id,
            title = ReferenceCards.titleFor("", document.name),
            image = ReferenceFiles.imagePath(gameId, id, target.extension),
            source = document.name
        )
        try {
            put(gameId, card, newImage = true)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    /** The placed file, already inside the verified reference directory. */
    private fun copyImage(gameId: String, id: String, document: PickedDocument): File {
        val name = document.name
        val size = document.declaredSize
        if (size != null && size > ReferenceLimits.MAX_IMAGE_BYTES) throw tooLarge(name, size, ReferenceLimits.MAX_IMAGE_BYTES)
        val dir = ReferenceFiles.directory(sortieDir, gameId)
            ?: throw ReferenceException("The reference folder is not inside ${sortieDir.path}; nothing was copied.")
        sweepTemporaries(dir)
        val tmp = File(dir, ".$id.tmp")
        try {
            readFailure(name) {
                document.open().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > ReferenceLimits.MAX_IMAGE_BYTES) throw tooLarge(name, null, ReferenceLimits.MAX_IMAGE_BYTES)
                            out.write(buf, 0, n)
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            }
            val bounds = probe(tmp)
            val ext = ReferenceFiles.EXTENSIONS[bounds.mimeType]
            if (bounds.width <= 0 || bounds.height <= 0 || ext == null) throw ReferenceException("$name is not a PNG, JPEG, WebP or GIF image.")
            if (maxOf(bounds.width, bounds.height) > ReferenceLimits.MAX_IMAGE_EDGE ||
                bounds.width.toLong() * bounds.height > ReferenceLimits.MAX_IMAGE_PIXELS
            ) {
                throw ReferenceException(
                    "$name is ${bounds.width}×${bounds.height}; images up to ${ReferenceLimits.MAX_IMAGE_EDGE} px a side and " +
                        "${ReferenceLimits.MAX_IMAGE_PIXELS / 1_000_000} megapixels are accepted."
                )
            }
            val target = File(dir, "$id.$ext")
            if (!tmp.renameTo(target)) throw ReferenceException("Could not store the image in ${dir.path}.")
            return target
        } finally {
            tmp.delete()
        }
    }

    /** Runs [block], turning an I/O failure into a [ReferenceException] that names the file. */
    private inline fun <T> readFailure(name: String, block: () -> T): T = try {
        block()
    } catch (e: ReferenceException) {
        throw e
    } catch (e: IOException) {
        throw ReferenceException("Could not read $name: ${e.message ?: e.javaClass.simpleName}", e)
    } catch (e: SecurityException) {
        throw ReferenceException("Sortie may not read $name: ${e.message ?: e.javaClass.simpleName}", e)
    }

    /** Leftovers of an import interrupted by a crash or kill, older than an hour (a concurrent import's copy is younger). */
    private fun sweepTemporaries(dir: File) {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir.listFiles { f -> f.name.startsWith(".") && f.name.endsWith(".tmp") && f.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Completes even when the caller is cancelled: the outcome on the card is then what the next read shows. */
    private suspend fun commit(f: (UserData) -> UserData) = withContext(NonCancellable) {
        try {
            user.commit(f)
        } catch (e: ReferenceException) {
            throw e
        } catch (e: Exception) {
            throw ReferenceException("Not saved to the card: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun tooLarge(name: String, size: Long?, limit: Long) = ReferenceException(
        "$name is too large" + (size?.let { " (${kib(it)})" } ?: "") + "; the limit is ${kib(limit)}."
    )

    private fun kib(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0) else "${(bytes + 1023) / 1024} KB"

    companion object {
        private const val TAG = "ReferenceStore"

        fun newId(): String = UUID.randomUUID().toString()
    }
}
