package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.gamelist.GamelistParser
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonGamelistReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(path: String): File = File(requireNotNull(javaClass.getResource("/cocoon/$path")) { path }.toURI())

    /** A Cocoon directory in a temp folder holding the fixture gamelists (copied: the reader is checked on a real tree). */
    private fun cocoonDir(): File {
        val dir = tmp.newFolder("Cocoon")
        fixture("gamelists").copyRecursively(File(dir, "gamelists"))
        return dir
    }

    @Test
    fun `the fixture really is malformed for a strict parser`() {
        assertThrows(Exception::class.java) { GamelistParser.parse(fixture("gamelists/gba/gamelist.xml")) }
    }

    @Test
    fun `reads an undeclared cocoon prefix and a bare ampersand`() {
        val gba = CocoonGamelistReader.read(fixture("gamelists/gba/gamelist.xml"))
        assertEquals("gba", gba.id)
        assertEquals("gba.ra.mgba", gba.defaultPlayer)
        assertEquals(
            listOf("Bits, Inc. - Micro Games$! (USA).gba", "Pairs 1 & 2 (USA).gba", "Café_ Test Version (1.0.2).gba"),
            gba.games.map { it.file }
        )
        val bits = gba.games[0]
        assertEquals("Bits, Inc.: Micro Games$!", bits.name)
        assertTrue(bits.favourite)
        assertFalse(bits.hidden)
        assertEquals(3, bits.playCount)
        assertEquals(12, bits.playMinutes)
        assertEquals(LocalDateTime.of(2026, 9, 1, 12, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), bits.lastPlayed)
        assertTrue(bits.boxArt!!.startsWith("content://com.android.externalstorage.documents/tree/primary%3ACocoon/document/"))
        assertTrue(bits.boxArt!!.endsWith("__cocoon_edit_1_aaaa01.jpg"))
        assertTrue(bits.logo!!.contains("%2Flogo%2F"))
        assertTrue(bits.screenshot!!.contains("%2Fscreenshot_gameplay%2F"))
    }

    @Test
    fun `an escaped entity stays one ampersand and absent counters are zero`() {
        val pairs = CocoonGamelistReader.read(fixture("gamelists/gba/gamelist.xml")).games[1]
        assertEquals("Pairs 1 & 2", pairs.name)
        assertEquals(0, pairs.playCount)
        assertNull(pairs.lastPlayed)
        assertNull(pairs.boxArt)
    }

    @Test
    fun `a path in a subfolder keeps its last segment and hidden is read`() {
        val hack = CocoonGamelistReader.read(fixture("gamelists/gba/gamelist.xml")).games[2]
        assertEquals("Café_ Test Version (1.0.2).gba", hack.file)
        assertTrue(hack.hidden)
        assertEquals(0, hack.playMinutes)
    }

    @Test
    fun `__none__ is no default player`() {
        val switch = CocoonGamelistReader.read(fixture("gamelists/switch/gamelist.xml"))
        assertNull(switch.defaultPlayer)
        assertEquals(listOf("example_switch_game.nsp"), switch.games.map { it.file })
        assertEquals("android.resource://rip.moth.cocoonshell/drawable/silk", switch.games.single().boxArt)
    }

    @Test
    fun `fallback repairs literal entities without corrupting CDATA`() {
        val file = tmp.newFile("gamelist.xml")
        val xml = """
            <gameList><cocoon:defaultplayerid>gba.ra.mgba</cocoon:defaultplayerid>
            <game><path>./Rock &Roll;.gba</path><name><![CDATA[Rock & Roll &amp;]]></name></game>
            <game><path>./Caf&eacute;.gba</path><name>1 &amp; 2 &#38; 3 &#x26; 4</name></game>
            </gameList>
        """.trimIndent()
        file.writeText(xml)
        val games = CocoonGamelistReader.read(file).games
        assertEquals(listOf("Rock &Roll;.gba", "Caf&eacute;.gba"), games.map { it.file })
        assertEquals(listOf("Rock & Roll &amp;", "1 & 2 & 3 & 4"), games.map { it.name })
        assertEquals(xml, file.readText())
    }

    @Test
    fun `valid XML preserves literal CDATA and declared entities`() {
        val file = tmp.newFile("valid.xml")
        file.writeText(
            "<gameList><game><path><![CDATA[./A & B.gba]]></path><name>A &amp; B</name></game></gameList>"
        )
        val game = CocoonGamelistReader.read(file).games.single()
        assertEquals("A & B.gba", game.file)
        assertEquals("A & B", game.name)
    }

    @Test
    fun `a broken file is reported and the others still load`() {
        val dir = cocoonDir()
        File(dir, "gamelists/broken").mkdirs()
        File(dir, "gamelists/broken/gamelist.xml").writeText("<gameList><game><path>./x.zip</path></gameList>")
        File(dir, "gamelists/.trash").mkdirs()
        File(dir, "gamelists/.trash/gamelist.xml").writeText("<gameList/>")
        File(dir, "gamelists/notes.txt").writeText("not a platform")
        File(dir, "gamelists/empty").mkdirs()

        val library = CocoonGamelistReader.readLibrary(dir)
        assertEquals(dir, library.dir)
        assertEquals(listOf("gba", "switch"), library.platforms.map { it.id })
        assertEquals(1, library.problems.size)
        assertTrue(library.problems.single(), library.problems.single().startsWith("broken: "))
    }

    /** An ES-DE tree (`scraped-esde/gamelists/<x>/gamelist.xml`) has Cocoon's layout but plain files; offering it would import nothing. */
    @Test
    fun `a folder without Cocoon gamelists is not a Cocoon directory`() {
        assertTrue(CocoonGamelistReader.looksLikeCocoonDir(cocoonDir()))
        val other = tmp.newFolder("other")
        assertFalse(CocoonGamelistReader.looksLikeCocoonDir(other))
        File(other, "gamelists/gba").mkdirs()
        assertFalse(CocoonGamelistReader.looksLikeCocoonDir(other))
        File(other, "gamelists/gba/gamelist.xml").writeText("<?xml version=\"1.0\"?>\n<gameList>\n\t<game>\n\t\t<path>./a.gba</path>\n\t</game>\n</gameList>\n")
        assertFalse(CocoonGamelistReader.looksLikeCocoonDir(other))
        val missing = CocoonGamelistReader.readLibrary(other.resolve("nowhere"))
        assertTrue(missing.platforms.isEmpty())
        assertEquals(1, missing.problems.size)
    }
}
