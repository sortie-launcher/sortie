package io.github.esko997.sortie.data.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun manualSubsetParsesAsBefore() {
        val md = """
            # Sortie
            Intro line one
            continues here.

            ## Controls
            - **A** opens
              the focused tile
            - *B* goes back
            #hashtag stays text
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Sortie"),
                MdBlock.Paragraph("Intro line one continues here."),
                MdBlock.Heading(2, "Controls"),
                MdBlock.Bullet("**A** opens the focused tile"),
                MdBlock.Bullet("*B* goes back"),
                MdBlock.Paragraph("#hashtag stays text")
            ),
            parseMarkdown(md)
        )
    }

    @Test
    fun magazineBlocks() {
        val md = "\uFEFF### Small heading\n#### Deeper reads as level 3\n> A quoted\n> thought\n>\n> Second quote\nAfter quote\n\n" +
            "![Garage screen, credit: publisher](media/garage.webp)\n![](media/b.webp \"Title caption\")\nText with ![inline](media/x.webp) image\n---\n"
        assertEquals(
            listOf(
                MdBlock.Heading(3, "Small heading"),
                MdBlock.Heading(3, "Deeper reads as level 3"),
                MdBlock.Quote("A quoted thought"),
                MdBlock.Quote("Second quote"),
                MdBlock.Paragraph("After quote"),
                MdBlock.Image("Garage screen, credit: publisher", "media/garage.webp"),
                MdBlock.Image("Title caption", "media/b.webp"),
                MdBlock.Paragraph("Text with ![inline](media/x.webp) image"),
                MdBlock.Rule
            ),
            parseMarkdown(md)
        )
    }

    @Test
    fun htmlStaysLiteralText() {
        assertEquals(listOf(MdBlock.Paragraph("<script>alert(1)</script> <img src=x>")), parseMarkdown("<script>alert(1)</script>\n<img src=x>"))
    }

    @Test
    fun inlineRuns() {
        assertEquals(
            listOf(
                InlineRun("See "),
                InlineRun("GameSpot review", link = "https://example.com/r"),
                InlineRun(", "),
                InlineRun("2002", bold = true),
                InlineRun(" and "),
                InlineRun("aside", italic = true)
            ),
            parseInline("See [GameSpot review](https://example.com/r), **2002** and *aside*")
        )
        assertEquals(listOf(InlineRun("plain 5 * 3")), parseInline("plain 5 * 3"))
    }
}
