package io.github.esko997.sortie.data.guide

import io.github.esko997.sortie.data.index.Indexer
import io.github.esko997.sortie.data.magazine.SystemFamilies
import io.github.esko997.sortie.data.manifest.GuideEntry
import io.github.esko997.sortie.data.manifest.LibraryRules
import io.github.esko997.sortie.data.manifest.SystemDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedGuideTest {
    private val families = SystemFamilies(
        listOf(
            SystemDef(id = "ps2", generation = "128-bit", roots = emptyList()),
            SystemDef(id = "psp", generation = "handheld", roots = emptyList()),
            SystemDef(id = "psx", generation = "32-bit", roots = emptyList()),
            SystemDef(id = "psvita", generation = "handheld", roots = emptyList()),
            SystemDef(id = "amiga", generation = "16-bit", roots = emptyList()),
            SystemDef(id = "tg-cd", generation = "16-bit", roots = emptyList()),
            SystemDef(id = "cps2", generation = "arcade", roots = emptyList()),
            SystemDef(id = "neogeo", generation = "arcade", roots = emptyList()),
            SystemDef(id = "neogeocd", generation = "arcade", roots = emptyList())
        )
    )

    private fun variant(
        id: String,
        platform: String,
        vararg names: String,
        hook: String = "Hook for $id.",
        flags: List<String> = emptyList(),
        controls: String = ""
    ) = """{"id": "$id", "platform": "$platform", "matchNames": [${names.joinToString { "\"$it\"" }}], "hook": "$hook", "controls": "$controls",
           "flags": [${flags.joinToString { "\"$it\"" }}]}"""

    private fun catalog(vararg works: Pair<String, List<String>>): CuratedCatalog = CuratedCatalog.parse(
        """{"format": 1, "works": [${works.joinToString { (id, vs) -> """{"id": "$id", "variants": [${vs.joinToString()}]}""" }}]}""".toByteArray()
    )

    private val library = catalog(
        "armored-core-2" to listOf(
            variant("ps2-armored-core-2", "ps2", "Armored Core 2"),
            variant("ps2-armored-core-2-true-analogs-v1-0", "ps2", "Armored Core 2 (True Analogs v1.0)", flags = listOf("hack"))
        ),
        "alundra" to listOf(
            variant("psx-alundra", "psx", "Alundra"),
            variant("psx-alundra-un-working-designs", "psx", "Alundra (Un-Working Designs)")
        ),
        "exile-amiga" to listOf(variant("amiga-exile", "amiga", "Exile (AGA)")),
        "exile-tg-cd" to listOf(variant("tg-cd-exile", "tg-cd", "Exile")),
        "sonic-the-hedgehog-the-lost-worlds" to listOf(variant("genesis-the-lost-worlds", "genesis", "Sonic the Hedgehog - The Lost Worlds")),
        "dragons-crown" to listOf(variant("psvita-dragons-crown", "psvita", "Dragon's Crown")),
        "sfa3" to listOf(variant("arcade-sfa3", "arcade", "sfa3")),
        "last-resort" to listOf(variant("neogeocd-last-resort", "neogeocd", "Last Resort"))
    )

    private fun matched(system: String, file: String, title: String = "", hack: Boolean = false, c: CuratedCatalog = library): String? =
        c.match(system, file, title.ifEmpty { file.substringBeforeLast('.') }, hack, families)?.variant?.id

    @Test
    fun `regions, revisions, discs and containers of one edition share its prose`() {
        assertEquals("ps2-armored-core-2", matched("ps2", "Armored Core 2 (USA).iso"))
        assertEquals("ps2-armored-core-2", matched("ps2", "Armored Core 2 (Japan) (Rev 1).chd"))
        assertEquals("psx-alundra", matched("psx", "Alundra (USA) (Rev 1).chd"))
        assertEquals("psx-alundra", matched("psx", "Alundra (Europe) (En,Fr,De) (Disc 1).cue"))
    }

    @Test
    fun `editions, hacks and other patch versions never take another variant's prose`() {
        assertEquals("ps2-armored-core-2-true-analogs-v1-0", matched("ps2", "Armored Core 2 (USA) (True Analogs v1.0).iso", hack = true))
        // a different release of the patch is not the one the hook describes
        assertNull(matched("ps2", "Armored Core 2 (USA) (True Analogs v1.03).iso", hack = true))
        // an unknown hack of the game must not inherit the retail hook
        assertNull(matched("ps2", "Armored Core 2 (USA) (Hard Mode Hack).iso", hack = true))
        assertEquals("psx-alundra-un-working-designs", matched("psx", "Alundra (USA) (Un-Working Designs).chd"))
    }

    @Test
    fun `same names on other platforms and longer titles are different games`() {
        assertNull(matched("psp", "Armored Core 2 (USA).iso"))
        // two unrelated games called Exile: each platform keeps its own
        assertEquals("tg-cd-exile", matched("tg-cd", "Exile (USA).chd"))
        assertEquals("amiga-exile", matched("amiga", "Exile (AGA).m3u"))
        assertNull(matched("amiga", "Exile (USA).chd"))
        assertNull(matched("tg-cd", "Exile (USA) (Un-Working Designs).chd"))
        assertNull(matched("amiga", "Exile (1995)(Audiogenic).m3u"))
        // no prefix or series matching
        assertNull(matched("ps2", "Armored Core 2 - Another Age (USA).iso"))
        assertNull(matched("genesis", "Sonic the Hedgehog (USA).md"))
    }

    @Test
    fun `arcade entries match romsets on every arcade system but never a CD port's scraped title`() {
        assertEquals("arcade-sfa3", matched("cps2", "sfa3.zip", title = "Street Fighter Alpha 3"))
        assertEquals("arcade-sfa3", matched("neogeo", "sfa3.7z"))
        assertNull(matched("psx", "sfa3.zip"))
        assertEquals("neogeocd-last-resort", matched("neogeocd", "Last Resort (Japan) (En,Ja).chd"))
        // an MVS romset whose scraped title is the CD game's is not the CD release
        assertNull(matched("neogeo", "lresort.zip", title = "Last Resort"))
    }

    @Test
    fun `scraped titles stand in only for opaque file names`() {
        assertEquals("psvita-dragons-crown", matched("psvita", "PCSE00019.zip", title = "Dragon's Crown"))
        assertNull(matched("psvita", "Crown Collection.zip", title = "Dragon's Crown"))
    }

    @Test
    fun `a file two variants could claim attaches to neither unless its own name decides`() {
        val ambiguous = catalog(
            "a" to listOf(variant("arcade-garou", "arcade", "garou")),
            "b" to listOf(variant("neogeo-garou", "neogeo", "garou"))
        )
        assertNull(matched("neogeo", "garou.zip", c = ambiguous))
        val byFile = catalog(
            "a" to listOf(variant("arcade-garou", "arcade", "garou")),
            "b" to listOf(variant("arcade-garou-title", "arcade", "Garou - Mark of the Wolves"))
        )
        assertEquals("arcade-garou", matched("neogeo", "garou.zip", title = "Garou - Mark of the Wolves", c = byFile))
    }

    @Test
    fun `unusable variants are dropped and malformed or missing catalogues leave the layer empty`() {
        val c = catalog("w" to listOf(variant("x-empty", "ps2", "Armored Core 2", hook = ""), variant("x-ok", "ps2", "Black")))
        assertEquals(1, c.variantCount)
        assertSame(CuratedCatalog.EMPTY, CuratedCatalog.load(null))
        assertSame(CuratedCatalog.EMPTY, CuratedCatalog.load("{not json".toByteArray()))
        assertSame(CuratedCatalog.EMPTY, CuratedCatalog.load("""{"format": 2, "works": []}""".toByteArray()))
        assertEquals("curated:none", CuratedCatalog.EMPTY.stamp)
    }

    @Test
    fun `new catalogue bytes change the index stamp, so an upgrade re-indexes changed prose`() {
        val a = catalog("w" to listOf(variant("ps2-black", "ps2", "Black", hook = "Old hook.")))
        val b = catalog("w" to listOf(variant("ps2-black", "ps2", "Black", hook = "New hook.")))
        val same = catalog("w" to listOf(variant("ps2-black", "ps2", "Black", hook = "Old hook.")))
        assertNotEquals(a.stamp, b.stamp)
        assertEquals(a.stamp, same.stamp)
        val rules = LibraryRules()
        assertNotEquals(Indexer.metaStamp("1:2:x", a.stamp, rules), Indexer.metaStamp("1:2:x", b.stamp, rules))
        assertNotEquals(Indexer.metaStamp("1:2:x", CuratedCatalog.EMPTY.stamp, rules), Indexer.metaStamp("1:2:x", a.stamp, rules))
    }

    private val bundled = CuratedVariant(
        id = "ps2-god-hand",
        platform = "ps2",
        matchNames = listOf("God Hand"),
        hook = "Bundled hook.",
        controls = "Bundled controls.",
        flags = listOf("brutal difficulty")
    )

    @Test
    fun `without a guide entry the catalogue is used with no fit and no tags`() {
        val g = GuidePrecedence.effective(null, bundled)!!
        assertEquals(
            GuideEntry(fit = 0, hook = "Bundled hook.", controls = "Bundled controls.", flags = listOf("brutal difficulty"), tags = emptyList(), auto = false),
            g
        )
        assertNull(GuidePrecedence.effective(null, null))
    }

    @Test
    fun `an authored guide entry is complete, blanks included`() {
        val authored = GuideEntry(fit = 5, hook = "", controls = "", flags = emptyList(), tags = listOf("brawler"), auto = false)
        assertSame(authored, GuidePrecedence.effective(authored, bundled))
        val withHook = GuideEntry(fit = 4, hook = "Card hook.", auto = false)
        assertSame(withHook, GuidePrecedence.effective(withHook, bundled))
    }

    @Test
    fun `a generated guide entry keeps its values and the catalogue fills only its blanks`() {
        val generated = GuideEntry(fit = 4, hook = "", controls = "", flags = emptyList(), tags = listOf("brawler", "favourite-lineage"), auto = true)
        val g = GuidePrecedence.effective(generated, bundled)!!
        assertEquals(4, g.fit)
        assertEquals(listOf("brawler", "favourite-lineage"), g.tags)
        assertEquals("Bundled hook.", g.hook)
        assertEquals("Bundled controls.", g.controls)
        assertEquals(listOf("brutal difficulty"), g.flags)
        assertTrue("catalogue prose is hand-written", !g.auto)

        val explicit = generated.copy(controls = "Card controls.", flags = listOf("card flag"))
        val e = GuidePrecedence.effective(explicit, bundled)!!
        assertEquals("Card controls.", e.controls)
        assertEquals(listOf("card flag"), e.flags)
        assertEquals("Bundled hook.", e.hook)

        val generatedHook = generated.copy(hook = "Generated hook.")
        val h = GuidePrecedence.effective(generatedHook, bundled)!!
        assertEquals("Generated hook.", h.hook)
        assertTrue(h.auto)
        assertSame(generated, GuidePrecedence.effective(generated, null))
    }
}
