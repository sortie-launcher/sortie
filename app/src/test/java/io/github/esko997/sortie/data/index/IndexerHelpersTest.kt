package io.github.esko997.sortie.data.index

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexerHelpersTest {
    @Test
    fun `cleanTitle strips region and revision tags`() {
        assertEquals("Armored Core 2", Indexer.cleanTitle("Armored Core 2 (USA) (True Analogs v1.0)"))
        assertEquals("Shiren the Wanderer", Indexer.cleanTitle("Shiren the Wanderer (USA)[vita3k]"))
    }

    @Test
    fun `cleanTitle moves a trailing The to the front`() {
        assertEquals("The Legend of Zelda - Spirit Tracks", Indexer.cleanTitle("Legend of Zelda, The - Spirit Tracks (Dpad Patched) (QoL)"))
    }

    @Test
    fun `cleanTitle never returns blank`() {
        assertEquals("(USA)", Indexer.cleanTitle("(USA)"))
    }

    @Test
    fun `sortKey ignores leading articles and case`() {
        assertEquals("legend of zelda", Indexer.sortKey("The Legend of Zelda"))
        assertEquals("boy and his blob", Indexer.sortKey("A Boy and His Blob"))
    }
}
