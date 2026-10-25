package io.github.esko997.sortie.data.cocoon

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CocoonWriteGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var card: File
    private lateinit var cocoon: File
    private lateinit var sortie: File
    private lateinit var cocoonFile: File
    private lateinit var art: ArtworkCopy

    @Before
    fun card() {
        card = tmp.newFolder("card")
        cocoon = File(card, "Cocoon").apply { mkdirs() }
        cocoonFile = File(cocoon, "gamelists/gba/gamelist.xml").apply {
            parentFile!!.mkdirs()
            writeText("<cocoon/>")
        }
        sortie = File(card, "sortie").apply { mkdirs() }
        File(sortie, CocoonWriteGuard.USER_FILE).writeText("{}")
        File(sortie, CocoonWriteGuard.PLAY_LOG).writeText("")
        val source = File(cocoon, "downloaded_media/gba/icon/Example__cocoon_edit_1_ab.png").apply {
            parentFile!!.mkdirs()
            writeText("png")
        }
        art = ArtworkCopy("gba/Example.gba", "covers", source, File(sortie, "media/gba/covers/Example.png"), 3L)
    }

    private fun plan(backup: CocoonBackup? = null) = CocoonImportPlan(
        cocoonDir = cocoon,
        backup = backup,
        newSystems = emptyList(),
        mediaDirs = mapOf("gba" to "sortie/media/gba"),
        systemProfiles = emptyList(),
        favourites = emptyList(),
        hidden = emptyList(),
        profileOverrides = emptyMap(),
        sessions = emptyList(),
        folders = emptyList(),
        artwork = listOf(art),
        artworkBytes = 3L,
        matched = 1,
        unmatched = emptyList(),
        ambiguous = emptyList(),
        missingVolume = 0,
        playStats = 0,
        droidDeck = emptyList(),
        skippedPlatforms = emptyList(),
        notes = emptyList()
    )

    private fun hazards(plan: CocoonImportPlan = plan(), linkCount: (File) -> Int? = CocoonWriteGuard::nioLinkCount): List<String> = CocoonWriteGuard.hazards(
        protectedTrees = listOfNotNull(plan.cocoonDir, plan.backup?.dir),
        sortieDir = sortie,
        safetyDir = File(sortie, "backups"),
        cardRoot = card,
        plan = plan,
        options = ImportOptions(),
        linkCount = linkCount
    )

    private fun symlink(at: File, to: File) {
        at.parentFile!!.mkdirs()
        Files.createSymbolicLink(at.toPath(), to.toPath())
    }

    private fun flagged(hazards: List<String>, f: File) = hazards.any { it.startsWith("${f.path}: ") }

    @Test
    fun `plain files outside Cocoon pass`() {
        assertEquals(emptyList<String>(), hazards())
    }

    /** ManifestCommit and UserStore open these names with FileOutputStream: a link there writes into Cocoon's folder. */
    @Test
    fun `a symbolic link at a file Sortie opens for writing is refused, even a dangling one`() {
        val staged = File(sortie, "systems.json.setup-new")
        val userTmp = File(sortie, "user.json.tmp")
        val journalTmp = File(sortie, ".sortie-setup-commit.tmp")
        symlink(staged, cocoonFile)
        symlink(userTmp, File(cocoon, "not-there-yet.json"))
        symlink(journalTmp, File(card, "elsewhere.json"))
        val h = hazards()
        assertTrue(h.toString(), flagged(h, staged) && flagged(h, userTmp) && flagged(h, journalTmp))
    }

    /** PlayLog appends to playlog.jsonl in place: a hard link would grow Cocoon's file too. */
    @Test
    fun `a hard link at a file Sortie writes in place is refused`() {
        val log = File(sortie, CocoonWriteGuard.PLAY_LOG)
        log.delete()
        Files.createLink(log.toPath(), cocoonFile.toPath())
        val h = hazards()
        assertTrue(h.toString(), flagged(h, log))
        assertTrue(h.toString(), h.single().contains("hard link"))
    }

    /** Renaming replaces only the directory entry, so the other name keeps its content: nothing to refuse. */
    @Test
    fun `a hard link at a file Sortie renames onto is not a hazard`() {
        val systems = File(sortie, "systems.json")
        Files.createLink(systems.toPath(), cocoonFile.toPath())
        assertEquals(emptyList<String>(), hazards())
    }

    @Test
    fun `an existing file whose links cannot be read is refused`() {
        val h = hazards(linkCount = { null })
        // only files opened for writing that exist are asked: playlog.jsonl here, not the absent temp files or user.json
        assertEquals(h.toString(), 1, h.size)
        assertTrue(h.toString(), flagged(h, File(sortie, CocoonWriteGuard.PLAY_LOG)))
    }

    @Test
    fun `artwork temp files and leftover safety-copy temp files are checked`() {
        val artTmp = CocoonWriteGuard.tempOf(art.target)
        symlink(artTmp, art.source)
        val leftover = File(sortie, "backups/${CocoonWriteGuard.SAFETY_PREFIX}-20260101-000000.json.tmp")
        symlink(leftover, cocoonFile)
        val h = hazards()
        assertTrue(h.toString(), flagged(h, artTmp) && flagged(h, leftover))
    }

    /** A folder Sortie writes into that resolves into the chosen backup (here through a link) is refused like Cocoon's own. */
    @Test
    fun `folders resolving into the backup are refused`() {
        val backupDir = tmp.newFolder("cocoon_backup_2026-01-01_00-00-00")
        val backup = CocoonBackup(backupDir, 0L, "3.07", emptyList(), emptyList(), emptyMap(), emptySet(), emptyMap())
        val safety = File(sortie, "backups")
        symlink(safety, backupDir)
        val h = hazards(plan(backup))
        assertTrue(h.toString(), flagged(h, safety))
    }
}
