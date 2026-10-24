package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.markdown.MdBlock
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MagazineClippingTest {
    private val issue = MagazineIssue(
        id = "field-notes-001",
        title = "Field Notes",
        published = "2026-10-24",
        articles = listOf(
            MagazineArticle(
                id = "a-one",
                title = "Garage first",
                games = listOf(MagazineGameReference("ps2-armored-core-3", "Armored Core 3", "ps2")),
                body = "articles/a-one.md"
            )
        )
    )
    private val article = issue.articles[0]

    private val local = listOf("ps2/Armored Core 3 (USA).chd")

    private fun highlight(block: Int, text: String, note: String = "") = highlightClipping(issue, article, local, block, text, note, now = 1_000)

    /** Clippings keep the library files the references resolved to, never portable reference ids. */
    @Test
    fun clippingStoresResolvedLocalGameIds() {
        assertEquals(local, highlight(1, "Tune the legs.").gameIds)
        val twice = articleClipping(issue, article, local + local, listOf(MdBlock.Paragraph("Tune the legs.")), "", 2_000)
        assertEquals(local, twice.gameIds)
        assertEquals(emptyList<String>(), articleClipping(issue, article, emptyList(), emptyList(), "", 2_000).gameIds)
    }

    @Test
    fun passageIsFoundWhereItWasSaved() {
        val blocks = listOf(MdBlock.Heading(2, "Garage"), MdBlock.Paragraph("Tune the legs."), MdBlock.Paragraph("Then the arms."))
        assertEquals(2, highlight(2, "Then the arms.").locate(blocks))
    }

    /** The pack gained a paragraph above it: the same text one block further down, not whatever is at the old index. */
    @Test
    fun movedPassageIsFollowedByItsText() {
        val clip = highlight(1, "Tune the legs.")
        val blocks = listOf(MdBlock.Paragraph("New intro."), MdBlock.Paragraph("More intro."), MdBlock.Paragraph("Tune the legs."))
        assertEquals(2, clip.locate(blocks))
    }

    /** Edited or removed: nothing is found, so the reader never lands on a different paragraph. */
    @Test
    fun changedPassageIsNotFound() {
        val clip = highlight(1, "Tune the legs.")
        assertNull(clip.locate(listOf(MdBlock.Paragraph("Intro."), MdBlock.Paragraph("Tune the legs, then the arms."))))
        // a heading with the same words is not a passage
        assertNull(clip.locate(listOf(MdBlock.Heading(2, "Tune the legs."))))
    }

    @Test
    fun identicalCopiesResolveToTheNearestOne() {
        val clip = highlight(3, "Again.")
        val blocks = listOf(MdBlock.Paragraph("Again."), MdBlock.Rule, MdBlock.Rule, MdBlock.Rule, MdBlock.Quote("Again."))
        assertEquals(4, clip.locate(blocks))
    }

    @Test
    fun longPassageIsShortenedVisiblyAndStillFound() {
        val long = "x".repeat(MAX_CLIP_TEXT + 10)
        val clip = highlight(0, long)
        assertTrue(clip.shortened)
        assertEquals(MAX_CLIP_TEXT, clip.text.length)
        assertEquals(0, clip.locate(listOf(MdBlock.Paragraph(long))))
        // an ordinary clipping never matches by prefix
        assertNull(highlight(0, "Tune").locate(listOf(MdBlock.Paragraph("Tune the legs."))))
        assertFalse(highlight(0, "Tune").shortened)
    }

    /** The same passage saved from the other screen replaces the first clipping instead of duplicating it. */
    @Test
    fun savingTheSameSourceReplacesAndKeepsIdentity() {
        val first = highlight(1, "Tune the legs.", "first")
        val again = highlightClipping(issue, article, local, 1, "Tune the legs.", "second", now = 5_000)
        val list = listOf(first).withClipping(again)
        assertEquals(1, list.size)
        assertEquals(first.id, list[0].id)
        assertEquals(first.created, list[0].created)
        assertEquals("second", list[0].note)
        // a different passage and the article clipping are separate entries
        val more = list.withClipping(highlight(2, "Then the arms.")).withClipping(articleClipping(issue, article, local, emptyList(), "", 6_000))
        assertEquals(3, more.size)
        assertEquals(2, more.without(first.id).size)
    }

    /** A note is the user's own words: over the limit it is refused, never cut. */
    @Test
    fun overlongNoteIsRefusedNotTruncated() {
        val clip = highlight(1, "Tune the legs.", "n".repeat(MAX_CLIP_NOTE + 1))
        assertEquals(MAX_CLIP_NOTE + 1, clip.note.length)
        assertThrows(IllegalArgumentException::class.java) { emptyList<MagazineClipping>().withClipping(clip) }
        assertEquals(1, emptyList<MagazineClipping>().withClipping(clip.copy(note = "n".repeat(MAX_CLIP_NOTE))).size)
    }

    @Test
    fun olderAndNewerUserJsonStillDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(emptyList<MagazineClipping>(), json.decodeFromString(UserData.serializer(), """{"favourites":[]}""").magazineClippings)
        val minimal = """{"magazineClippings":[{"id":"c1","issueId":"i","articleId":"a","text":"Hi.","futureField":1}]}"""
        val clip = json.decodeFromString(UserData.serializer(), minimal).magazineClippings.single()
        assertEquals(CLIP_HIGHLIGHT, clip.kind)
        assertFalse(clip.shortened)
        assertEquals(0, clip.locate(listOf(MdBlock.Paragraph("Hi."))))
    }
}
