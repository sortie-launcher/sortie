package io.github.esko997.sortie.data.cocoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CocoonAliasesTest {
    @Test
    fun `retroarch players name a core profile per build`() {
        assertEquals(CocoonPlayer("ra-mgba", "mgba", "com.retroarch"), CocoonAliases.player("gba.ra.mgba"))
        assertEquals(CocoonPlayer("ra64-gambatte", "gambatte", "com.retroarch.aarch64"), CocoonAliases.player("gbc.ra64.gambatte"))
        assertEquals(CocoonPlayer("ra-mame", "mame", "com.retroarch"), CocoonAliases.player("mame.ra.mamearcade"))
        assertEquals(CocoonPlayer("ra-mame", "mame", "com.retroarch"), CocoonAliases.player("ra.mamearcade"))
    }

    @Test
    fun `standalone players resolve through the preset catalog`() {
        assertEquals("armsx2-uri", CocoonAliases.player("ps2.come.nanodata.armsx2")?.profileId)
        assertEquals("ppsspp", CocoonAliases.player("psp.org.ppsspp.ppsspp")?.profileId)
        assertEquals("mupen-fz", CocoonAliases.player("n64.org.mupen64plusae.v3.fzurita.pro")?.profileId)
        assertEquals(CocoonPlayer("lime3ds-file", null, "io.github.lime3ds.android"), CocoonAliases.player("3ds.azahar"))
    }

    @Test
    fun `no player and unknown apps give nothing`() {
        assertNull(CocoonAliases.player("__none__"))
        assertNull(CocoonAliases.player(""))
        assertNull(CocoonAliases.player("switch.com.example.unknownemu"))
    }

    @Test
    fun `alias groups and preset ids`() {
        assertTrue("pcengine" in CocoonAliases.group("tg16"))
        assertTrue("arcade" in CocoonAliases.group("fbneo"))
        assertEquals(setOf("cps2"), CocoonAliases.group("cps2"))
        assertEquals(setOf("supergrafx"), CocoonAliases.group("supergrafx"))
        assertEquals("pcengine", CocoonAliases.PLATFORM_TO_PRESET["tg16"])
        assertEquals("n3ds", CocoonAliases.PLATFORM_TO_PRESET["3ds"])
    }
}
