package io.github.esko997.sortie.data.cocoon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonPathsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a primary document uri becomes an internal storage path`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3ACocoon/document/primary%3ACocoon%2Fdownloaded_media%2Fgbc%2F" +
            "screenshot_gameplay%2FQuest%2C%20The%20-%20Ages%20of%20Time%20(U)%20%5BC%5D%5B!%5D__cocoon_edit_2_0abc.jpg"
        assertEquals(
            File(
                "/storage/emulated/0/Cocoon/downloaded_media/gbc/screenshot_gameplay/" +
                    "Quest, The - Ages of Time (U) [C][!]__cocoon_edit_2_0abc.jpg"
            ),
            CocoonPaths.fromDocumentUri(uri)
        )
    }

    @Test
    fun `a card volume tree uri becomes its storage path`() {
        assertEquals(
            File("/storage/ABCD-1234/roms/gba"),
            CocoonPaths.fromDocumentUri("content://com.android.externalstorage.documents/tree/ABCD-1234%3Aroms%2Fgba")
        )
        assertEquals(
            File("/storage/ABCD-1234/roms/gba/Café+Racer.gba"),
            CocoonPaths.fromDocumentUri("content://com.android.externalstorage.documents/document/ABCD-1234%3Aroms%2Fgba%2FCaf%C3%A9+Racer.gba")
        )
        assertEquals(File("/storage/emulated/0"), CocoonPaths.fromDocumentUri("content://com.android.externalstorage.documents/tree/primary%3A"))
    }

    @Test
    fun `other references are not paths`() {
        assertNull(CocoonPaths.fromDocumentUri("android.resource://rip.moth.cocoonshell/drawable/silk"))
        assertNull(CocoonPaths.fromDocumentUri("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Fx.zip"))
        assertNull(CocoonPaths.fromDocumentUri("content://com.android.externalstorage.documents/tree/primary%3ACocoon%2F..%2F..%2Fdata"))
        assertNull(CocoonPaths.fromDocumentUri("content://com.android.externalstorage.documents/tree/primary%3ACo%ZZ"))
        assertNull(CocoonPaths.fromDocumentUri("shortcut_intent:pinned:com.droiddeck.launcher/game:100001"))
        assertNull(CocoonPaths.fromDocumentId("no-volume"))
    }

    @Test
    fun `backup document ids map like uris`() {
        assertEquals(File("/storage/ABCD-1234/roms/tg16"), CocoonPaths.fromDocumentId("ABCD-1234:roms/tg16"))
        assertEquals(File("/storage/emulated/0/roms"), CocoonPaths.fromDocumentId("primary:roms/"))
    }

    @Test
    fun `isInside sees through a symlinked alias like sdcard`() {
        val real = tmp.newFolder("storage-emulated-0")
        val cocoon = File(real, "Cocoon").apply { mkdirs() }
        val alias = File(tmp.root, "sdcard")
        val linked = runCatching { Files.createSymbolicLink(alias.toPath(), real.toPath()) }.isSuccess
        assumeTrue("symlinks unsupported here", linked)

        assertTrue(CocoonPaths.isInside(File(alias, "Cocoon/downloaded_media/gba/icon/x.jpg"), cocoon))
        assertTrue(CocoonPaths.isInside(File(cocoon, "media"), File(alias, "Cocoon")))
        assertTrue(CocoonPaths.isInside(File(alias, "Cocoon"), cocoon))
        assertTrue(CocoonPaths.isInside(File(alias, "Cocoon/not/yet/created.png"), cocoon))
        assertEquals(CocoonPaths.canonical(cocoon), CocoonPaths.canonical(File(alias, "Cocoon")))

        assertFalse(CocoonPaths.isInside(File(alias, "sortie/media"), cocoon))
        assertFalse(CocoonPaths.isInside(File(real, "Cocoon2"), cocoon))
        assertFalse(CocoonPaths.isInside(real, cocoon))
    }
}
