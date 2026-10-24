package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.markdown.MdBlock
import io.github.esko997.sortie.data.user.UserStore
import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.Serializable

/** A highlighted paragraph (bullet or quote) of an article, with [MagazineClipping.text] its exact text. */
const val CLIP_HIGHLIGHT = "highlight"

/** A whole article clipped as a recommendation; [MagazineClipping.text] is its first paragraph, for reading without the pack. */
const val CLIP_ARTICLE = "article"

/** A longer passage is kept as its first this-many characters, flagged [MagazineClipping.shortened] (and found by prefix). */
const val MAX_CLIP_TEXT = 20_000

/** Notes are the user's own words: a longer one is refused ([withClipping] throws), never cut. Editors say so first. */
const val MAX_CLIP_NOTE = 4_000

/**
 * A personal clipping from an offline magazine, kept in user.json (`magazineClippings`, so it is in backups); the packs stay
 * read-only. Everything shown in the scrapbook is copied in when the clipping is made, so it outlives the issue being changed
 * or removed from the card. [issueId]/[articleId] are the pack's stable ids, used to reopen the source; [block] is the
 * paragraph's index in the parsed article when it was clipped, a hint only: the paragraph is found again by its [text]
 * ([locate]) and never by the index alone. Unknown [kind]s (a newer build's) read as highlights when they carry text.
 */
@Serializable
data class MagazineClipping(
    val id: String,
    val kind: String = CLIP_HIGHLIGHT,
    val issueId: String,
    val articleId: String,
    val issueTitle: String = "",
    /** The issue's `published` as written in the pack. */
    val issuePublished: String = "",
    val articleTitle: String = "",
    val gameTitle: String = "",
    val system: String = "",
    /** Local Sortie game ids the article's references resolved to when clipped; opened through the library page, which says when a game is not indexed. */
    val gameIds: List<String> = emptyList(),
    val deck: String = "",
    val block: Int = -1,
    /** Inline Markdown as in the pack (`**bold**`, `*italic*`, `[text](url)`); at most [MAX_CLIP_TEXT] characters. */
    val text: String = "",
    /** [text] is only the beginning of a longer passage; shown as such, and the passage is found again by that beginning. */
    val shortened: Boolean = false,
    val note: String = "",
    /** Epoch milliseconds. */
    val created: Long = 0,
    val updated: Long = 0
) {
    val isArticle: Boolean get() = kind == CLIP_ARTICLE

    /** Where this clipping's paragraph is in [blocks] now, or null when it is not there (any more). */
    fun locate(blocks: List<MdBlock>): Int? {
        if (isArticle || text.isEmpty()) return null
        fun matches(b: MdBlock): Boolean {
            val t = b.clipText() ?: return false
            return if (shortened) t.startsWith(text) else t == text
        }
        if (blocks.getOrNull(block)?.let(::matches) == true) return block
        // moved (the pack gained or lost paragraphs above it): the same text elsewhere; identical copies are interchangeable
        return blocks.indices.filter { matches(blocks[it]) }.minByOrNull { abs(it - block) }
    }

    /** The same passage or article as [other], whatever their ids: saving one over the other replaces it. */
    fun sameSource(other: MagazineClipping): Boolean =
        isArticle == other.isArticle && issueId == other.issueId && articleId == other.articleId && (isArticle || text == other.text)
}

/** The text a reader block can be highlighted by: paragraphs, bullets and quotes; null for headings, images and rules. */
fun MdBlock.clipText(): String? = when (this) {
    is MdBlock.Paragraph -> text
    is MdBlock.Bullet -> text
    is MdBlock.Quote -> text
    else -> null
}

/** [gameIds]: the local library ids the article's game references resolved to (never portable reference ids). */
fun highlightClipping(
    issue: MagazineIssue,
    article: MagazineArticle,
    gameIds: List<String>,
    block: Int,
    text: String,
    note: String,
    now: Long
): MagazineClipping = newClipping(CLIP_HIGHLIGHT, issue, article, gameIds, block, text, note, now)

/** [blocks]: the article's parsed text when it could be read (its first paragraph is kept), else empty. [gameIds] as for [highlightClipping]. */
fun articleClipping(issue: MagazineIssue, article: MagazineArticle, gameIds: List<String>, blocks: List<MdBlock>, note: String, now: Long): MagazineClipping {
    val first = blocks.indexOfFirst { it is MdBlock.Paragraph }
    return newClipping(CLIP_ARTICLE, issue, article, gameIds, first, if (first >= 0) blocks[first].clipText()!! else "", note, now)
}

private fun newClipping(
    kind: String,
    issue: MagazineIssue,
    article: MagazineArticle,
    gameIds: List<String>,
    block: Int,
    text: String,
    note: String,
    now: Long
) = MagazineClipping(
    id = UUID.randomUUID().toString(),
    kind = kind,
    issueId = issue.id,
    articleId = article.id,
    issueTitle = issue.title,
    issuePublished = issue.published,
    articleTitle = article.title,
    gameTitle = article.gameTitle,
    system = article.system,
    gameIds = gameIds.distinct(),
    deck = article.deck,
    block = block,
    text = text.take(MAX_CLIP_TEXT),
    shortened = text.length > MAX_CLIP_TEXT,
    note = note.trim(),
    created = now,
    updated = now
)

/**
 * [clip] saved into the list: it replaces the entry with its id, or — made on the other screen or before the list was
 * refreshed — the entry for the same source, keeping that entry's id and creation time. Otherwise it is appended.
 * A note longer than [MAX_CLIP_NOTE] (after trimming) is refused with [IllegalArgumentException], nothing changed.
 */
fun List<MagazineClipping>.withClipping(clip: MagazineClipping): List<MagazineClipping> {
    val c = clip.copy(note = clip.note.trim())
    require(c.note.length <= MAX_CLIP_NOTE) { "the note is ${c.note.length} characters; notes hold up to $MAX_CLIP_NOTE" }
    val i = indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: indexOfFirst { it.sameSource(c) }
    if (i < 0) return this + c
    val old = this[i]
    return toMutableList().also { it[i] = c.copy(id = old.id, created = old.created) }
}

fun List<MagazineClipping>.without(id: String): List<MagazineClipping> = filterNot { it.id == id }

/** The article's own clipping, if it was clipped. */
fun List<MagazineClipping>.articleClip(issueId: String, articleId: String): MagazineClipping? =
    lastOrNull { it.isArticle && it.issueId == issueId && it.articleId == articleId }

/** The article's highlights by the block they are on now; a highlight whose text is no longer there is left out. */
fun List<MagazineClipping>.highlightsIn(issueId: String, articleId: String, blocks: List<MdBlock>): Map<Int, MagazineClipping> {
    val out = HashMap<Int, MagazineClipping>()
    for (c in this) {
        if (c.isArticle || c.issueId != issueId || c.articleId != articleId) continue
        c.locate(blocks)?.let { out[it] = c }
    }
    return out
}

/** Whether a clipping's source is still on the card, as far as the shelf tells (a paragraph is checked by the reader). */
enum class ClipSource { AVAILABLE, ARTICLE_MISSING, ISSUE_MISSING }

fun MagazineClipping.source(shelf: MagazineShelf): ClipSource {
    val issue = shelf.issue(issueId) ?: return ClipSource.ISSUE_MISSING
    return if (issue.issue.article(articleId) == null) ClipSource.ARTICLE_MISSING else ClipSource.AVAILABLE
}

/** Saves [clip] durably (see [UserStore.commit]); throws the write failure with nothing changed. */
suspend fun UserStore.saveClipping(clip: MagazineClipping): UserData = commit { it.copy(magazineClippings = it.magazineClippings.withClipping(clip)) }

suspend fun UserStore.removeClipping(id: String): UserData = commit { it.copy(magazineClippings = it.magazineClippings.without(id)) }
