package io.github.esko997.sortie.data.cocoon

import io.github.esko997.sortie.data.backup.planSessionMerge
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.manifest.FolderDef
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.setup.CommitIo
import io.github.esko997.sortie.data.setup.LibraryPresets
import io.github.esko997.sortie.data.setup.LibrarySetupStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CocoonImportDeltaTest {
    private val cocoon = File("/storage/emulated/0/Cocoon")

    private fun plan(
        favourites: List<String> = emptyList(),
        hidden: List<String> = emptyList(),
        overrides: Map<String, String> = emptyMap(),
        sessions: List<SessionPlan> = emptyList(),
        folders: List<FolderPlan> = emptyList()
    ) = CocoonImportPlan(
        cocoonDir = cocoon,
        backup = null,
        newSystems = emptyList(),
        mediaDirs = emptyMap(),
        systemProfiles = emptyList(),
        favourites = favourites,
        hidden = hidden,
        profileOverrides = overrides,
        sessions = sessions,
        folders = folders,
        artwork = emptyList(),
        artworkBytes = 0,
        matched = 0,
        unmatched = emptyList(),
        ambiguous = emptyList(),
        missingVolume = 0,
        playStats = 0,
        droidDeck = emptyList(),
        skippedPlatforms = emptyList(),
        notes = emptyList()
    )

    private fun session(gameId: String, start: Long, minutes: Long = 30) = SessionPlan(gameId, "ra-mgba", start, start + minutes * 60_000)

    private val full = plan(
        favourites = listOf("gba/a.gba", "gba/b.gba", "app:com.example.game"),
        hidden = listOf("gba/c.gba"),
        overrides = mapOf("gba/a.gba" to "ra-mgba", "gba/b.gba" to "ra-vba"),
        sessions = listOf(session("gba/a.gba", 1_000_000), session("gba/b.gba", 9_000_000, 60)),
        folders = listOf(
            FolderPlan(FolderDef(id = "cocoon-1", label = "Shmups", include = listOf("gba/a.gba", "gba/b.gba")), 0),
            FolderPlan(FolderDef(id = "cocoon-2", label = "RPGs", include = listOf("gba/c.gba")), 0)
        )
    )

    /** What the importer leaves behind: the same merges [ImportDelta] compares with, and the sessions the play log would hold. */
    private class Library(val user: UserData, val folders: List<FolderDef>, val sessions: List<SessionEntity>)

    private fun imported(plan: CocoonImportPlan, library: Library): Library {
        val existing = library.folders.associateBy { it.id }
        val saved = plan.folders.map { mergeImportedFolder(existing[it.def.id], it.def, it.def.include) }
        val merge = planSessionMerge(importedSessions(plan.sessions) { true }, library.sessions)
        return Library(
            mergeImportedUser(library.user, plan.favourites, plan.hidden, plan.profileOverrides),
            library.folders.filter { f -> saved.none { it.id == f.id } } + saved,
            library.sessions + merge.toInsert
        )
    }

    private fun delta(plan: CocoonImportPlan, library: Library) = ImportDelta.of(plan, library.user, library.folders, library.sessions)

    @Test
    fun firstImportIntoAnEmptyLibraryAddsEverything() {
        val d = delta(full, Library(UserData(), emptyList(), emptyList()))
        assertEquals(DeltaCount(3, 0), d.favourites)
        assertEquals(DeltaCount(1, 0), d.hidden)
        assertEquals(DeltaCount(2, 0), d.overrides)
        assertEquals(SessionDelta(2, 0, 90), d.sessions)
        assertEquals(FolderDelta(create = 2, update = 0, unchanged = 0, members = 3), d.folders)
    }

    @Test
    fun aRerunAfterTheImportPreviewsNothingNew() {
        val first = imported(full, Library(UserData(), emptyList(), emptyList()))
        val d = delta(full, first)
        assertEquals(DeltaCount(0, 3), d.favourites)
        assertEquals(DeltaCount(0, 1), d.hidden)
        assertEquals(DeltaCount(0, 2), d.overrides)
        assertEquals(SessionDelta(0, 2, 0), d.sessions)
        assertEquals(FolderDelta(create = 0, update = 0, unchanged = 2, members = 0), d.folders)
        // and applying it again really changes nothing
        val second = imported(full, first)
        assertEquals(first.user, second.user)
        assertEquals(first.folders, second.folders)
        assertEquals(first.sessions, second.sessions)
    }

    @Test
    fun partlyPresentDataCountsOnlyTheDifference() {
        val library = Library(
            user = UserData(
                favourites = setOf("gba/a.gba", "snes/x.sfc"),
                hidden = setOf("gba/c.gba"),
                // the user's own choice for b differs from Cocoon's: it stays, so b is not an addition
                profileOverrides = mapOf("gba/b.gba" to "ra-mgba")
            ),
            // an existing folder of the same id with a stray duplicate and one of the two members
            folders = listOf(FolderDef(id = "cocoon-1", label = "Mine", include = listOf("gba/a.gba", "gba/a.gba", "snes/x.sfc"))),
            // same game and start, different end: the merge identity is (game, start), so it is already here
            sessions = listOf(SessionEntity(gameId = "gba/a.gba", profileId = null, startedAt = 1_000_000, endedAt = 1_000_001))
        )
        val d = delta(full, library)
        assertEquals(DeltaCount(2, 1), d.favourites)
        assertEquals(DeltaCount(0, 1), d.hidden)
        assertEquals(DeltaCount(1, 1), d.overrides)
        assertEquals(SessionDelta(1, 1, 60), d.sessions)
        assertEquals(FolderDelta(create = 1, update = 1, unchanged = 0, members = 2), d.folders)

        // the preview's counts are exactly what the merge then adds, and the user's choice and folder name survive
        val after = imported(full, library)
        assertEquals(library.user.favourites.size + d.favourites.add, after.user.favourites.size)
        assertEquals(library.user.profileOverrides.size + d.overrides.add, after.user.profileOverrides.size)
        assertEquals("ra-mgba", after.user.profileOverrides["gba/b.gba"])
        assertEquals(library.sessions.size + d.sessions.add, after.sessions.size)
        val mine = after.folders.single { it.id == "cocoon-1" }
        assertEquals("Mine", mine.label)
        assertEquals(listOf("gba/a.gba", "snes/x.sfc", "gba/b.gba"), mine.include)
        assertEquals(DeltaCount(0, 3), delta(full, after).favourites)
    }

    @Test
    fun duplicateSessionsInsideTheBackupCountOnce() {
        val twice = plan(sessions = listOf(session("gba/a.gba", 5_000), session("gba/a.gba", 5_000, 45)))
        assertEquals(SessionDelta(1, 1, 30), delta(twice, Library(UserData(), emptyList(), emptyList())).sessions)
    }

    @Test
    fun unknownSessionProfilesAreDroppedNotTheSession() {
        val sessions = importedSessions(listOf(session("gba/a.gba", 1), SessionPlan("gba/b.gba", "gone", 2, 3))) { it == "ra-mgba" }
        assertEquals(listOf("ra-mgba", null), sessions.map { it.profileId })
    }

    private fun store(dir: File) = LibrarySetupStore(dir, {}, CommitIo(), "test-device")

    @Test
    fun aLibraryChangedAfterThePreviewIsNotImported() = runBlocking {
        val volume = Files.createTempDirectory("sortie-volume").toFile()
        val s = store(File(volume, "sortie"))
        val gba = LibraryPresets.preset("gba")!!
        val previewed = s.saveSystem(s.load(), gba.system.copy(roots = listOf("${volume.path}/roms/gba")), gba.profiles)
        assertEquals(ConfirmCheck.Ready, ConfirmCheck.of(previewed.fingerprint, s.load()))

        // the user adds their own system on another folder in Library setup while the preview waits
        val own = gba.system.copy(id = "gameboy-advance", roots = listOf("${volume.path}/roms/gba-hacks"), profiles = gba.system.profiles)
        s.saveSystem(s.load(), own, emptyList())
        assertEquals(ConfirmCheck.Changed, ConfirmCheck.of(previewed.fingerprint, s.load()))
    }

    @Test
    fun anUnwritableConfigurationBlocksEvenAnUnchangedPreview() = runBlocking {
        val volume = Files.createTempDirectory("sortie-volume").toFile()
        val dir = File(volume, "sortie")
        val s = store(dir)
        val gba = LibraryPresets.preset("gba")!!
        s.saveSystem(s.load(), gba.system.copy(roots = listOf("${volume.path}/roms/gba")), gba.profiles)
        File(dir, "launchers.json").writeText("{\"profiles\": [")
        val broken = s.load()
        val check = ConfirmCheck.of(broken.fingerprint, broken)
        assertTrue(check is ConfirmCheck.Blocked)
        assertEquals(broken.blocking, (check as ConfirmCheck.Blocked).reasons)
        assertTrue(check.reasons.single().startsWith("launchers.json"))
    }

    @Test
    fun aBrokenOptionalFileIsAWarningNotABlocker() = runBlocking {
        val volume = Files.createTempDirectory("sortie-volume").toFile()
        val dir = File(volume, "sortie")
        val s = store(dir)
        val gba = LibraryPresets.preset("gba")!!
        s.saveSystem(s.load(), gba.system.copy(roots = listOf("${volume.path}/roms/gba")), gba.profiles)
        File(dir, "guide.json").writeText("not json")
        val config = s.load()
        assertTrue(config.errors.isNotEmpty())
        assertEquals(ConfirmCheck.Ready, ConfirmCheck.of(config.fingerprint, config))
    }

    @Test
    fun aFirstImportWithoutAnyConfigurationIsAllowed() = runBlocking {
        val volume = Files.createTempDirectory("sortie-volume").toFile()
        val empty = store(File(volume, "sortie")).load()
        assertEquals(ConfirmCheck.Ready, ConfirmCheck.of(empty.fingerprint, empty))
    }
}
