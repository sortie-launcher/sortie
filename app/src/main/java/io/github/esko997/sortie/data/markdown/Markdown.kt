package io.github.esko997.sortie.data.markdown

/**
 * The deliberately small Markdown subset Sortie renders (the bundled manual and offline magazine articles): `#`/`##`/`###`
 * headings (deeper levels read as `###`), paragraphs joined across soft line breaks, `- ` bullets (an indented line continues the
 * bullet), `>` block quotes, standalone `![caption](path)` image lines, `---` rules, and inline `**bold**`, `*italic*` and
 * `[text](url)`. HTML is never interpreted; it stays literal text. Image paths are returned as written: whoever renders them
 * decides what a path may point at.
 *
 * Two or more image lines in a row (no blank line between) whose title is exactly `sequence` form one [MdBlock.Sequence]:
 * `![First caption](media/a.webp "sequence")` then `![Second caption](media/b.webp "sequence")`. A blank line or any other line
 * ends it; a lone `sequence` image is an ordinary [MdBlock.Image]. Readers without sequences show the same lines as
 * separate captioned images, so the syntax stays readable everywhere.
 */
sealed interface MdBlock {
    /** [level] 1..3. Heading text is shown verbatim (no inline markup). */
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    data class Bullet(val text: String) : MdBlock

    data class Quote(val text: String) : MdBlock

    /** A line that is only an image; [caption] is the alt text (or the optional title when the alt text is blank). */
    data class Image(val caption: String, val src: String) : MdBlock

    /** Consecutive `sequence` image lines, in order (at least two): one horizontal run of captioned panels. */
    data class Sequence(val panels: List<Image>) : MdBlock

    data object Rule : MdBlock
}

/** The pictures a block shows: an image line's one, a sequence's panels in order, none for text. */
val MdBlock.images: List<MdBlock.Image>
    get() = when (this) {
        is MdBlock.Image -> listOf(this)
        is MdBlock.Sequence -> panels
        else -> emptyList()
    }

/** One inline span: plain, bold or italic text, or a [link] whose target is attribution only (Sortie never opens it). */
data class InlineRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val IMAGE = Regex("""^!\[([^\]]*)]\(\s*([^)\s]+)(?:\s+"([^"]*)")?\s*\)$""")

/** The image title that marks a line as a panel of a [MdBlock.Sequence]. */
const val SEQUENCE_TITLE = "sequence"
private val RULE = Regex("""^(-{3,}|\*{3,}|_{3,})$""")
private val INLINE = Regex("""\[([^\]\n]+)]\(([^)\s]+)\)|\*\*(.+?)\*\*|\*(.+?)\*""")

fun parseMarkdown(md: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val para = StringBuilder()
    val quote = StringBuilder()
    val panels = ArrayList<MdBlock.Image>()
    fun endSequence() {
        when (panels.size) {
            0 -> Unit
            1 -> out += panels[0]
            else -> out += MdBlock.Sequence(panels.toList())
        }
        panels.clear()
    }
    fun flush() {
        endSequence()
        if (para.isNotEmpty()) out += MdBlock.Paragraph(para.toString())
        para.clear()
        if (quote.isNotEmpty()) out += MdBlock.Quote(quote.toString())
        quote.clear()
    }
    for (raw in md.removePrefix("\uFEFF").lines()) {
        val line = raw.trim()
        val heading = HEADING.matchEntire(line)
        val image = IMAGE.matchEntire(line)
        val panel = image != null && image.groupValues[3] == SEQUENCE_TITLE
        if (!panel) endSequence()
        when {
            line.isEmpty() -> flush()
            panel -> {
                if (panels.isEmpty()) flush()
                // a panel's caption is its alt text only: the title is the marker, never a caption
                panels += MdBlock.Image(image!!.groupValues[1].trim(), image.groupValues[2])
            }
            heading != null -> {
                flush()
                out += MdBlock.Heading(heading.groupValues[1].length.coerceAtMost(3), heading.groupValues[2].trim())
            }
            line.startsWith(">") -> {
                if (para.isNotEmpty()) flush()
                val text = line.removePrefix(">").trim()
                if (text.isEmpty()) {
                    flush() // a bare ">" separates two quoted paragraphs
                } else {
                    if (quote.isNotEmpty()) quote.append(' ')
                    quote.append(text)
                }
            }
            image != null -> {
                flush()
                val caption = image.groupValues[1].trim().ifEmpty { image.groupValues[3].trim() }
                out += MdBlock.Image(caption, image.groupValues[2])
            }
            RULE.matches(line) -> {
                flush()
                out += MdBlock.Rule
            }
            line.startsWith("- ") -> {
                flush()
                out += MdBlock.Bullet(line.removePrefix("- ").trim())
            }
            out.lastOrNull() is MdBlock.Bullet && para.isEmpty() && quote.isEmpty() && raw.startsWith("  ") -> // wrapped bullet continuation
                out[out.lastIndex] = MdBlock.Bullet((out.last() as MdBlock.Bullet).text + " " + line)
            else -> {
                if (quote.isNotEmpty()) flush()
                if (para.isNotEmpty()) para.append(' ')
                para.append(line)
            }
        }
    }
    flush()
    return out
}

/** `[text](url)`, `**bold**` and `*italic*` spans; anything else verbatim. Spans do not nest. */
fun parseInline(text: String): List<InlineRun> {
    val out = ArrayList<InlineRun>()
    var i = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first > i) out += InlineRun(text.substring(i, m.range.first))
        val g = m.groups
        out += when {
            g[1] != null -> InlineRun(g[1]!!.value, link = g[2]!!.value)
            g[3] != null -> InlineRun(g[3]!!.value, bold = true)
            else -> InlineRun(g[4]!!.value, italic = true)
        }
        i = m.range.last + 1
    }
    if (i < text.length) out += InlineRun(text.substring(i))
    return out
}
