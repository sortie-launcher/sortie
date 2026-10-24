package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.markdown.MdBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderOutlineTest {
    @Test
    fun destinationsAccountForLeadAndNonHeadingBlocks() {
        val blocks = listOf(
            MdBlock.Paragraph("Introduction"),
            MdBlock.Heading(2, "Decisions"),
            MdBlock.Image("An example", "media/example.webp"),
            MdBlock.Paragraph("Explanation"),
            MdBlock.Heading(3, "Decisions")
        )
        assertEquals(
            listOf(ReaderSection(0, "Beginning", 1), ReaderSection(3, "Decisions", 2), ReaderSection(6, "Decisions", 3)),
            readerOutline(blocks, hasLead = true)
        )
        assertEquals(
            listOf(ReaderSection(0, "Beginning", 1), ReaderSection(2, "Decisions", 2), ReaderSection(5, "Decisions", 3)),
            readerOutline(blocks, hasLead = false)
        )
    }

    @Test
    fun articleWithoutHeadingsStillHasAStartDestination() {
        assertEquals(listOf(ReaderSection(0, "Beginning", 1)), readerOutline(listOf(MdBlock.Paragraph("Unsectioned article")), hasLead = true))
    }
}
