package io.github.esko997.sortie.data.backup

import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.magazine.MagazineClipping
import io.github.esko997.sortie.data.manifest.ControllerSupport
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.GameBookmark
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.reference.ReferenceCard
import io.github.esko997.sortie.data.user.GameProgress
import io.github.esko997.sortie.mecha.MechaCabinetTier
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaSpinOutcome
import io.github.esko997.sortie.mecha.MechaSpinReceipt
import io.github.esko997.sortie.mecha.MechaState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFormatTest {
    private val user = UserData(
        favourites = setOf("snes/Super Metroid (USA).sfc", "shortcut:com.droiddeck.launcher/game-42"),
        // queued games are independent of favourites; an id the restoring device has not indexed yet must survive too
        wantToPlay = setOf("ps2/Armored Core 3 (USA).chd", "gba/Not On This Card.gba"),
        homeSort = "custom",
        browseChrome = "compact",
        layout = listOf("system:snes", "folder:want-to-play", "ps2/Armored Core 3 (USA).chd"),
        dock = listOf("app:org.ppsspp.ppsspp", "", "", "", ""),
        gameAnnotations = mapOf("ps2/Armored Core 3 (USA).chd" to GameAnnotation(ControllerSupport.GAMEPAD)),
        bookmarks = mapOf("ps2/Armored Core 3 (USA).chd" to GameBookmark("Mission 12, garage", 1_700_000_000_000)),
        bookmarkInvitation = false,
        gameProgress = mapOf("snes/Super Metroid (USA).sfc" to GameProgress("finished", "2026-10-24", "Worth returning to.")),
        magazineClippings = listOf(
            MagazineClipping(
                id = "clipping",
                issueId = "field-notes-001",
                articleId = "garage",
                block = 4,
                text = "Build around your preferred range.",
                note = "Try a lighter frame.",
                gameIds = listOf("ps2/Armored Core 3 (USA).chd")
            )
        ),
        referenceCards = mapOf(
            "ps2/Armored Core 3 (USA).chd" to listOf(ReferenceCard(id = "reference", title = "Garage", body = "# Loadout\nKeep spare energy."))
        )
    )

    private fun closed(id: String, start: Long, minutes: Long, rowId: Long = 0) =
        SessionEntity(rowId = rowId, gameId = id, profileId = "p", startedAt = start, endedAt = start + minutes * 60000)

    private fun valid(text: String) = parseBackup(text) as ParsedBackup.Valid

    @Test
    fun roundTripKeepsPreferencesAndClosedSessionsOnly() {
        val open = SessionEntity(rowId = 9, gameId = "psx/Running.chd", profileId = null, startedAt = 5_000_000, endedAt = null)
        val backup = buildBackup(user, listOf(closed("a", 1_000_000, 30, rowId = 1), open), null, 42, "thor", "0.0.1")
        val parsed = valid(encodeBackup(backup))
        assertEquals(user, parsed.backup.user)
        assertEquals(listOf(BackupSession("a", "p", 1_000_000, 1_000_000 + 30 * 60000)), parsed.backup.sessions)
        assertEquals("psx/Running.chd", parsed.backup.openSessionExcluded?.gameId)
        assertEquals(0, parsed.openIgnored)
    }

    /**
     * A backup from before bookmarks: nothing of its journal or notes is dropped silently. "Where was I?" becomes the bookmark,
     * the controller entry stays, and the journal and mapping notes come back as [ParsedBackup.Valid.legacy] for archiving.
     */
    @Test
    fun anOlderBackupsJournalAndNotesAreCarriedForArchiving() {
        val text = """{"format": "sortie-backup", "version": 2, "createdAt": 1, "sessions": [], "user": {
            "gameAnnotations": {"a/b": {"controller": "touch", "returnNote": "World 2", "mappingNotes": "L = map"},
                                "c/d": {"mappingNotes": "R = run"}},
            "journalEntries": [{"id": "e", "date": "2026-10-01", "text": "Long night."}]}}"""
        val parsed = valid(text)
        assertEquals(mapOf("a/b" to GameBookmark("World 2")), parsed.backup.user.bookmarks)
        assertEquals(mapOf("a/b" to GameAnnotation(ControllerSupport.TOUCH)), parsed.backup.user.gameAnnotations)
        val legacy = parsed.legacy!!
        assertEquals(1, legacy.journalEntries)
        assertEquals(setOf("a/b", "c/d"), legacy.games.keys)
        assertEquals("R = run", legacy.games["c/d"]?.mappingNotes)
        assertEquals("1 journal entry, 1 \"where was I?\" note and 2 mapping notes", legacy.describe())
    }

    /** A current backup has nothing to archive. */
    @Test
    fun aCurrentBackupHasNoLegacyNotes() {
        assertNull(valid(encodeBackup(buildBackup(user, emptyList(), null, 1, "thor", "0.0.1"))).legacy)
    }

    @Test
    fun repeatRestoreAddsNothingAndDeviceSessionsWin() {
        val incoming = listOf(BackupSession("a", "p", 1_000, 1_000 + 60000), BackupSession("b", null, 2_000, 2_000 + 120000))
        // the device already has "a" (different row id, a later end) and a session newer than the backup
        val device = listOf(closed("a", 1_000, 5, rowId = 77), closed("c", 9_000_000, 10, rowId = 78))
        val first = planSessionMerge(incoming, device)
        assertEquals(listOf("b"), first.toInsert.map { it.gameId })
        assertEquals(1, first.alreadyPresent)
        assertEquals(2L, first.addedMinutes)
        val afterFirst = device + first.toInsert.mapIndexed { i, s -> s.copy(rowId = 100L + i) }
        val second = planSessionMerge(incoming, afterFirst)
        assertTrue(second.toInsert.isEmpty())
        assertEquals(2, second.alreadyPresent)
    }

    @Test
    fun unfinishedSessionsInTheFileAreNeverImported() {
        val sessions = listOf(BackupSession("a", null, 1_000, null), BackupSession("b", null, 2_000, 3_000))
        val text = encodeBackup(BackupFile(createdAt = 1, user = user, sessions = sessions))
        val parsed = valid(text)
        assertEquals(1, parsed.openIgnored)
        assertEquals(listOf("b"), planSessionMerge(parsed.backup.sessions, emptyList()).toInsert.map { it.gameId })
    }

    @Test
    fun malformedTruncatedForeignAndNewerFilesAreRejected() {
        val good = encodeBackup(buildBackup(user, listOf(closed("a", 1_000, 1)), null, 1, "thor", "0.0.1"))
        val rejected = listOf(
            good.substring(0, good.length / 2),
            "",
            "[1, 2]",
            """{"favourites": []}""",
            """{"format": "sortie-backup"}""",
            """{"format": "sortie-backup", "version": ${BackupFile.VERSION + 1}, "createdAt": 1, "user": {}, "sessions": []}""",
            """{"format": "sortie-backup", "version": 0, "createdAt": 1, "user": {}, "sessions": []}""",
            """{"format": "sortie-backup", "version": -1, "createdAt": 1, "user": {"journalEntries": [{"text": "x"}]}, "sessions": []}""",
            """{"format": "sortie-backup", "version": 1, "user": {}, "sessions": []}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": {}, "sessions": [{"gameId": "a", "startedAt": 5000, "endedAt": 10}]}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": {}, "sessions": [{"gameId": "", "startedAt": 5000, "endedAt": 6000}]}"""
        )
        rejected.forEach { assertTrue("accepted: $it", parseBackup(it) is ParsedBackup.Invalid) }
    }

    /** Well-formed JSON that lacks a section must not pass as an all-defaults backup (restoring it would wipe every preference). */
    @Test
    fun backupsMissingASectionAreRejected() {
        val rejected = listOf(
            """{"format": "sortie-backup", "version": 1, "createdAt": 1}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "sessions": []}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": {"favourites": []}}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": null, "sessions": []}""",
            """{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": [], "sessions": {}}"""
        )
        rejected.forEach { assertTrue("accepted: $it", parseBackup(it) is ParsedBackup.Invalid) }
    }

    @Test
    fun outOfRangePreferencesAreClamped() {
        val text = """{"format": "sortie-backup", "version": 1, "createdAt": 1, "sessions": [],""" +
            """ "user": {"gridColumns": 99, "gridRows": 0, "layout": ["x", "x"], "browseChrome": "sideways"}}"""
        val u = valid(text).backup.user
        assertEquals(10, u.gridColumns)
        assertEquals(2, u.gridRows)
        assertEquals(listOf("x"), u.layout)
        assertEquals("auto", u.browseChrome)
        assertNull(valid(text).backup.openSessionExcluded)
    }

    private val companion = MechaState(
        name = "Rivet",
        equipped = MechaSlot.entries.associateWith { "yard_${it.name.lowercase()}_1" },
        owned = MechaSlot.entries.mapTo(LinkedHashSet()) { "yard_${it.name.lowercase()}_1" } + "survey_head_2",
        salvage = 4,
        bond = 17,
        charge = 70,
        condition = 85,
        sleeping = false,
        origins = mapOf("survey_head_2" to "Salvage run #1"),
        initializedAt = 500,
        creditedMinutes = 12,
        pendingMinutes = 2,
        salvageRuns = 1,
        processedSessions = setOf("a@1000")
    )

    @Test
    fun theCompanionSaveRoundTripsWhole() {
        val backup = buildBackup(user, listOf(closed("a", 1_000, 12)), companion, 42, "dmg", "0.0.1")
        assertEquals(2, backup.version)
        val parsed = valid(encodeBackup(backup))
        assertEquals(companion, parsed.backup.mecha)
    }

    @Test
    fun versionOneFilesStillReadWithoutACompanion() {
        val parsed = valid("""{"format": "sortie-backup", "version": 1, "createdAt": 1, "user": {}, "sessions": []}""")
        assertNull(parsed.backup.mecha)
    }

    @Test
    fun aMalformedCompanionSectionRejectsTheWholeFile() {
        val rejected = listOf(
            """{"format": "sortie-backup", "version": 2, "createdAt": 1, "user": {}, "sessions": [], "mecha": []}""",
            """{"format": "sortie-backup", "version": 2, "createdAt": 1, "user": {}, "sessions": [], "mecha": {"name": "Rivet"}}"""
        )
        rejected.forEach { assertTrue("accepted: $it", parseBackup(it) is ParsedBackup.Invalid) }
    }

    private fun withReceipt() = companion.copy(
        version = 2,
        ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.ALLOY),
        equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.ALLOY),
        cabinetSpins = 1,
        lastSpin = MechaSpinReceipt(
            sequence = 1,
            tier = MechaCabinetTier.ALLOY,
            reels = listOf(2, 2, 2),
            outcome = MechaSpinOutcome.REWARD,
            returnedCredits = 3,
            finish = MechaFinish.ALLOY
        )
    )

    @Test
    fun versionTwoCompanionKeepsFinishAndResolvedReceiptWithoutChangingBackupWrapper() {
        val save = withReceipt()
        val backup = buildBackup(user, emptyList(), save, 42, "dmg", "0.0.1")
        assertEquals(2, backup.version)
        assertEquals(save, valid(encodeBackup(backup)).backup.mecha)
    }

    @Test
    fun legacyCompanionWithoutItsVersionStillParsesAsVersionOne() {
        val backup = buildBackup(user, emptyList(), companion, 42, "dmg", "0.0.1")
        val root = backupJson.parseToJsonElement(encodeBackup(backup)).jsonObject
        val legacy = JsonObject(
            root.getValue("mecha").jsonObject - setOf(
                "version", "ownedFinishes", "equippedFinishes", "cabinetSpins", "lastSpin",
                "ownedThemes", "selectedTheme", "hangarIntroSeen", "arcadeRuns", "activeArcade", "arcadeBest"
            )
        )
        assertEquals(companion, valid(JsonObject(root + ("mecha" to legacy)).toString()).backup.mecha)
    }

    @Test
    fun futureVersionsInvalidFinishesAndInconsistentReceiptsRejectTheWholeBackup() {
        val save = withReceipt()
        val invalid = listOf(
            save.copy(version = 99),
            save.copy(version = 1),
            save.copy(ownedFinishes = setOf(MechaFinish.PLAIN)),
            save.copy(cabinetSpins = 2),
            save.copy(lastSpin = save.lastSpin!!.copy(returnedCredits = 10)),
            save.copy(lastSpin = save.lastSpin!!.copy(reels = listOf(0, 1, 2))),
            save.copy(lastSpin = save.lastSpin!!.copy(finish = MechaFinish.HOLO)),
            save.copy(lastSpin = save.lastSpin!!.copy(partId = "yard_head_1"))
        )
        invalid.forEach { broken ->
            val text = encodeBackup(buildBackup(user, emptyList(), broken, 42, "dmg", "0.0.1"))
            assertTrue("accepted: $broken", parseBackup(text) is ParsedBackup.Invalid)
        }
        val text = encodeBackup(buildBackup(user, emptyList(), save, 42, "dmg", "0.0.1"))
        assertTrue(parseBackup(text.replace("\"ALLOY\"", "\"UNKNOWN_FINISH\"")) is ParsedBackup.Invalid)
        assertTrue(parseBackup(text.replace("\"REWARD\"", "\"UNKNOWN_OUTCOME\"")) is ParsedBackup.Invalid)
    }
}
