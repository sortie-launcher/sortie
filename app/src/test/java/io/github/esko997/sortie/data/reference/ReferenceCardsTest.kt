package io.github.esko997.sortie.data.reference

import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceCardsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** Only the paths Sortie writes are opened: no traversal, no foreign directories, no symlinked game directory. */
    @Test
    fun imagePathsResolveOnlyInsideTheReferenceDirectory() {
        val root = Files.createTempDirectory("sortie").toFile()
        val relative = ReferenceFiles.imagePath("snes/Super Metroid (USA).sfc", "0b1c-2d", "png")
        assertEquals(File(root, relative).canonicalFile, ReferenceFiles.resolve(root, relative))
        val key = ReferenceFiles.gameKey("snes/Super Metroid (USA).sfc")
        listOf(
            null,
            "reference/$key/../../user.json",
            "reference/$key/a.svg",
            "covers/$key/a.png",
            "/reference/$key/a.png",
            "reference/$key/sub/a.png"
        ).forEach { assertNull(it, ReferenceFiles.resolve(root, it)) }

        val outside = Files.createTempDirectory("elsewhere")
        val linked = "0123456789abcdef0123"
        File(root, "reference").mkdirs()
        Files.createSymbolicLink(File(root, "reference/$linked").toPath(), outside)
        assertNull(ReferenceFiles.resolve(root, "reference/$linked/card.png"))

        // reference/ itself pointing out of the Sortie directory
        val other = Files.createTempDirectory("sortie").toFile()
        Files.createSymbolicLink(File(other, "reference").toPath(), outside)
        File(outside.toFile(), key).mkdirs()
        assertNull(ReferenceFiles.resolve(other, relative))
    }

    @Test
    fun neighbourWrapsAndFallsBackToTheFirstCard() {
        val cards = listOf("a", "b", "c").map { ReferenceCard(id = it) }
        assertEquals("b", ReferenceCards.neighbour(cards, "a", 1)?.id)
        assertEquals("c", ReferenceCards.neighbour(cards, "a", -1)?.id)
        assertEquals("a", ReferenceCards.neighbour(cards, "c", 1)?.id)
        assertEquals("a", ReferenceCards.neighbour(cards, "deleted", 1)?.id)
        assertNull(ReferenceCards.neighbour(emptyList(), "a", 1))
    }

    /** Edits replace in place; a game's last card removed drops its key, leaving other games' cards untouched. */
    @Test
    fun editsStayWithinOneGame() {
        val other = mapOf("gba/b.gba" to listOf(ReferenceCard(id = "x")))
        val one = ReferenceCards.withCards(other, "snes/a.sfc", ReferenceCards.upsert(emptyList(), ReferenceCard(id = "a", title = "Map")))
        val edited = ReferenceCards.upsert(one.getValue("snes/a.sfc"), ReferenceCard(id = "a", title = "World map"))
        assertEquals(listOf("World map"), edited.map { it.title })
        assertEquals(other, ReferenceCards.withCards(one, "snes/a.sfc", emptyList()))
    }

    /** user.json written before cards existed, and cards written by a newer build, both load. */
    @Test
    fun userDataWithoutOrWithNewerCardFieldsDecodes() {
        assertEquals(emptyMap<String, List<ReferenceCard>>(), json.decodeFromString(UserData.serializer(), "{}").referenceCards)
        val data = json.decodeFromString(UserData.serializer(), """{"referenceCards":{"snes/a.sfc":[{"id":"a","future":1}]}}""")
        assertEquals(ReferenceCard(id = "a"), data.referenceCards.getValue("snes/a.sfc").single())
    }

    @Test
    fun displaySizeKeepsSmallImagesAndBoundsLargeOnes() {
        assertEquals(1200 to 800, ReferenceCards.displaySize(1200, 800))
        val (w, h) = ReferenceCards.displaySize(8000, 8000)
        assertTrue(w.toLong() * h <= ReferenceLimits.DISPLAY_MAX_PIXELS)
        val (tw, th) = ReferenceCards.displaySize(2000, 16000)
        assertTrue(th <= ReferenceLimits.DISPLAY_MAX_EDGE && tw >= 1000)
        assertEquals(0 to 0, ReferenceCards.displaySize(0, 10))
    }

    @Test
    fun markdownImportRejectsBinaryAndTitlesFromTheFirstHeading() {
        assertNull(ReferenceCards.decodeMarkdown(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertNull(ReferenceCards.decodeMarkdown("a\u0000b".toByteArray()))
        assertEquals("# Bosses\nok", ReferenceCards.decodeMarkdown("\uFEFF# Bosses\r\nok".toByteArray()))
        assertEquals("Bosses", ReferenceCards.titleFor("intro\n## Bosses\n", "guide.md"))
        assertEquals("guide", ReferenceCards.titleFor("no heading", "guide.md"))
    }
}
