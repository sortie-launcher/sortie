package io.github.esko997.sortie.data.journal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** user.json may come from a backup or a hand edit: only contained, well-formed journal media paths may be read, written or deleted. */
class JournalMediaPathTest {
    private val dir: File = Files.createTempDirectory("sortie").toFile()
    private val outside: File = Files.createTempDirectory("elsewhere").toFile()

    private fun link(at: File, to: File) {
        requireNotNull(at.parentFile).mkdirs()
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    @Test
    fun wellFormedPathResolvesInsideMediaFolder() {
        val rel = "journal/media/2b1c-44/9f0e-aa.png"
        assertEquals(File(dir.canonicalFile, rel), JournalMedia.resolve(dir, rel))
        assertEquals(File(dir.canonicalFile, "journal/media/2b1c-44"), JournalMedia.entryDir(dir, "2b1c-44"))
    }

    @Test
    fun anythingElseIsRefused() {
        listOf(
            "../user.json",
            "journal/media/../../user.json",
            "journal/media/a/../b.png",
            "/storage/emulated/0/DCIM/x.png",
            "journal/media/a/b.gif",
            "journal/media/a/b/c.png",
            "journal/other/a/b.png",
            "user.json",
            ""
        ).forEach { assertNull(it, JournalMedia.resolve(dir, it)) }
        assertNull(JournalMedia.entryDir(dir, "../x"))
        assertNull(JournalMedia.entryDir(dir, ""))
    }

    @Test
    fun symlinkedEntryFolderIsRefused() {
        link(File(dir, "journal/media/link"), outside)
        assertNull(JournalMedia.resolve(dir, "journal/media/link/x.png"))
        assertNull(JournalMedia.entryDir(dir, "link"))
    }

    @Test
    fun symlinkedJournalRootIsRefusedForReadsAndWrites() {
        File(outside, "media/a").mkdirs()
        File(outside, "media/a/b.png").writeText("not ours")
        link(File(dir, "journal"), outside)
        assertNull(JournalMedia.resolve(dir, "journal/media/a/b.png"))
        assertNull(JournalMedia.entryDir(dir, "a"))
        assertNull(JournalMedia.entryDir(dir, "new-entry")) // not created yet: the symlinked parent alone refuses it
    }

    @Test
    fun symlinkedMediaFolderIsRefused() {
        link(File(dir, "journal/media"), outside)
        assertNull(JournalMedia.resolve(dir, "journal/media/a/b.png"))
        assertNull(JournalMedia.entryDir(dir, "a"))
    }

    @Test
    fun decodeStaysWithinPixelBudget() {
        listOf(7679 to 7679, 16_384 to 16_384, 16_384 to 1, 3841 to 3841, 7681 to 3841, 3840 to 3840).forEach { (w, h) ->
            val s = JournalMedia.sampleFor(w, h)
            val pixels = ((w + s - 1) / s).toLong() * ((h + s - 1) / s)
            assertTrue("$w×$h sample $s", pixels <= JournalMedia.MAX_DECODE_PIXELS)
        }
        assertEquals(1, JournalMedia.sampleFor(3840, 3840))
        assertEquals(2, JournalMedia.sampleFor(7679, 7679))
    }
}
