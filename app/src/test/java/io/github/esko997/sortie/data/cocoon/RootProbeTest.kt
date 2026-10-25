package io.github.esko997.sortie.data.cocoon

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootProbeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun files(dir: File, names: Iterable<String>): File = dir.apply {
        mkdirs()
        names.forEach { File(this, it).writeText("x") }
    }

    /** 25 games: 10 % is 2.5, so a folder needs 3 hits. */
    private val big = (1..25).map { "Game $it (USA).md" }

    @Test
    fun `a folder needs ten percent of a platform's games`() {
        val card = tmp.newFolder("card")
        val roms = files(File(card, "roms/genesis"), big.take(20))
        files(File(card, "Download"), big.takeLast(2)) // 2 of 25: a stray copy, not a root
        val third = files(File(card, "more/genesis"), big.drop(20).take(3)) // exactly 3 of 25
        val probe = RootProbe(listOf(card), exclude = emptyList())
        val roots = probe.discover(mapOf("genesis" to big), emptyMap())
        assertEquals(listOf(roms, third), roots.getValue("genesis"))
    }

    @Test
    fun `a small platform needs one hit and names compare case-insensitively`() {
        val card = tmp.newFolder("card")
        val dir = files(File(card, "roms/ngpc"), listOf("SONIC POCKET ADVENTURE (WORLD).NGC", "readme.txt"))
        val roots = RootProbe(listOf(card), emptyList()).discover(mapOf("ngpc" to listOf("Sonic Pocket Adventure (World).ngc")), emptyMap())
        assertEquals(listOf(dir), roots.getValue("ngpc"))
    }

    @Test
    fun `the Cocoon dir, Android, dot folders and deep folders are not probed`() {
        val card = tmp.newFolder("card")
        val cocoon = File(card, "Cocoon")
        files(File(cocoon, "gamelists/genesis"), big)
        files(File(card, "Android/data/x"), big)
        files(File(card, ".hidden"), big)
        val deep = files(File(card, "a/b/c/d"), big)
        val probe = RootProbe(listOf(card), exclude = listOf(cocoon))
        assertTrue(probe.discover(mapOf("genesis" to big), emptyMap()).isEmpty())
        assertFalse(probe.listings().any { it.dir == deep })

        // a hint is listed wherever it is and counts with a single hit
        val hinted = probe.discover(mapOf("genesis" to big), mapOf("genesis" to listOf(File(card, "a/b/c/d"), File(cocoon, "gamelists/genesis"))))
        assertEquals(listOf(deep), hinted.getValue("genesis"))
        assertTrue(probe.listings().any { it.dir == deep })
    }

    @Test
    fun `a hint with one hit is kept while the walk needs more`() {
        val card = tmp.newFolder("card")
        val hint = files(File(card, "roms/genesis-extra"), big.take(1))
        val roms = files(File(card, "roms/genesis"), big.drop(1))
        val roots = RootProbe(listOf(card), emptyList()).discover(mapOf("genesis" to big), mapOf("genesis" to listOf(hint)))
        assertEquals(listOf(hint, roms), roots.getValue("genesis"))
    }

    @Test
    fun `grant hints are empty without the debug file`() {
        val cocoon = tmp.newFolder("Cocoon")
        assertTrue(RootProbe.grantHints(cocoon).isEmpty())
        // grants on volumes this machine does not have are dropped rather than probed
        File(cocoon, "permissions_debug.txt").writeText(
            "Persisted URI permissions:\n[PROTECTED] content://com.android.externalstorage.documents/tree/9999-0000%3Aroms%2Fgba (read)\n"
        )
        assertTrue(RootProbe.grantHints(cocoon).isEmpty())
    }

    @Test
    fun `grants only hint the platform's own alias group`() {
        val card = tmp.newFolder("card")
        val roms = File(card, "roms")
        val fbneo = files(File(roms, "fbneo"), listOf("gyruss.zip"))
        val arcade = files(File(roms, "arcade"), listOf("1944.zip"))
        val coleco = files(File(roms, "colecovision"), listOf("gyruss.zip"))
        val hints = RootProbe.hints(listOf("fbneo", "colecovision"), listOf(fbneo, coleco, roms), null, emptyList(), card)
        assertEquals(setOf(fbneo, arcade), hints.getValue("fbneo").toSet())
        assertEquals(listOf(coleco), hints.getValue("colecovision"))
    }
}
