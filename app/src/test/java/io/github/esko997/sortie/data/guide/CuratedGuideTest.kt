package io.github.esko997.sortie.data.guide

import io.github.esko997.sortie.data.manifest.GuideEntry
import java.io.FilterInputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedGuideTest {
    private fun entry(number: Int = 1, system: String = "genesis", aliases: List<String> = listOf("Sonic (USA).md")) = CuratedEntry(
        id = "00000000-0000-4000-8000-${number.toString().padStart(12, '0')}", system = system, title = "Sonic", aliases = aliases,
        hook = "Shared hook.", controls = "Shared controls.", flags = listOf("fast")
    )

    private fun bytes(vararg entries: CuratedEntry, revision: String = "one") = Json.encodeToString(
        CuratedCatalogFile.serializer(), CuratedCatalogFile(schemaVersion = 1, revision = revision, entries = entries.toList())
    ).toByteArray()

    private fun catalog(vararg entries: CuratedEntry, revision: String = "one") = CuratedCatalog.parse(bytes(*entries, revision = revision))

    private val fingerprint = CatalogFingerprint("crc32", "1234abcd", "raw", 4096, "dat-release", "No-Intro identified release")
    private val observed = PayloadFingerprint("1234abcd", "raw", 4096)

    @Test
    fun `aliases retain region patch translation and revision qualifiers`() {
        val retail = entry(aliases = listOf("Sonic (USA).md", "Sonic (USA)"))
        val c = catalog(retail)
        assertEquals(retail.id, c.match("genesis", "Sonic (USA).md")?.id)
        assertEquals(retail.id, c.match("genesis", "Sonic (USA).zip")?.id)
        for (name in listOf("Sonic.md", "Sonic (Europe).md", "Sonic (USA) (Rev A).md", "Sonic (USA) [T+Eng].md", "Sonic (USA) (Hack).md", "Sonic (USA).ips")) {
            assertNull(name, c.match("genesis", name))
        }
    }

    @Test
    fun `disc aliases ignore case using root locale without losing qualifiers or punctuation`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val disc = entry(system = "ps2", aliases = listOf("INDIGO - A Game (USA).ISO", "INDIGO - A Game (USA)"))
            val c = catalog(disc)
            assertEquals(disc.id, c.match("ps2", "indigo - a game (usa).iso")?.id)
            assertEquals(disc.id, c.match("ps2", "Indigo - A Game (Usa).CHD")?.id)
            assertNull(c.match("ps2", "Indigo - A Game (USA) (Rev 1).ISO"))
            assertNull(c.match("ps2", "Indigo A Game (USA).ISO"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `case folded collisions abstain for full names and curated stems`() {
        val first = entry(system = "ps2", aliases = listOf("GAME (USA).ISO"))
        val second = entry(2, "ps2", listOf("game (usa).iso"))
        assertNull(catalog(first, second).match("ps2", "Game (USA).iso"))
        val stem = second.copy(aliases = listOf("game (usa)"))
        assertNull(catalog(first, stem).match("ps2", "GAME (USA).ISO"))
    }

    @Test
    fun `converted containers preserve qualified stems across ciso cdi and 32x`() {
        for ((system, extension) in listOf("wii" to "ciso", "dreamcast" to "cdi", "sega32x" to "32x")) {
            val game = entry(system = system, aliases = listOf("A Game - Subtitle (USA) (Rev 1)"))
            val c = catalog(game)
            assertEquals(game.id, c.match(system, "a game - subtitle (usa) (rev 1).$extension")?.id)
            assertNull(c.match(system, "A Game - Subtitle (USA).$extension"))
            assertNull(c.match(system, "A Game - Subtitle (USA) (Rev 1) (Hack).$extension"))
        }
    }

    @Test
    fun `safe relative project aliases never collapse into generic executable names`() {
        val project = entry(system = "rpgmaker", aliases = listOf("games/An Adventure/Game.exe"))
        val c = catalog(project)
        assertEquals(project.id, c.match("rpgmaker", "games/An Adventure/Game.exe")?.id)
        for (name in listOf("Game.exe", "Game", "games/Other Adventure/Game.exe", "games/An Adventure/Game.ini")) {
            assertNull(name, c.match("rpgmaker", name))
        }
        for (unsafe in listOf("/absolute/Game.exe", "../Game.exe", "games/../Game.exe", "games/./Game.exe", "games//Game.exe", "C:/Game.exe", "games\\Game.exe")) {
            assertThrows(IllegalArgumentException::class.java) { catalog(project.copy(aliases = listOf(unsafe))) }
        }
    }

    @Test
    fun `explicit variants never inherit their parent prose`() {
        val parent = entry()
        val patch = entry(2, aliases = listOf("Sonic (Hack 2).md")).copy(parentId = parent.id, hook = "Hack-specific mechanics.")
        val c = catalog(parent, patch)
        assertEquals(patch, c.match("genesis", "Sonic (Hack 2).md"))
        assertNull(c.match("genesis", "Sonic (Hack 3).md"))
    }

    @Test
    fun `platform aliases are finite and custom systems never infer from labels`() {
        val c = catalog(entry(), entry(2, "arcade", listOf("game.zip")), entry(3, "pcengine", listOf("game.pce")))
        assertEquals(entry().id, c.match("megadrive", "Sonic (USA).md")?.id)
        assertNull(c.match("snes", "Sonic (USA).md"))
        assertNull(c.match("my-genesis", "Sonic (USA).md"))
        assertEquals(entry(2).id, c.match("fbneo", "game.zip")?.id)
        assertNull(c.match("custom-arcade", "game.zip"))
        assertEquals(entry(3).id, c.match("tg16", "game.pce")?.id)
    }

    @Test
    fun `filename and stem collisions abstain instead of choosing first`() {
        val c = catalog(entry(), entry(2, aliases = listOf("Sonic (USA)")), entry(3, "snes"))
        assertNull(c.match("genesis", "Sonic (USA).md"))
        assertEquals(entry(3).id, c.match("snes", "Sonic (USA).md")?.id)
    }

    @Test
    fun `measured fingerprint identifies renamed game and outranks filename`() {
        val identified = entry().copy(fingerprints = listOf(fingerprint))
        val c = catalog(identified, entry(2, aliases = listOf("Wrong name.md")))
        assertEquals(identified.id, c.match("genesis", "My renamed game.md", listOf(observed))?.id)
        assertEquals(identified.id, c.match("genesis", "Wrong name.md", listOf(observed))?.id)
        assertNull(c.match("snes", "My renamed game.md", listOf(observed)))
        assertNull(c.match("genesis", "My renamed game.md", listOf(observed.copy(size = 4097))))
        assertNull(c.match("genesis", "My renamed game.md", listOf(observed.copy(representation = "nes-prg-chr"))))
        assertNull(c.match("genesis", "My renamed game.md", listOf(observed.copy(crc32 = "1234abce"))))
    }

    @Test
    fun `ambiguous verified fingerprints do not fall back to an alias`() {
        val a = entry().copy(fingerprints = listOf(fingerprint))
        val b = entry(2, aliases = listOf("Other.md")).copy(fingerprints = listOf(fingerprint))
        assertNull(catalog(a, b).match("genesis", "Sonic (USA).md", listOf(observed)))
    }

    @Test
    fun `duplicate ids unsupported evidence and dangling parents are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { catalog(entry(), entry()) }
        assertThrows(IllegalArgumentException::class.java) { catalog(entry().copy(parentId = entry(2).id)) }
        for (bad in listOf(fingerprint.copy(verification = "filename"), fingerprint.copy(source = ""), fingerprint.copy(size = 0), fingerprint.copy(representation = "archive"))) {
            assertThrows(IllegalArgumentException::class.java) { catalog(entry().copy(fingerprints = listOf(bad))) }
        }
    }

    @Test
    fun `catalog revision and content both invalidate metadata and asset loads once`() {
        val a = catalog(entry())
        assertNotEquals(a.stamp, catalog(entry(), revision = "two").stamp)
        assertNotEquals(a.stamp, catalog(entry().copy(hook = "Corrected text.")).stamp)
        assertNotEquals(a.stamp, CuratedCatalog.EMPTY.stamp)
        var reads = 0
        val guide = CuratedGuide { reads++; catalog(entry()) }
        val first = guide.catalog
        assertSame(first, guide.catalog)
        assertEquals(1, reads)
    }

    @Test
    fun `stream parsing preserves unicode and revision digest while skipping nested provenance`() {
        val game = entry().copy(hook = "Étoiles — 冒険")
        val source = bytes(game).decodeToString().dropLast(1) +
            ""","provenance":{"citations":[{"text":"${"é".repeat(8193)}"}]}}"""
        val encoded = source.toByteArray()
        val fragmented = object : FilterInputStream(encoded.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 3))
        }
        val parsed = fragmented.use { CuratedCatalog.parse(it) }
        val hash = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
        assertEquals("shared-blurbs:1:one:$hash", parsed.stamp)
        assertEquals(game, parsed.match("genesis", "Sonic (USA).md"))
        assertThrows(IllegalArgumentException::class.java) {
            encoded.copyOf(encoded.size - 1).inputStream().use { CuratedCatalog.parse(it) }
        }
    }

    @Test
    fun `repeated evidence for the same identity is not a collision`() {
        val game = entry(aliases = listOf("GAME (USA).ISO", "game (usa).iso", "game (usa)"))
            .copy(fingerprints = listOf(fingerprint, fingerprint))
        val c = catalog(game)
        assertEquals(game.id, c.match("genesis", "Game (USA).iso")?.id)
        assertEquals(game.id, c.match("genesis", "Renamed.md", listOf(observed, observed))?.id)
    }

    @Test
    fun `without local guide catalog supplies prose but no personal fit or tags`() {
        val g = GuidePrecedence.effective(null, entry())!!
        assertEquals(0, g.fit)
        assertTrue(g.tags.isEmpty())
        assertEquals("Shared hook.", g.hook)
        assertFalse(g.auto)
        assertNull(GuidePrecedence.effective(null, null))
    }

    @Test
    fun `authored local guide is complete even when blanks intentionally suppress shared prose`() {
        val local = GuideEntry(fit = 5, hook = "", controls = "", flags = emptyList(), tags = listOf("personal"), auto = false)
        assertSame(local, GuidePrecedence.effective(local, entry()))
        assertSame(local, GuidePrecedence.effective(local, null))
    }

    @Test
    fun `generated local fields win individually and catalog retirement removes only shared fields`() {
        val local = GuideEntry(fit = 4, controls = "My mapping.", flags = listOf("my caution"), tags = listOf("favourite"), auto = true)
        val first = GuidePrecedence.effective(local, entry())!!
        assertEquals("Shared hook.", first.hook)
        assertEquals(local.fit, first.fit)
        assertEquals(local.controls, first.controls)
        assertEquals(local.flags, first.flags)
        assertEquals(local.tags, first.tags)
        assertFalse(first.auto)
        assertEquals("Corrected text.", GuidePrecedence.effective(local, entry().copy(hook = "Corrected text."))!!.hook)
        assertSame(local, GuidePrecedence.effective(local, null))
        val hookOverride = local.copy(hook = "My hook.")
        assertEquals("My hook.", GuidePrecedence.effective(hookOverride, entry())!!.hook)
        assertTrue(GuidePrecedence.effective(hookOverride, entry())!!.auto)
    }
}
