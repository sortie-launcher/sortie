package io.github.esko997.sortie.data.index

import io.github.esko997.sortie.data.db.Signature
import io.github.esko997.sortie.data.manifest.LibraryRules
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexerScanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val rules = LibraryRules()

    private fun card(): File = tmp.newFolder("card")

    private fun rom(dir: File, name: String, bytes: Int = 4, mtime: Long = 1_700_000_000_000L): File {
        dir.mkdirs()
        return File(dir, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(mtime)
        }
    }

    private fun sys(vararg roots: String, folder: String = "Sega") = SystemDef(
        id = "genesis",
        folder = folder,
        label = "Genesis",
        roots = roots.toList(),
        extensions = listOf(".md")
    )

    private fun sig(f: File, folder: String = "Sega", path: String = f.path, arriving: Boolean = false) =
        Signature("genesis/${f.name}", path, folder, f.lastModified(), f.length(), addedAt = 1L, arriving = arriving)

    private fun sig(id: String, path: String) = Signature(id, path, "Sega", 1L, 4L, 1L, false)

    @Test
    fun `a missing root is not an empty folder and keeps its rows`() {
        val card = card()
        val scan = Indexer.scan(card, sys("roms/genesis"), rules)
        assertEquals(listOf(Indexer.RootState.MISSING), scan.roots.map { it.state })
        assertFalse(scan.complete)
        val existing = listOf(sig("genesis/Sonic.md", File(card, "roms/genesis/Sonic.md").path)).associateBy { it.id }
        val plan = Indexer.plan(existing, scan, metaChanged = false, folder = "Sega")
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf("genesis/Sonic.md"), plan.kept)
        val problems = Indexer.problems(sys("roms/genesis"), scan, plan)
        assertEquals(1, problems.size)
        assertTrue(problems.single(), problems.single().contains("is missing") && problems.single().contains("kept 1 game"))
    }

    @Test
    fun `a root that is a file rather than a folder counts as missing`() {
        val card = card()
        File(card, "roms").mkdirs()
        File(card, "roms/genesis").writeText("not a folder")
        assertEquals(listOf(Indexer.RootState.MISSING), Indexer.scan(card, sys("roms/genesis"), rules).roots.map { it.state })
    }

    @Test
    fun `an unreadable root keeps its rows`() {
        val card = card()
        val dir = File(card, "roms/genesis")
        rom(dir, "Sonic.md")
        dir.setReadable(false)
        try {
            assumeTrue("the test user can still list unreadable folders (root?)", dir.listFiles() == null)
            val scan = Indexer.scan(card, sys("roms/genesis"), rules)
            assertEquals(listOf(Indexer.RootState.UNREADABLE), scan.roots.map { it.state })
            val existing = listOf(sig("genesis/Sonic.md", File(dir, "Sonic.md").path)).associateBy { it.id }
            val plan = Indexer.plan(existing, scan, metaChanged = true, folder = "Sega")
            assertTrue(plan.delete.isEmpty())
            assertEquals(listOf("genesis/Sonic.md"), plan.kept)
            assertTrue(Indexer.problems(sys("roms/genesis"), scan, plan).single().contains("can't be read"))
        } finally {
            dir.setReadable(true)
        }
    }

    @Test
    fun `an existing empty root really is empty and its stale rows go`() {
        val card = card()
        File(card, "roms/genesis").mkdirs()
        val scan = Indexer.scan(card, sys("roms/genesis"), rules)
        assertTrue(scan.complete)
        val existing = listOf(sig("genesis/Sonic.md", File(card, "roms/genesis/Sonic.md").path)).associateBy { it.id }
        val plan = Indexer.plan(existing, scan, metaChanged = false, folder = "Sega")
        assertEquals(listOf("genesis/Sonic.md"), plan.delete)
        assertTrue(plan.kept.isEmpty())
        assertTrue(Indexer.problems(sys("roms/genesis"), scan, plan).isEmpty())
    }

    @Test
    fun `a missing secondary root still indexes the available one without deleting anything`() {
        val card = card()
        val present = File(card, "roms/genesis")
        val sonic = rom(present, "Sonic.md")
        val newGame = rom(present, "Streets of Rage.md")
        val system = sys("roms/genesis", "/no/such/volume/genesis")
        val scan = Indexer.scan(card, system, rules)
        assertEquals(listOf(Indexer.RootState.LISTED, Indexer.RootState.MISSING), scan.roots.map { it.state })
        val fromMissing = sig("genesis/Gunstar Heroes.md", "/no/such/volume/genesis/Gunstar Heroes.md")
        val existing = listOf(sig(sonic), fromMissing).associateBy { it.id }
        val plan = Indexer.plan(existing, scan, metaChanged = false, folder = "Sega")
        assertEquals(listOf("genesis/${newGame.name}"), plan.build.map { it.id })
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf(fromMissing.id), plan.kept)
        assertTrue(Indexer.problems(system, scan, plan).single().contains("/no/such/volume/genesis"))
    }

    @Test
    fun `a moved root with identical size and mtime rebuilds the row with the new path`() {
        val card = card()
        val moved = rom(File(card, "games/genesis"), "Sonic.md")
        val oldPath = File(card, "roms/genesis/Sonic.md").path
        val scan = Indexer.scan(card, sys("games/genesis"), rules)
        val existing = listOf(sig(moved, path = oldPath)).associateBy { it.id }
        val plan = Indexer.plan(existing, scan, metaChanged = false, folder = "Sega")
        assertEquals(listOf(moved.path), plan.build.map { it.file.path })
        assertTrue(plan.delete.isEmpty())
    }

    @Test
    fun `an unchanged file keeps its row and only flips arriving`() {
        val card = card()
        val dir = File(card, "roms/genesis")
        val sonic = rom(dir, "Sonic.md")
        File(dir, ".syncthing.Sonic.md.tmp").writeText("")
        val scan = Indexer.scan(card, sys("roms/genesis"), rules)
        assertEquals(setOf("Sonic.md"), scan.arriving)
        val plan = Indexer.plan(listOf(sig(sonic)).associateBy { it.id }, scan, metaChanged = false, folder = "Sega")
        assertTrue(plan.build.isEmpty())
        assertEquals(mapOf(true to listOf("genesis/Sonic.md")), plan.arriving)
        val settled = Indexer.plan(listOf(sig(sonic, arriving = true)).associateBy { it.id }, scan, metaChanged = false, folder = "Sega")
        assertTrue(settled.arriving.isEmpty())
    }

    @Test
    fun `a changed system folder or metadata rebuilds unchanged files`() {
        val card = card()
        val sonic = rom(File(card, "roms/genesis"), "Sonic.md")
        val scan = Indexer.scan(card, sys("roms/genesis"), rules)
        val existing = listOf(sig(sonic)).associateBy { it.id }
        assertEquals(1, Indexer.plan(existing, scan, metaChanged = false, folder = "Mega Drive").build.size)
        assertEquals(1, Indexer.plan(existing, scan, metaChanged = true, folder = "Sega").build.size)
        assertEquals(0, Indexer.plan(existing, scan, metaChanged = false, folder = "Sega").build.size)
    }

    @Test
    fun `the same file name in two roots prefers the copy with bytes`() {
        val card = card()
        rom(File(card, "a"), "Sonic.md", bytes = 0)
        val real = rom(File(card, "b"), "Sonic.md", bytes = 8)
        val scan = Indexer.scan(card, sys("a", "b"), rules)
        assertEquals(real.path, scan.found.getValue("genesis/Sonic.md").file.path)
    }

    @Test
    fun `extensions, skipped files and hidden files are filtered`() {
        val card = card()
        val dir = File(card, "roms/genesis")
        rom(dir, "Sonic.MD")
        rom(dir, "readme.txt")
        rom(dir, ".hidden.md")
        rom(dir, "bios.md")
        val scan = Indexer.scan(card, sys("roms/genesis"), LibraryRules(skipFiles = listOf("BIOS.md")))
        assertEquals(setOf("genesis/Sonic.MD"), scan.found.keys)
    }
}
