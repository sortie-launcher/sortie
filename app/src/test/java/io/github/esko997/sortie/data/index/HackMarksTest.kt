package io.github.esko997.sortie.data.index

import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.LibraryRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HackMarksTest {
    /** The hackRegex the desktop writes into every device's systems.json. */
    private val cardRules = LibraryRules(
        hackRegex = """(?i)\((hack|translation|redux|patched|undub|un-working designs|one vision|true analogs|plus v[\d.]+|""" +
            """english[^)]*|[^)]*patch)\)|\[[^\]]*(hack|patch)[^\]]*\]"""
    )
    private val card = HackMarks.compile(cardRules)

    // The Thor's guide.json entries, verbatim apart from shortened hooks.
    private val ac2 = GuideEntry(fit = 5, hook = "Armored Core 2 with VanLaser's dual-analog patch… Play this one, not the stock disc.", flags = listOf("hack"))
    private val lastRaven = GuideEntry(fit = 5, hook = "Last Raven with VanLaser's True Analogs v1.03…", flags = listOf("brutal difficulty", "hack"))
    private val sc3Deluxe =
        GuideEntry(fit = 3, hook = "A fan build of Soulcalibur III from TopazTK's project to port the Arcade Edition to PS2…", flags = listOf("hack"))
    private val sc3 = GuideEntry(fit = 4, hook = "Create-a-Soul with 13 job classes…", flags = emptyList())

    private fun hack(file: String, guide: GuideEntry? = null, configured: Regex? = card) = HackMarks.isHack(file, configured, guide)

    @Test
    fun `the Thor's versioned custom builds are marked by their guide flags`() {
        assertTrue(hack("Armored Core 2 (USA) (True Analogs v1.0).iso", ac2, configured = null))
        assertTrue(hack("Armored Core - Last Raven Portable (USA) (True Analogs v1.03).iso", lastRaven, configured = null))
        assertTrue(hack("Soulcalibur III - Arcade Edition Deluxe (USA) (v1.21 Hack).iso", sc3Deluxe, configured = null))
    }

    @Test
    fun `versioned qualifiers are marked from the file name alone`() {
        // the configured rule names "(true analogs)" and "(translation)"; a version number beside the words no longer hides them
        assertTrue(hack("Armored Core 2 (USA) (True Analogs v1.0).iso"))
        assertTrue(hack("Armored Core - Last Raven Portable (USA) (True Analogs v1.03).iso"))
        assertTrue(hack("Baroque - Yuganda Mousou (Japan) (En) (v1.02 Translation).chd"))
        // the word hack in a qualifier counts even without a configured rule
        assertTrue(hack("Soulcalibur III - Arcade Edition Deluxe (USA) (v1.21 Hack).iso", configured = null))
        assertTrue(hack("Super Mario World (USA) [Hack by Foo].sfc", configured = null))
    }

    @Test
    fun `retail originals stay unmarked`() {
        assertFalse(hack("Soulcalibur III (USA).iso", sc3))
        assertFalse(hack("Armored Core 2 (USA).iso", GuideEntry(flags = emptyList())))
        assertFalse(hack("Armored Core 2 (USA) (Rev 1).iso"))
        assertFalse(hack("Final Fantasy VII (USA) (Disc 2).chd"))
    }

    @Test
    fun `prose and lookalike words are not evidence`() {
        val prose =
            GuideEntry(hook = "Cyberpunk hacking sim with patched-in analog controls and a hack-and-slash finale", flags = listOf("hacker", "dated controls"))
        assertFalse(hack("Uplink - Hacker Elite (USA).iso", prose))
        assertFalse(hack(".hack - Infection (USA).iso"))
        assertFalse(hack("Bandai Collection (USA) (.hack Edition).iso", configured = null))
        assertFalse(hack("Hack 'n' Slash (USA).zip", configured = null))
    }

    @Test
    fun `an unrecognised qualifier is not a hack`() {
        assertFalse(hack("Smash Remix (v2.0.1).z64"))
        assertFalse(hack("Pokemon Unbound (v2.1.1.1).gba"))
        // marked once the curated guide says so
        assertTrue(hack("Pokemon Unbound (v2.1.1.1).gba", GuideEntry(flags = listOf("Pokemon", "hack"))))
        assertTrue(hack("Langrisser Hikari II (T-Chi).zip", GuideEntry(flags = listOf("translation"))))
        assertTrue(hack("Metal Max 3 (Japan) (En) (v1.0 Translation).nds", GuideEntry(flags = listOf("fan translation")), configured = null))
    }

    @Test
    fun `the configured rule still decides what else counts`() {
        assertTrue(hack("Frame Gride (Japan) (En) (Translation).cdi"))
        assertTrue(hack("Super Robot Taisen A Portable (Japan) (En) (Plus v1.2).iso"))
        // without the rule a translation is only marked by the guide; a custom rule can mark anything it names
        assertFalse(hack("Frame Gride (Japan) (En) (Translation).cdi", configured = null))
        assertTrue(hack("Zelda (USA) (Randomizer).sfc", configured = Regex("(?i)\\(randomizer\\)")))
        assertNull(HackMarks.compile(LibraryRules(hackRegex = "(unclosed")))
        assertTrue(
            hack("Soulcalibur III - Arcade Edition Deluxe (USA) (v1.21 Hack).iso", configured = HackMarks.compile(LibraryRules(hackRegex = "(unclosed")))
        )
    }

    @Test
    fun `an upgrade or a new hackRegex changes the stored stamp, so cached rows are rebuilt`() {
        val guideStamp = "712800:1788220800000:2026-10-25"
        // before this classifier the stored stamp was guide.json's own
        assertNotEquals(guideStamp, Indexer.metaStamp(guideStamp, cardRules))
        assertNotEquals(Indexer.metaStamp(guideStamp, cardRules), Indexer.metaStamp(guideStamp, LibraryRules()))
        assertNotEquals(Indexer.metaStamp(guideStamp, cardRules), Indexer.metaStamp(guideStamp, cardRules.copy(hackRegex = "(?i)\\(hack\\)")))
        // unchanged rules and unrelated rule edits do not rebuild the library
        assertEquals(Indexer.metaStamp(guideStamp, cardRules), Indexer.metaStamp(guideStamp, cardRules.copy(skipFiles = listOf("neogeo.zip"))))
    }
}
