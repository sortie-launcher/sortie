package io.github.esko997.sortie.data.reference

import io.github.esko997.sortie.data.user.UserStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceStoreTest {
    private val game = "snes/Super Metroid (USA).sfc"

    /** A PNG signature and IHDR with the given size; enough for [pngProbe], which reads them as a decoder's header pass would. */
    private fun png(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33)
        .put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        .putInt(13).put("IHDR".toByteArray()).putInt(width).putInt(height)
        .array()

    private val pngProbe: (File) -> ImageBounds = { f ->
        val b = f.readBytes()
        if (b.size >= 24 && b[1] == 0x50.toByte() && String(b, 12, 4) == "IHDR") {
            val buf = ByteBuffer.wrap(b)
            ImageBounds(buf.getInt(16), buf.getInt(20), "image/png")
        } else {
            ImageBounds(0, 0, null)
        }
    }

    private class Setup(val root: File, val userFile: File, val store: ReferenceStore)

    private fun setup(userFile: File? = null): Setup {
        val root = Files.createTempDirectory("sortie").toFile()
        val file = userFile ?: File(root, "user.json")
        return Setup(root, file, ReferenceStore(UserStore(file), root, pngProbe))
    }

    private fun document(name: String, bytes: ByteArray, declared: Long? = bytes.size.toLong()) = PickedDocument(name, declared) { ByteArrayInputStream(bytes) }

    private fun storedFiles(root: File): List<File> = File(root, ReferenceFiles.DIR).walk().filter { it.isFile }.toList()

    /** The import path itself: a new image card is saved with its copied file, both survive a reload, other games see nothing. */
    @Test
    fun importedImageCardSurvivesReloadAndDeleteRemovesItsFile() = runBlocking {
        val s = setup()
        val bytes = png(640, 480)
        val card = s.store.importImage(game, document("world map.png", bytes))
        assertEquals("world map", card.title)
        val resolved = ReferenceFiles.resolve(s.root, card.image)
        assertNotNull(resolved)
        val file = resolved!!
        assertArrayEquals(bytes, file.readBytes())
        val reloaded = UserStore(s.userFile).data.value.referenceCards
        assertEquals(listOf(card), reloaded[game])
        assertEquals(setOf(game), reloaded.keys)

        s.store.delete(game, card.id)
        assertTrue(!file.exists())
        assertEquals(emptyMap<String, List<ReferenceCard>>(), UserStore(s.userFile).data.value.referenceCards)
    }

    /** An edit cannot repoint the image, and an image card deleted meanwhile is not resurrected by a stale editor. */
    @Test
    fun editsKeepTheImportedImageAndStaleImageEditsAreRefused() = runBlocking {
        val s = setup()
        val card = s.store.importImage(game, document("map.png", png(32, 32)))
        val edited = s.store.save(game, card.copy(title = "Lower Norfair", image = ReferenceFiles.imagePath(game, "other", "png")))
        assertEquals(card.image, edited.image)
        assertEquals("Lower Norfair", edited.title)

        s.store.delete(game, card.id)
        val stale = runCatching { s.store.save(game, edited.copy(title = "too late")) }.exceptionOrNull()
        assertTrue(stale is ReferenceException)
        assertNull(UserStore(s.userFile).data.value.referenceCards[game])
    }

    /** When user.json cannot be written the card is not saved and the copied image is removed again. */
    @Test
    fun failedSaveRemovesTheCopiedImage() = runBlocking {
        val blocker = Files.createTempFile("sortie", "file").toFile() // a regular file where user.json's directory should be
        val s = setup(File(blocker, "user.json"))
        val failure = runCatching { s.store.importImage(game, document("map.png", png(32, 32))) }.exceptionOrNull()
        assertTrue(failure is ReferenceException)
        assertEquals(emptyList<File>(), storedFiles(s.root))
    }

    /** Refused imports leave neither a card nor a file (temporary copies included). */
    @Test
    fun refusedImagesLeaveNothingBehind() = runBlocking {
        val s = setup()
        val refused = listOf(
            document("notes.txt", "not an image".toByteArray()),
            document("huge.png", png(ReferenceLimits.MAX_IMAGE_EDGE + 1, 10)),
            document("declared.png", png(10, 10), declared = ReferenceLimits.MAX_IMAGE_BYTES + 1)
        )
        refused.forEach { doc ->
            val failure = runCatching { s.store.importImage(game, doc) }.exceptionOrNull()
            assertTrue(doc.name, failure is ReferenceException)
        }
        assertEquals(emptyList<File>(), storedFiles(s.root))
        assertNull(UserStore(s.userFile).data.value.referenceCards[game])
    }

    /** A `reference/` that is a symlink out of the Sortie directory receives nothing. */
    @Test
    fun symlinkedReferenceDirectoryIsNeverWritten() = runBlocking {
        val s = setup()
        val outside = Files.createTempDirectory("elsewhere")
        Files.createSymbolicLink(File(s.root, ReferenceFiles.DIR).toPath(), outside)
        val failure = runCatching { s.store.importImage(game, document("map.png", png(32, 32))) }.exceptionOrNull()
        assertTrue(failure is ReferenceException)
        assertEquals(emptyList<File>(), outside.toFile().walk().filter { it.isFile }.toList())
        assertNull(UserStore(s.userFile).data.value.referenceCards[game])
    }

    @Test
    fun markdownImportCreatesATitledTextCard() = runBlocking {
        val s = setup()
        val card = s.store.importMarkdown(game, document("bosses.md", "# Boss order\n- Kraid\n".toByteArray()))
        assertEquals("Boss order", card.title)
        assertNull(card.image)
        assertEquals(listOf(card), UserStore(s.userFile).data.value.referenceCards[game])
        val binary = runCatching { s.store.importMarkdown(game, document("map.png", png(4, 4))) }.exceptionOrNull()
        assertTrue(binary is ReferenceException)
    }
}
