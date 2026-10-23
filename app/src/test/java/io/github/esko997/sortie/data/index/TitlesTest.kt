package io.github.esko997.sortie.data.index

import org.junit.Assert.assertEquals
import org.junit.Test

class TitlesTest {
    @Test
    fun stripsRegionsTagsVersionsAndRevisions() {
        assertEquals("Chrono Trigger", cleanTitle("Chrono Trigger (USA)"))
        assertEquals("Metal Gear Solid", cleanTitle("Metal Gear Solid (USA) (Disc 1) (Rev 1)"))
        assertEquals("Sonic the Hedgehog", cleanTitle("Sonic the Hedgehog (World) (Rev A) [!]"))
        assertEquals("Animal Crossing - Wild World", cleanTitle("Animal Crossing - Wild World (USA) v1.1.1"))
        assertEquals("Mega Man X", cleanTitle("Mega Man X (USA) Rev1"))
    }

    @Test
    fun keepsWordsThatStartWithRev() {
        assertEquals("The Revenge of Shinobi", cleanTitle("The Revenge of Shinobi (USA)"))
        assertEquals("Resident Evil Revelations", cleanTitle("Resident Evil Revelations (Europe)"))
        assertEquals("Dance Dance Revolution", cleanTitle("Dance Dance Revolution"))
        assertEquals("Rev Limit", cleanTitle("Rev Limit (Japan)"))
    }

    @Test
    fun neverReturnsEmpty() {
        assertEquals("(USA)", cleanTitle("(USA)"))
    }
}
