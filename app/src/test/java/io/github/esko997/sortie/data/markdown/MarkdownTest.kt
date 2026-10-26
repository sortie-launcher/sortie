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
    fun consecutiveSequenceImagesFormOneSequenceAndAnythingElseEndsIt() {
        val md = """
            Before
            ![First, credit: A](media/1.webp "sequence")
            ![Second, credit: B](media/2.webp "sequence")
            ![Third](media/3.webp "sequence")

            ![Next run one](media/4.webp "sequence")
            ![Next run two](media/5.webp "sequence")
            ![Plain picture](media/6.webp)
            ![](media/7.webp "sequence")
            After
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Paragraph("Before"),
                MdBlock.Sequence(
                    listOf(
                        MdBlock.Image("First, credit: A", "media/1.webp"),
                        MdBlock.Image("Second, credit: B", "media/2.webp"),
                        MdBlock.Image("Third", "media/3.webp")
                    )
                ),
                // a blank line starts a new sequence
                MdBlock.Sequence(listOf(MdBlock.Image("Next run one", "media/4.webp"), MdBlock.Image("Next run two", "media/5.webp"))),
                MdBlock.Image("Plain picture", "media/6.webp"),
                // a lone panel is an ordinary picture, and the marker title is never its caption
                MdBlock.Image("", "media/7.webp"),
                MdBlock.Paragraph("After")
            ),
            parseMarkdown(md)
        )
        assertEquals(listOf("media/1.webp", "media/2.webp", "media/3.webp"), parseMarkdown(md)[1].images.map { it.src })
    }

    @Test
    fun otherImageTitlesStayCaptionsAndNeverGroup() {
        assertEquals(
            listOf(MdBlock.Image("", "media/a.webp"), MdBlock.Image("Sequence", "media/b.webp")),
            parseMarkdown("![](media/a.webp \"sequence\")\n![](media/b.webp \"Sequence\")")
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
