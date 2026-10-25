package io.github.esko997.sortie.data.cocoon

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonBackupReaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(path: String): File = File(requireNotNull(javaClass.getResource("/cocoon/$path")) { path }.toURI())

    private fun backup(into: File): File = into.also { fixture("backup").copyRecursively(it) }

    private fun manifest(dir: File, createdAt: Long, format: String = "cocoon_unified_backup") {
        dir.mkdirs()
        File(dir, "manifest.json").writeText("""{"created_at": $createdAt, "format": "$format", "source_app_version": "3.07"}""")
    }

    @Test
    fun `finds unified backups newest first and ignores other folders`() {
        val cocoon = tmp.newFolder("Cocoon")
        val older = File(cocoon, "backups/cocoon_backup_2026-10-12").also { manifest(it, 1_000L) }
        val newer = File(cocoon, "backups/cocoon_backup_2026-10-14").also { manifest(it, 2_000L) }
        manifest(File(cocoon, "backups/themes"), 3_000L, format = "cocoon_theme")
        File(cocoon, "backups/empty").mkdirs()
        File(cocoon, "backups/broken").mkdirs()
        File(cocoon, "backups/broken/manifest.json").writeText("{not json")
        assertEquals(listOf(newer, older), CocoonBackupReader.find(cocoon))
    }

    @Test
    fun `copied backups outside Cocoon are ordered by their recorded date`() {
        val downloads = tmp.newFolder("Download")
        val older = File(downloads, "recently-copied").also { manifest(it, 1_000L) }
        val newer = File(downloads, "earlier-copy").also { manifest(it, 2_000L) }
        older.setLastModified(9_000L)
        newer.setLastModified(3_000L)
        assertEquals(listOf(newer to 2_000L, older to 1_000L), CocoonBackupReader.findDated(downloads))
        assertEquals(listOf(newer), CocoonBackupReader.find(newer))
    }

    @Test
    fun `reads user folders with members in Cocoon's order`() {
        val b = CocoonBackupReader.read(backup(tmp.newFolder("b")))
        assertEquals(1_791_000_000_000L, b.createdAt)
        assertEquals("3.07", b.appVersion)
        val folder = b.folders.single()
        assertEquals(7, folder.id)
        assertEquals("Weekend Picks", folder.name)
        assertEquals(
            listOf(
                CocoonRef("tg16", "Star Patrol (USA).pce", null),
                CocoonRef("cps2", "skyfleet.7z", null),
                CocoonRef("shortcut", "Example Quest", null),
                CocoonRef("android_app", null, "com.example.shmup")
            ),
            folder.members
        )
    }

    @Test
    fun `reads real sessions only and skips a torn line`() {
        val b = CocoonBackupReader.read(backup(tmp.newFolder("b")))
        assertEquals(
            listOf(
                CocoonSession(CocoonRef("gba", "Bits, Inc. - Micro Games$! (USA).gba", null), 1_790_100_000_000L, 1_790_100_060_000L, "com.retroarch"),
                CocoonSession(CocoonRef("android_app", null, "com.example.shmup"), 1_790_199_000_000L, 1_790_200_000_000L, null)
            ),
            b.sessions
        )
    }

    @Test
    fun `reads player overrides, favourites, hidden flags and roots`() {
        val b = CocoonBackupReader.read(backup(tmp.newFolder("b")))
        assertEquals(mapOf(CocoonRef("gba", "Metroid Fusion (USA).gba", null) to "gba.ra64.mgba"), b.playerOverrides)
        assertEquals(setOf(CocoonRef("android_app", null, "com.example.shmup")), b.favourites)
        assertEquals(
            setOf(CocoonRef("android_app", null, "com.example.reader"), CocoonRef("gba", "Pairs 1 & 2 (USA).gba", null)),
            b.hidden
        )
        assertEquals(
            mapOf("gba" to setOf("ABCD-1234:roms/gba"), "tg16" to setOf("ABCD-1234:roms/tg16", "ABCD-1234:roms/pcengine")),
            b.roots
        )
    }

    @Test
    fun `a backup without optional categories still reads`() {
        val dir = tmp.newFolder("bare").also { manifest(it, 5L) }
        val b = CocoonBackupReader.read(dir)
        assertEquals(5L, b.createdAt)
        assertEquals(emptyList<CocoonFolder>(), b.folders)
        assertEquals(emptyList<CocoonSession>(), b.sessions)
    }

    @Test
    fun `a folder that is not a unified backup is refused`() {
        val dir = tmp.newFolder("theme").also { manifest(it, 5L, format = "cocoon_theme") }
        assertThrows(IOException::class.java) { CocoonBackupReader.read(dir) }
        assertThrows(IOException::class.java) { CocoonBackupReader.read(tmp.newFolder("nothing")) }
    }
}
