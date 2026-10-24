package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.markdown.MdBlock

/** A destination in the article's LazyColumn, including its optional lead image and title item. */
data class ReaderSection(val itemIndex: Int, val title: String, val level: Int)

fun readerOutline(blocks: List<MdBlock>, hasLead: Boolean): List<ReaderSection> = buildList {
    add(ReaderSection(0, "Beginning", 1))
    val bodyStart = if (hasLead) 2 else 1
    blocks.forEachIndexed { index, block ->
        if (block is MdBlock.Heading) add(ReaderSection(bodyStart + index, block.text, block.level))
    }
}
