package io.github.esko997.sortie.data.reference

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

/**
 * One of the user's own reference cards for a game (`user.referenceCards[gameId]`): a titled Markdown note, or an imported image
 * (a map, move list, chart) with optional Markdown notes in [body]. Written or imported on the device; nothing is downloaded.
 */
@Serializable
data class ReferenceCard(
    /** Stable random id; also names the card's image file. */
    val id: String,
    val title: String = "",
    /** Markdown in Sortie's small subset (see `data.markdown`); for an image card, its caption/notes. */
    val body: String = "",
    /**
     * The image attachment, relative to the Sortie directory (`reference/<game key>/<card id>.<ext>`, see [ReferenceFiles]); null
     * for a text card. A path that does not match that shape is never opened.
     */
    val image: String? = null,
    /** Display name of the file the card was imported from; shown for orientation only, never reopened. */
    val source: String = "",
    /** Epoch millis. */
    val created: Long = 0,
    val updated: Long = 0
)

/** Bounds that keep user.json small and imports cheap to decode. */
object ReferenceLimits {
    const val MAX_TITLE = 120

    /** Characters of Markdown per card; user.json is rewritten on every preference change, so cards stay note-sized. */
    const val MAX_BODY = 100_000

    /** A Markdown import larger than this is refused rather than truncated. */
    const val MAX_MARKDOWN_BYTES = 256 * 1024
    const val MAX_IMAGE_BYTES = 24L * 1024 * 1024
    const val MAX_IMAGE_EDGE = 16_384
    const val MAX_IMAGE_PIXELS = 64_000_000L
    const val MAX_CARDS_PER_GAME = 200

    /** Decoded for display: at most this many pixels (a 64 MB hardware bitmap) and this long an edge (GPU texture limit). */
    const val DISPLAY_MAX_PIXELS = 16_000_000L
    const val DISPLAY_MAX_EDGE = 8_192
}

/** Where image attachments live under the Sortie directory, and the only paths that are ever opened. */
object ReferenceFiles {
    const val DIR = "reference"

    /** Extensions by the decoder's MIME type; anything else is refused at import. */
    val EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "image/gif" to "gif")

    private val CARD_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val PATH = Regex("""$DIR/[0-9a-f]{20}/[A-Za-z0-9-]{1,64}\.(png|jpg|webp|gif)""")

    fun isCardId(id: String): Boolean = CARD_ID.matches(id)

    /** A filesystem-safe directory name for [gameId] (game ids are paths: `snes/Some Game (USA).sfc`). */
    fun gameKey(gameId: String): String = MessageDigest.getInstance("SHA-1").digest(gameId.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun imagePath(gameId: String, cardId: String, ext: String): String {
        require(isCardId(cardId) && ext in EXTENSIONS.values) { "unsafe reference image name" }
        return "$DIR/${gameKey(gameId)}/$cardId.$ext"
    }

    /**
     * The attachment file for [relative] under [sortieDir], or null when the path is not one Sortie writes (hand-edited or
     * restored user.json) or any part of it below the Sortie directory is a symlink (`reference/` itself included).
     */
    fun resolve(sortieDir: File, relative: String?): File? {
        if (relative == null || !PATH.matches(relative)) return null
        return contained(sortieDir, relative)
    }

    /**
     * [gameId]'s attachment directory, created if needed, or null when it would not be a real directory inside the Sortie
     * directory (a symlinked `reference/` or game folder). Checked before creating anything, and again after.
     */
    fun directory(sortieDir: File, gameId: String): File? {
        val relative = "$DIR/${gameKey(gameId)}"
        val dir = contained(sortieDir, relative) ?: return null
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return contained(sortieDir, relative)?.takeIf { it.isDirectory }
    }

    /**
     * `canonical(sortieDir)/relative` when resolving it changes nothing, i.e. no component of [relative] is a symlink or `..`.
     * [relative] must already be one of the fixed shapes above.
     */
    private fun contained(sortieDir: File, relative: String): File? = runCatching {
        val expected = File(sortieDir.canonicalFile, relative)
        expected.takeIf { it.canonicalFile == it }
    }.getOrNull()
}

/** Pure list edits and lookups shared by the store and both screens. */
object ReferenceCards {
    /** [card] replacing the card with its id, or appended. */
    fun upsert(cards: List<ReferenceCard>, card: ReferenceCard): List<ReferenceCard> {
        val i = cards.indexOfFirst { it.id == card.id }
        return if (i < 0) cards + card else cards.toMutableList().also { it[i] = card }
    }

    /** The map with [gameId]'s list replaced; an empty list removes the entry. */
    fun withCards(all: Map<String, List<ReferenceCard>>, gameId: String, cards: List<ReferenceCard>): Map<String, List<ReferenceCard>> =
        if (cards.isEmpty()) all - gameId else all + (gameId to cards)

    /** The card [step] places from [currentId], wrapping around; the first card when [currentId] is gone; null for none. */
    fun neighbour(cards: List<ReferenceCard>, currentId: String?, step: Int): ReferenceCard? {
        if (cards.isEmpty()) return null
        val i = cards.indexOfFirst { it.id == currentId }
        if (i < 0) return cards.first()
        return cards[(i + step).mod(cards.size)]
    }

    /** The first heading of [markdown] (any level), else [fallback] without its extension; clipped to [ReferenceLimits.MAX_TITLE]. */
    fun titleFor(markdown: String, fallback: String): String {
        val heading = markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") }
            ?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() }
        val name = fallback.substringAfterLast('/').let { n -> if ('.' in n) n.substringBeforeLast('.') else n }
        return (heading ?: name.ifBlank { "Untitled" }).take(ReferenceLimits.MAX_TITLE)
    }

    /**
     * [bytes] as Markdown text, or null when they are not text Sortie can show: invalid UTF-8 or containing NUL (a binary file
     * picked by mistake). A byte-order mark is dropped.
     */
    fun decodeMarkdown(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
        val text = runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
        if ('\u0000' in text) return null
        return text.removePrefix("\uFEFF").replace("\r\n", "\n")
    }

    /**
     * Decode size for showing a [width]x[height] image: the original when it fits [ReferenceLimits.DISPLAY_MAX_PIXELS] and
     * [ReferenceLimits.DISPLAY_MAX_EDGE], else scaled down uniformly until it does. Long maps keep their full width as far as
     * the pixel budget allows instead of being shrunk to a fixed edge.
     */
    fun displaySize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val byPixels = kotlin.math.sqrt(ReferenceLimits.DISPLAY_MAX_PIXELS.toDouble() / (width.toLong() * height))
        val byEdge = ReferenceLimits.DISPLAY_MAX_EDGE.toDouble() / maxOf(width, height)
        val scale = minOf(1.0, byPixels, byEdge)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
