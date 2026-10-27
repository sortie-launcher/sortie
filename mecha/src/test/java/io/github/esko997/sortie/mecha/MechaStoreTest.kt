package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.INIT
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.parts
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.session
import io.github.esko997.sortie.ui.SortieButtonStyle
import io.github.esko997.sortie.ui.SortieFolderIcons
import io.github.esko997.sortie.ui.SortiePalette
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaStoreTest {
    private val dir: File = Files.createTempDirectory("sortie-mecha").toFile()
    private val file = File(dir, "mecha/state.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Stands in for the host's sessions: what SortieApp maps from GameDao.completedSessionsFrom, re-emitted on every change. */
    private val table = MutableStateFlow<List<PlaySession>>(emptyList())

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun store(now: Long = INIT, catalog: () -> List<MechaPart> = { parts }, random: Random = Random.Default) = MechaStore(
        locate = { file },
        loadCatalog = catalog,
        sessions = { since -> table.map { rows -> rows.filter { it.startedAt >= since && it.endedAt != null && !it.abandoned } } },
        clock = { now },
        scope = scope,
        random = random
    )

    private suspend fun MechaStore.settled(): MechaView {
        withTimeout(5_000) { view.first { it.state != null || it.error != null } }
        idle()
        return view.value
    }

    private fun onDisk(): MechaState = decodeMechaState(file.readText())

    @Test
    fun firstOpenSavesTheStarterHangarAndLaterOpensReadIt() = runBlocking {
        val first = store().settled()
        val state = first.state!!
        assertEquals(state, onDisk())
        assertEquals(INIT, state.initializedAt)
        assertEquals(200, first.parts.size)
        assertNotNull(first.message)

        val again = store(now = INIT + 10_000).settled()
        assertEquals(state, again.state)
        assertNull(again.error)
    }

    @Test
    fun actionsPersistOnlyValidChanges() = runBlocking {
        val s = store()
        s.settled()
        s.equip("survey_head_2")
        s.idle()
        assertNotNull(s.view.value.error)
        assertEquals("yard_head_1", onDisk().equipped[MechaSlot.HEAD])

        s.equip("survey_head_1")
        s.rename(" Bolt ")
        s.care(MechaCare.REST)
        s.setMotion(false)
        s.buyPart("survey_arms_2")
        s.idle()
        val saved = onDisk()
        assertEquals(s.view.value.state, saved)
        assertEquals("survey_head_1", saved.equipped[MechaSlot.HEAD])
        assertEquals("Bolt", saved.name)
        assertTrue(saved.sleeping)
        assertFalse(saved.motion)
        assertEquals(MECHA_STARTER_CREDITS - MECHA_PART_PRICE, saved.salvage)
        assertEquals(13, saved.owned.size)
    }

    @Test
    fun playIsCreditedOnceWithItsBookkeepingAcrossRestartsAndReimports() = runBlocking {
        table.value = listOf(session("snes/before.sfc", INIT - 60 * 60_000L, 90))
        val s = store()
        s.settled()
        s.refreshSessions()
        s.idle()
        assertEquals(MECHA_STARTER_CREDITS, s.view.value.state!!.salvage)

        table.value = table.value + session("snes/after.sfc", INIT + 60_000L, 12)
        withTimeout(5_000) { s.view.first { it.state?.salvage == MECHA_STARTER_CREDITS + 2 } }
        s.idle()
        val saved = onDisk()
        assertEquals(MECHA_STARTER_CREDITS + 2, saved.salvage)
        assertEquals(2, saved.pendingMinutes)
        assertEquals(setOf("snes/after.sfc@${INIT + 60_000L}"), saved.processedSessions)

        // a backup restore re-inserts the same session (under a new row id, which never reaches the store); a restart reads it all again
        table.value = table.value.map { it.copy() }
        val restarted = store(now = INIT + 3_600_000L)
        restarted.settled()
        restarted.refreshSessions()
        restarted.idle()
        assertEquals(saved, restarted.view.value.state)
        assertEquals(saved, onDisk())
    }

    @Test
    fun anUnreadableSaveIsReportedAndNeverOverwritten() = runBlocking {
        file.parentFile!!.mkdirs()
        val bytes = "{\"name\":\"Mica\",\"owned\":[".toByteArray()
        file.writeBytes(bytes)
        table.value = listOf(session("snes/after.sfc", INIT + 60_000L, 30))
        val s = store()
        val view = s.settled()
        assertNull(view.state)
        assertTrue(view.error!!.contains(file.path))

        s.rename("Bolt")
        s.equip("yard_head_1")
        s.care(MechaCare.CHARGE)
        s.spin(MechaSlotStake.ONE, 0)
        s.setEnabled(false)
        s.refreshSessions()
        s.idle()
        assertArrayEquals(bytes, file.readBytes())
        assertFalse(File(dir, "mecha/state.json.tmp").exists())
        assertNull(s.view.value.state)

        s.startFresh()
        s.idle()
        val kept = file.parentFile!!.listFiles()!!.single { it.name.startsWith("state.damaged-") }
        assertArrayEquals(bytes, kept.readBytes())
        // the fresh hangar counts play from its own start: the 30 minutes after it, once
        val fresh = withTimeout(5_000) { s.view.first { it.state?.processedSessions?.size == 1 } }.state!!
        s.idle()
        assertEquals(fresh, onDisk())
        assertEquals(12, fresh.owned.size)
        assertEquals(MECHA_STARTER_CREDITS + 6, fresh.salvage)
    }

    @Test
    fun aConsistencyProblemIsAlsoPreserved() = runBlocking {
        store().settled()
        val good = onDisk()
        val bad = good.copy(equipped = good.equipped + (MechaSlot.BODY to "courier_body_2"))
        val text = Json { encodeDefaults = true }.encodeToString(MechaState.serializer(), bad)
        file.writeText(text)
        val s = store()
        val view = s.settled()
        assertNull(view.state)
        assertNotNull(view.error)
        s.care(MechaCare.TUNE)
        s.idle()
        assertEquals(text, file.readText())
    }

    @Test
    fun aFailedSaveKeepsThePreviousStateAndBytes() = runBlocking {
        val s = store()
        val before = s.settled().state!!
        val bytes = file.readBytes()
        val tmp = File(dir, "mecha/state.json.tmp")
        assertTrue(tmp.mkdir()) // the temp file can't be written
        s.care(MechaCare.CHARGE)
        s.idle()
        assertEquals(before, s.view.value.state)
        assertTrue(s.view.value.error!!.startsWith("Not saved"))
        assertArrayEquals(bytes, file.readBytes())

        assertTrue(tmp.delete())
        s.care(MechaCare.CHARGE)
        s.idle()
        assertNull(s.view.value.error)
        assertTrue(onDisk().charge > before.charge)
    }

    @Test
    fun anIncompleteCatalogWritesNothing() = runBlocking {
        val s = store(catalog = { throw IOException("mecha/courier/catalog.json is missing from the app") })
        val view = s.settled()
        assertNull(view.state)
        assertTrue(view.error!!.contains("courier"))
        s.startFresh()
        s.buyPart("survey_arms_2")
        s.idle()
        assertFalse(file.exists())
    }

    @Test
    fun petsArePersistedAndRateLimitedAcrossRestarts() = runBlocking {
        val s = store()
        val before = s.settled().state!!
        s.pet()
        s.idle()
        val once = onDisk()
        assertEquals(before.bond + 1, once.bond)
        val petFile = File(dir, "mecha/pet.txt")
        assertEquals(INIT.toString(), petFile.readText())
        // Pet timing remains outside the companion save.
        assertEquals(4, once.version)
        assertFalse(file.readText().contains("pet"))
        val bytes = file.readBytes()
        repeat(5) { s.pet() }
        s.idle()
        // the clock has not moved: the same window, so nothing more is earned or written
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(once, s.view.value.state)
        // a restart inside the window still remembers it; after the window a pet counts again
        val restarted = store(now = INIT + MECHA_PET_BOND_MS - 1)
        restarted.settled()
        restarted.pet()
        restarted.idle()
        assertArrayEquals(bytes, file.readBytes())
        val later = store(now = INIT + MECHA_PET_BOND_MS)
        later.settled()
        later.pet()
        later.idle()
        assertEquals(before.bond + 2, onDisk().bond)
        assertEquals((INIT + MECHA_PET_BOND_MS).toString(), petFile.readText())
    }

    /** Another device's hangar: its own folder, sessions table and clock. */
    private inner class Device(name: String, val now: Long) {
        val folder = File(dir, name)
        val table = MutableStateFlow<List<PlaySession>>(emptyList())
        val store = MechaStore(
            locate = { File(folder, "mecha/state.json") },
            loadCatalog = { parts },
            sessions = { since -> table.map { rows -> rows.filter { it.startedAt >= since && it.endedAt != null && !it.abandoned } } },
            clock = { now },
            scope = scope
        )

        fun disk(): MechaState = Json.decodeFromString(MechaState.serializer(), File(folder, "mecha/state.json").readText())
    }

    @Test
    fun aBackupRoundTripRestoresTheSaveAndCreditsNoSessionTwice() = runBlocking {
        // the DMG: 20 minutes of play, a rename and a part purchase, then a backup
        val dmg = Device("dmg", INIT)
        dmg.store.settled()
        val played = listOf(session("gba/a.gba", INIT + 60_000L, 20))
        dmg.table.value = played
        withTimeout(5_000) { dmg.store.view.first { it.state?.processedSessions?.size == 1 } }
        dmg.store.rename("Rivet")
        dmg.store.buyPart("survey_head_2")
        dmg.store.idle()
        val save = dmg.store.backupSave()!!
        assertEquals("Rivet", save.name)
        assertEquals(13, save.owned.size)
        assertEquals(MECHA_STARTER_CREDITS + 4 - MECHA_PART_PRICE, save.salvage)

        // the Nova: its own hangar with its own credited play, restoring the DMG's backup
        val nova = Device("nova", INIT + 1_000L)
        nova.store.settled()
        val own = listOf(session("snes/b.sfc", INIT + 120_000L, 10))
        nova.table.value = own
        withTimeout(5_000) { nova.store.view.first { it.state?.processedSessions?.size == 1 } }
        assertNull(nova.store.restoreProblem(save))
        // the restore replaces the companion first, then merges the DMG's history into the table
        val done = nova.store.restore(save, merged = played)
        assertTrue(done.replaced)
        nova.table.value = own + played
        nova.store.refreshSessions()
        nova.store.idle()
        val restored = nova.store.view.value.state!!
        assertEquals(restored, nova.disk())
        assertEquals("Rivet", restored.name)
        assertEquals(save.owned, restored.owned)
        assertEquals(save.equipped, restored.equipped)
        // neither the DMG's 20 minutes nor the Nova's own 10 pay again
        assertEquals(save.salvage, restored.salvage)
        assertEquals(save.processedSessions + "snes/b.sfc@${INIT + 120_000L}", restored.processedSessions)

        // restoring the same backup again changes nothing
        nova.store.restore(save, merged = emptyList())
        nova.store.refreshSessions()
        nova.store.idle()
        assertEquals(restored, nova.store.view.value.state)

        // new play after the restore counts, once
        nova.table.value = nova.table.value + session("snes/c.sfc", INIT + 3_600_000L, 5)
        withTimeout(5_000) { nova.store.view.first { it.state?.salvage == save.salvage + 1 } }
        nova.store.refreshSessions()
        nova.store.idle()
        assertEquals(save.salvage + 1, nova.disk().salvage)
    }

    @Test
    fun aFailedRestoreIsUndoneAndABackupWithoutAHangarPaysNothing() = runBlocking {
        val s = store()
        val before = s.settled().state!!
        val other = before.copy(name = "Bolt", salvage = 9)
        val done = s.restore(other, merged = emptyList())
        assertEquals("Bolt", onDisk().name)
        s.undoRestore(done)
        assertEquals(before, onDisk())
        assertEquals(before, s.view.value.state)

        // no hangar in the backup: this one stays and the merged sessions are marked as paid for elsewhere
        val merged = listOf(session("psx/elsewhere.chd", INIT + 60_000L, 30))
        assertFalse(s.restore(null, merged).replaced)
        table.value = merged
        s.refreshSessions()
        s.idle()
        assertEquals(before.salvage, s.view.value.state!!.salvage)
        assertEquals(before.salvage, onDisk().salvage)

        // an invalid save is refused and changes nothing
        val bytes = file.readBytes()
        val bad = before.copy(owned = before.owned + "mystery_head_9")
        assertNotNull(s.restoreProblem(bad))
        assertTrue(runCatching { s.restore(bad, emptyList()) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
    }

    private fun writeState(state: MechaState) {
        file.parentFile!!.mkdirs()
        file.writeText(Json { encodeDefaults = true }.encodeToString(MechaState.serializer(), state))
    }

    /** Records actual RNG requests; domain outcomes still run through the real store and atomic file writer. */
    private class Draws(private vararg val values: Int) : Random() {
        val bounds = mutableListOf<Int>()

        override fun nextBits(bitCount: Int): Int = error("Unexpected unbounded random draw")

        override fun nextInt(until: Int): Int {
            val value = values[bounds.size]
            bounds += until
            require(value in 0 until until)
            return value
        }
    }

    @Test
    fun legacySaveWithoutVersionUpgradesAtomicallyAndKeepsAllHistory() = runBlocking {
        val legacy = starterMechaState(parts, INIT).copy(
            version = 1,
            name = "Old Bolt",
            salvage = 42,
            salvageRuns = 7,
            owned = starterMechaState(parts, INIT).owned + "survey_head_2",
            charge = 43,
            condition = 61,
            bond = 73,
            pendingMinutes = 3,
            processedSessions = setOf("gba/old.gba@${INIT + 60_000L}")
        )
        val encoded = Json { encodeDefaults = true }.encodeToString(MechaState.serializer(), legacy)
        val oldFields = Json.parseToJsonElement(encoded).jsonObject - setOf(
            "version", "ownedPaints", "equippedPaints", "cabinetSpins", "lastSpin", "legacyLastSpin",
            "ownedThemes", "selectedTheme", "hangarIntroSeen", "arcadeRuns", "activeArcade", "arcadeBest",
            "ownedFolderIcons", "selectedFolderIcons", "ownedButtonStyles", "selectedButtonStyle"
        )
        file.parentFile!!.mkdirs()
        file.writeText(JsonObject(oldFields).toString())
        assertEquals(1, onDisk().version)
        table.value = listOf(session("gba/old.gba", INIT + 60_000L, 20))
        val s = store()
        s.settled()
        s.refreshSessions()
        s.idle()
        assertEquals(legacy.copy(version = 4), s.view.value.state)
        assertEquals(s.view.value.state, onDisk())
    }

    @Test
    fun failedLegacyUpgradeNeverPublishesOrReplacesTheOriginal() = runBlocking {
        val legacy = starterMechaState(parts, INIT).copy(version = 1, salvage = 37, name = "Keep me")
        writeState(legacy)
        val bytes = file.readBytes()
        val tmp = File(file.parentFile, "state.json.tmp")
        assertTrue(tmp.mkdir())
        val s = store()
        assertNull(s.settled().state)
        assertTrue(s.view.value.error!!.contains("upgrade"))
        assertFalse(s.view.value.canStartFresh)
        s.buyPart("survey_head_2")
        s.startFresh()
        s.idle()
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(runCatching { s.backupSave() }.isFailure)
        assertTrue(tmp.delete())
        val replacement = starterMechaState(parts, INIT).copy(name = "Replacement")
        assertNotNull(s.restoreProblem(replacement))
        assertTrue(runCatching { s.restore(replacement, emptyList()) }.isFailure)
        assertTrue(runCatching { s.restore(null, emptyList()) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        s.retryOpen()
        s.idle()
        assertEquals(legacy.copy(version = 4), s.view.value.state)
        assertEquals(s.view.value.state, s.backupSave())
        assertEquals(s.view.value.state, onDisk())
    }

    @Test
    fun purchasesFinishesAndFreeBuildPersistWithoutDuplicateCharges() = runBlocking {
        val before = starterMechaState(parts, INIT).copy(salvage = 20)
        writeState(before)
        val s = store()
        s.settled()
        repeat(2) { s.buyPart("survey_head_2") }
        val paint = MechaPaint(finish = MechaFinish.ALLOY)
        repeat(2) { s.buyPaint(paint) }
        s.applyPaint(paint, MechaSlot.HEAD)
        val build = mutableMapOf(
            MechaSlot.HEAD to "survey_head_2",
            MechaSlot.LEGS to "survey_legs_1",
            MechaSlot.BODY to "yard_body_1",
            MechaSlot.ARMS to "yard_arms_1"
        )
        s.fitBuild(build)
        s.idle()
        val saved = onDisk()
        assertEquals(20 - MECHA_PART_PRICE - paint.price, saved.salvage)
        assertEquals(before.owned + "survey_head_2", saved.owned)
        assertEquals(setOf(MechaPaint(), paint), saved.ownedPaints)
        assertEquals(paint, saved.equippedPaints[MechaSlot.HEAD])
        assertEquals(build, saved.equipped)
        assertEquals(saved, store().settled().state)
        s.applyPaint(MechaPaint(), MechaSlot.HEAD)
        s.idle()
        assertEquals(saved.salvage, onDisk().salvage)
        assertEquals(MechaPaint(), onDisk().equippedPaints[MechaSlot.HEAD] ?: MechaPaint())
    }

    @Test
    fun queuedSpinsResolveOnceAndStaleClicksNeverSpendOrDrawAgain() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val draws = Draws(0)
        val random = object : Random() {
            override fun nextBits(bitCount: Int): Int = error("Unexpected unbounded random draw")

            override fun nextInt(until: Int): Int {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return draws.nextInt(until)
            }
        }
        val s = store(random = random)
        val before = s.settled().state!!
        val bytes = file.readBytes()
        try {
            s.spin(MechaSlotStake.ONE, 0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            s.spin(MechaSlotStake.ONE, 0)
            assertTrue(s.view.value.busy)
            assertEquals(before, s.view.value.state)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            release.countDown()
        }
        s.idle()
        val saved = onDisk()
        assertFalse(s.view.value.busy)
        assertEquals(listOf(100), draws.bounds)
        assertEquals(1, saved.cabinetSpins)
        assertEquals(before.salvage + 4, saved.salvage)
        assertEquals(before.owned, saved.owned)
        assertEquals(MechaSpinOutcome.JACKPOT, saved.lastSpin!!.outcome)
        assertEquals(saved, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertEquals(saved, store().settled().state)
    }

    @Test
    fun failedCommerceWritesKeepWalletInventoryAndReceiptTogether() = runBlocking {
        writeState(starterMechaState(parts, INIT).copy(salvage = 20))
        val draws = Draws(0)
        val s = store(random = draws)
        val before = s.settled().state!!
        val bytes = file.readBytes()
        val tmp = File(file.parentFile, "state.json.tmp")
        assertTrue(tmp.mkdir())
        s.buyPart("survey_head_2")
        s.buyPaint(MechaPaint(finish = MechaFinish.ALLOY))
        s.spin(MechaSlotStake.FIVE, 0)
        s.idle()
        assertEquals(before, s.view.value.state)
        assertEquals(listOf(100), draws.bounds)
        assertFalse(s.view.value.busy)
        assertTrue(s.view.value.error!!.startsWith("Not saved"))
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(tmp.delete())
        assertEquals(before, store().settled().state)
    }

    @Test
    fun ineligibleSpinsAndOverspendingNeverDrawOrWrite() = runBlocking {
        writeState(starterMechaState(parts, INIT).copy(salvage = 1))
        val draws = Draws()
        val s = store(random = draws)
        val before = s.settled().state!!
        val bytes = file.readBytes()
        s.buyPart("survey_head_2")
        s.buyPaint(MechaPaint(finish = MechaFinish.ALLOY))
        s.spin(MechaSlotStake.THREE, 0)
        s.spin(MechaSlotStake.ONE, 8)
        s.idle()
        assertEquals(before, s.view.value.state)
        assertTrue(draws.bounds.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun restoringAReceiptAndReloadingCannotGrantItsRewardAgain() = runBlocking {
        val draws = Draws(0)
        val s = store(random = draws)
        s.settled()
        s.spin(MechaSlotStake.ONE, 0)
        s.idle()
        val backup = s.backupSave()!!
        val decoded = Json.decodeFromString(MechaState.serializer(), Json.encodeToString(MechaState.serializer(), backup))
        val other = Device("receipt-target", INIT)
        other.store.settled()
        other.store.restore(decoded, emptyList())
        other.store.restore(decoded, emptyList())
        assertEquals(backup, other.disk())
        assertEquals(backup.lastSpin, other.store.backupSave()!!.lastSpin)
        other.store.spin(MechaSlotStake.ONE, 0)
        other.store.idle()
        assertEquals(backup, other.disk())
        assertEquals(backup, store().settled().state)
    }

    @Test
    fun futureAndInconsistentReceiptStatesAreRejectedWithoutReset() = runBlocking {
        val good = starterMechaState(parts, INIT)
        val badStates = listOf(
            good.copy(version = 99),
            good.copy(cabinetSpins = 1),
            good.copy(version = 1, ownedPaints = setOf(MechaPaint(), MechaPaint(finish = MechaFinish.ALLOY)))
        )
        for (bad in badStates) {
            writeState(bad)
            val bytes = file.readBytes()
            val s = store()
            assertNull(s.settled().state)
            assertNotNull(s.restoreProblem(bad))
            assertTrue(runCatching { s.restore(bad, emptyList()) }.isFailure)
            s.spin(MechaSlotStake.ONE, 0)
            s.idle()
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun restoringLegacySaveUpgradesBeforePublishingAndFailedRestoreKeepsBytes() = runBlocking {
        val s = store()
        val before = s.settled().state!!
        val legacy = before.copy(version = 1, salvage = 29, salvageRuns = 6)
        val bytes = file.readBytes()
        val tmp = File(file.parentFile, "state.json.tmp")
        assertTrue(tmp.mkdir())
        assertTrue(runCatching { s.restore(legacy, emptyList()) }.isFailure)
        assertEquals(before, s.view.value.state)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(tmp.delete())
        s.restore(legacy, emptyList())
        assertEquals(legacy.copy(version = 4), onDisk())
        assertEquals(onDisk(), s.view.value.state)
    }

    @Test
    fun refundAndMissPersistTheirExactNetCreditReturn() = runBlocking {
        val draws = Draws(25, 75)
        val s = store(random = draws)
        val before = s.settled().state!!
        s.spin(MechaSlotStake.ONE, 0)
        s.idle()
        val refund = onDisk()
        assertEquals(before.salvage, refund.salvage)
        assertEquals(before.owned, refund.owned)
        assertEquals(MechaSpinOutcome.REFUND, refund.lastSpin!!.outcome)
        assertEquals(1, refund.lastSpin!!.returnedCredits)
        s.spin(MechaSlotStake.ONE, 1)
        s.idle()
        val miss = onDisk()
        assertEquals(before.salvage - 1, miss.salvage)
        assertEquals(before.owned, miss.owned)
        assertEquals(MechaSpinOutcome.MISS, miss.lastSpin!!.outcome)
        assertEquals(0, miss.lastSpin!!.returnedCredits)
        assertEquals(2, miss.cabinetSpins)
        assertEquals(miss, store().settled().state)
    }

    @Test
    fun saturatedLegacyUpgradeAndRestorePreserveBalanceWithoutBankingPlay() = runBlocking {
        val played = session("gba/saturated.gba", INIT + 60_000L, 20)
        val legacy = starterMechaState(parts, INIT).copy(version = 1, salvage = 67, pendingMinutes = 4)
        writeState(legacy)
        val s = store()
        assertEquals(legacy.copy(version = 4, pendingMinutes = 0), s.settled().state)
        table.value = listOf(played)
        s.refreshSessions()
        s.idle()
        val saturated = onDisk()
        assertEquals(67, saturated.salvage)
        assertEquals(0, saturated.pendingMinutes)
        assertEquals(20, saturated.creditedMinutes)
        assertTrue("gba/saturated.gba@${played.startedAt}" in saturated.processedSessions)
        s.restore(legacy, listOf(played))
        s.refreshSessions()
        s.idle()
        assertEquals(67, onDisk().salvage)
        assertEquals(0, onDisk().pendingMinutes)
        assertEquals(saturated.processedSessions, onDisk().processedSessions)
    }

    @Test
    fun failedRestoreOverDamagedSaveKeepsItsOriginalBytesInPlace() = runBlocking {
        file.parentFile!!.mkdirs()
        val bytes = "{broken save".toByteArray()
        file.writeBytes(bytes)
        val s = store()
        assertNull(s.settled().state)
        assertTrue(File(file.parentFile, "state.json.tmp").mkdir())
        assertTrue(runCatching { s.restore(starterMechaState(parts, INIT), emptyList()) }.isFailure)
        assertArrayEquals(bytes, file.readBytes())
        assertNull(s.view.value.state)
    }

    @Test
    fun cappedWalletsNeverSpendOrResolveAnOutcome() = runBlocking {
        val before = starterMechaState(parts, INIT).copy(salvage = 50)
        writeState(before)
        val bytes = file.readBytes()
        val draws = Draws()
        val s = store(random = draws)
        s.settled()
        MechaSlotStake.entries.forEach { s.spin(it, 0) }
        s.idle()
        assertEquals(before, s.view.value.state)
        assertTrue(draws.bounds.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun themePurchaseApplyAndIntroductionPersistSeparatelyWithoutDuplicateDebits() = runBlocking {
        val initial = starterMechaState(parts, INIT).copy(salvage = 20)
        writeState(initial)
        val s = store()
        assertFalse(s.settled().state!!.hangarIntroSeen)
        s.setTheme(SortiePalette.HARBOR)
        s.idle()
        assertEquals(initial, onDisk())
        s.buyTheme(SortiePalette.HARBOR)
        s.buyTheme(SortiePalette.HARBOR)
        s.idle()
        val bought = onDisk()
        assertEquals(12, bought.salvage)
        assertEquals("default", bought.selectedTheme)
        assertEquals(setOf("default", "harbor"), bought.ownedThemes)
        s.setTheme(SortiePalette.HARBOR)
        s.completeIntroduction(hideCompanion = true)
        s.idle()
        val applied = onDisk()
        assertEquals("harbor", applied.selectedTheme)
        assertFalse(applied.enabled)
        assertTrue(applied.hangarIntroSeen)
        assertEquals(12, applied.salvage)
        assertEquals(applied, store().settled().state)
        s.completeIntroduction()
        s.setTheme(SortiePalette.DEFAULT)
        s.idle()
        assertFalse(onDisk().enabled)
        assertEquals("default", onDisk().selectedTheme)
        assertEquals(12, onDisk().salvage)
    }

    @Test
    fun paidArcadeCoinSurvivesRestartAndBindsOnlyOneGameWithoutDuplicateChargesOrRewards() = runBlocking {
        val s = store(random = Random(42))
        val initial = s.settled().state!!
        val game = MechaArcadeGame.FLAPPY_FLIGHT
        s.insertArcadeCoin(0)
        s.insertArcadeCoin(0)
        s.idle()
        val inserted = onDisk()
        val coin = inserted.activeArcade!!
        assertNull(coin.game)
        assertEquals(1, inserted.arcadeRuns)
        assertEquals(initial.salvage - 1, inserted.salvage)
        assertEquals(inserted, s.view.value.state)
        val restarted = store()
        assertEquals(inserted, restarted.settled().state)
        restarted.insertArcadeCoin(1)
        restarted.finishArcade(coin, 0)
        restarted.selectArcadeGame(coin.copy(seed = coin.seed + 1), game)
        restarted.idle()
        assertEquals(inserted, onDisk())
        restarted.selectArcadeGame(coin, game)
        restarted.selectArcadeGame(coin, MechaArcadeGame.SCRAP_CATCH)
        restarted.idle()
        val paid = onDisk()
        val ticket = paid.activeArcade!!
        assertEquals(inserted.copy(activeArcade = coin.copy(game = game)), paid)
        assertEquals(paid, store().settled().state)
        restarted.selectArcadeGame(ticket, MechaArcadeGame.CIRCUIT_MATCH)
        restarted.abandonArcade(coin)
        restarted.finishArcade(ticket.copy(seed = ticket.seed + 1), 8)
        restarted.idle()
        assertEquals(paid, onDisk())
        restarted.finishArcade(ticket, 8)
        restarted.finishArcade(ticket, 90)
        restarted.idle()
        val finished = onDisk()
        assertEquals(paid.copy(activeArcade = null, arcadeBest = mapOf(game to 8)), finished)
        restarted.insertArcadeCoin(1)
        restarted.idle()
        val second = onDisk()
        restarted.finishArcade(ticket, 90)
        restarted.abandonArcade(ticket)
        restarted.selectArcadeGame(coin, game)
        restarted.idle()
        assertEquals(second, onDisk())
        restarted.abandonArcade(second.activeArcade!!)
        restarted.abandonArcade(second.activeArcade!!)
        restarted.idle()
        assertEquals(second.copy(activeArcade = null), onDisk())
        assertEquals(initial.salvage - 2, onDisk().salvage)
        assertEquals(initial.owned, onDisk().owned)
        assertEquals(initial.creditedMinutes, onDisk().creditedMinutes)
        assertEquals(initial.processedSessions, onDisk().processedSessions)
    }

    @Test
    fun failedThemeAndIntroductionSavesLeaveEveryVisibleAndDurableFieldUnchanged() = runBlocking {
        val initial = starterMechaState(parts, INIT).copy(salvage = 20)
        writeState(initial)
        val s = store()
        s.settled()
        val tmp = File(file.parentFile, "state.json.tmp")
        val initialBytes = file.readBytes()
        assertTrue(tmp.mkdir())
        s.buyTheme(SortiePalette.PLUM)
        s.completeIntroduction(hideCompanion = true)
        s.idle()
        assertEquals(initial, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertFalse(s.view.value.busy)
        assertArrayEquals(initialBytes, file.readBytes())
        assertTrue(tmp.delete())
        s.buyTheme(SortiePalette.PLUM)
        s.idle()
        val bought = onDisk()
        val boughtBytes = file.readBytes()
        assertTrue(tmp.mkdir())
        s.setTheme(SortiePalette.PLUM)
        s.idle()
        assertEquals(bought, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertArrayEquals(boughtBytes, file.readBytes())
        assertTrue(tmp.delete())
        s.setTheme(SortiePalette.PLUM)
        s.completeIntroduction(hideCompanion = true)
        s.idle()
        assertEquals(bought.copy(selectedTheme = "plum", hangarIntroSeen = true, enabled = false), onDisk())
    }

    @Test
    fun failedCoinSelectionFinishAndAbandonKeepTheirPreviousDurableStateForRetry() = runBlocking {
        val s = store(random = Random(7))
        val initial = s.settled().state!!
        val game = MechaArcadeGame.FLAPPY_FLIGHT
        val tmp = File(file.parentFile, "state.json.tmp")
        val initialBytes = file.readBytes()
        assertTrue(tmp.mkdir())
        s.insertArcadeCoin(0)
        s.idle()
        assertEquals(initial, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertArrayEquals(initialBytes, file.readBytes())
        assertTrue(tmp.delete())
        s.insertArcadeCoin(0)
        s.idle()
        val inserted = onDisk()
        val coin = inserted.activeArcade!!
        val coinBytes = file.readBytes()
        assertTrue(tmp.mkdir())
        s.selectArcadeGame(coin, game)
        s.idle()
        assertEquals(inserted, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertArrayEquals(coinBytes, file.readBytes())
        s.abandonArcade(coin)
        s.idle()
        assertEquals(inserted, s.view.value.state)
        assertArrayEquals(coinBytes, file.readBytes())
        assertTrue(tmp.delete())
        s.selectArcadeGame(coin, game)
        s.idle()
        val paid = onDisk()
        val ticket = paid.activeArcade!!
        val paidBytes = file.readBytes()
        assertEquals(initial.salvage - 1, paid.salvage)
        assertTrue(tmp.mkdir())
        s.finishArcade(ticket, 7)
        s.idle()
        assertEquals(paid, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertArrayEquals(paidBytes, file.readBytes())
        s.abandonArcade(ticket)
        s.idle()
        assertEquals(paid, s.view.value.state)
        assertNotNull(s.view.value.error)
        assertArrayEquals(paidBytes, file.readBytes())
        assertTrue(tmp.delete())
        s.finishArcade(ticket, 7)
        s.idle()
        assertEquals(paid.copy(activeArcade = null, arcadeBest = mapOf(game to 7)), onDisk())
        assertEquals(onDisk(), s.view.value.state)
        assertFalse(s.view.value.busy)
    }

    @Test
    fun versionTwoMigrationFailureKeepsCommerceAndBlocksBackupUntilRetry() = runBlocking {
        val legacy = starterMechaState(parts, INIT).copy(
            version = 2,
            salvage = 73,
            processedSessions = setOf("old@1"),
            ownedPaints = setOf(MechaPaint(), MechaPaint(finish = MechaFinish.ALLOY)),
            equippedPaints = mapOf(MechaSlot.BODY to MechaPaint(finish = MechaFinish.ALLOY)),
            cabinetSpins = 1,
            legacyLastSpin = MechaLegacySpinReceipt(
                1,
                MechaLegacyCabinetTier.ALLOY,
                listOf(0, 0, 0),
                MechaLegacySpinOutcome.REWARD,
                3,
                finish = MechaFinish.ALLOY
            )
        )
        file.parentFile!!.mkdirs()
        file.writeText(legacyMechaText(legacy))
        val bytes = file.readBytes()
        val tmp = File(file.parentFile, "state.json.tmp")
        assertTrue(tmp.mkdir())
        val s = store()
        val failed = s.settled()
        assertNull(failed.state)
        assertFalse(failed.canStartFresh)
        assertNotNull(failed.error)
        s.completeIntroduction(true)
        s.insertArcadeCoin(0)
        s.startFresh()
        s.idle()
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(runCatching { s.backupSave() }.isFailure)
        assertNotNull(s.restoreProblem(legacy))
        assertTrue(runCatching { s.restore(null, emptyList()) }.isFailure)
        assertTrue(runCatching { s.restore(legacy, emptyList()) }.isFailure)
        assertTrue(tmp.delete())
        s.retryOpen()
        s.idle()
        assertEquals(legacy.copy(version = 4), onDisk())
        assertEquals(onDisk(), s.view.value.state)
        assertEquals(onDisk(), s.backupSave())
        assertFalse(onDisk().hangarIntroSeen)
    }

    @Test
    fun restoringThemeAndPaidTicketPreservesSelectionAndUnionsSessionDeduplication() = runBlocking {
        val current = starterMechaState(parts, INIT).copy(processedSessions = setOf("local@1"))
        writeState(current)
        val s = store()
        s.settled()
        val backup = current.copy(salvage = 20, processedSessions = setOf("backup@2"))
            .buyingTheme(SortiePalette.LICHEN).state.selectingTheme(SortiePalette.LICHEN).state
            .introduced(true).state.insertingArcadeCoin(0, 123).state
        s.restore(backup, emptyList())
        val expected = backup.copy(processedSessions = setOf("local@1", "backup@2"))
        assertEquals(expected, onDisk())
        assertEquals(expected, s.view.value.state)
        assertEquals(expected, store().settled().state)
    }

    @Test
    fun paintAndLauncherPurchasesAndWholeBotApplyPersistAtomically() = runBlocking {
        val initial = starterMechaState(parts, INIT).copy(salvage = 30)
        writeState(initial)
        val s = store()
        s.settled()
        val paint = MechaPaint(MechaPaintScheme.PLUM, MechaFinish.HOLO)
        s.applyPaint(paint, null)
        s.setFolderIcons(SortieFolderIcons.DRAWERS)
        s.setButtonStyle(SortieButtonStyle.ARCADE)
        s.idle()
        assertEquals(initial, onDisk())
        repeat(2) {
            s.buyPaint(paint)
            s.buyFolderIcons(SortieFolderIcons.DRAWERS)
            s.buyButtonStyle(SortieButtonStyle.ARCADE)
        }
        s.idle()
        val bought = onDisk()
        assertEquals(10, bought.salvage)
        assertTrue(bought.equippedPaints.isEmpty())
        assertEquals("default", bought.selectedFolderIcons)
        assertEquals("default", bought.selectedButtonStyle)
        val bytes = file.readBytes()
        val tmp = File(file.parentFile, "state.json.tmp")
        assertTrue(tmp.mkdir())
        s.applyPaint(paint, null)
        s.setFolderIcons(SortieFolderIcons.DRAWERS)
        s.setButtonStyle(SortieButtonStyle.ARCADE)
        s.idle()
        assertEquals(bought, s.view.value.state)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(tmp.delete())
        s.applyPaint(paint, null)
        s.setFolderIcons(SortieFolderIcons.DRAWERS)
        s.setButtonStyle(SortieButtonStyle.ARCADE)
        s.idle()
        val applied = onDisk()
        assertEquals(
            bought.copy(
                equippedPaints = MechaSlot.entries.associateWith { paint },
                selectedFolderIcons = "drawers",
                selectedButtonStyle = "arcade"
            ),
            applied
        )
        assertEquals(applied, store().settled().state)
        assertEquals(applied, s.backupSave())
    }

    @Test
    fun allSaveVersionsLoadUpgradeBackupAndRestoreWithoutLosingDurableState() = runBlocking {
        for (version in 1..4) {
            val initial = starterMechaState(parts, INIT).copy(
                version = version, salvage = 31, motion = false, enabled = false, sleeping = true,
                creditedMinutes = 19, pendingMinutes = 4, salvageRuns = 9, processedSessions = setOf("old@1")
            )
            val commerce = if (version < 2) {
                initial
            } else {
                initial.copy(
                    ownedPaints = setOf(MechaPaint(), MechaPaint(finish = MechaFinish.HOLO)),
                    equippedPaints = mapOf(MechaSlot.LEGS to MechaPaint(finish = MechaFinish.HOLO)),
                    cabinetSpins = 3,
                    legacyLastSpin = MechaLegacySpinReceipt(
                        3,
                        MechaLegacyCabinetTier.HOLO,
                        listOf(2, 2, 2),
                        MechaLegacySpinOutcome.REWARD,
                        5,
                        finish = MechaFinish.HOLO
                    )
                )
            }
            val arcade = if (version < 3) {
                commerce
            } else {
                commerce.copy(
                    ownedThemes = setOf("default", "plum"),
                    selectedTheme = "plum",
                    hangarIntroSeen = true,
                    arcadeRuns = 2,
                    activeArcade = MechaArcadeTicket(2, null, Long.MIN_VALUE),
                    arcadeBest = mapOf(MechaArcadeGame.FLAPPY_FLIGHT to 19)
                )
            }
            val original = if (version < 4) {
                arcade
            } else {
                arcade.copy(
                    ownedPaints = arcade.ownedPaints + MechaPaint(MechaPaintScheme.MOSS, MechaFinish.ALLOY),
                    equippedPaints = arcade.equippedPaints + (MechaSlot.HEAD to MechaPaint(MechaPaintScheme.MOSS, MechaFinish.ALLOY)),
                    ownedFolderIcons = setOf("default", "pixel"),
                    selectedFolderIcons = "pixel",
                    ownedButtonStyles = setOf("default", "instrument"),
                    selectedButtonStyle = "instrument"
                )
            }
            file.parentFile!!.mkdirs()
            if (version < 4) file.writeText(legacyMechaText(original)) else writeState(original)
            val s = store()
            val expected = original.copy(version = 4)
            assertEquals(expected, s.settled().state)
            assertEquals(expected, onDisk())
            assertEquals(expected, s.backupSave())
            s.restore(s.backupSave(), emptyList())
            assertEquals(expected, onDisk())
            assertEquals(expected, store().settled().state)
        }
    }

    @Test
    fun malformedHistoricalFinishAndReceiptBytesRemainUntouched() = runBlocking {
        val historical = starterMechaState(parts, INIT).copy(
            version = 3,
            ownedPaints = setOf(MechaPaint(), MechaPaint(finish = MechaFinish.ALLOY)),
            equippedPaints = mapOf(MechaSlot.HEAD to MechaPaint(finish = MechaFinish.ALLOY))
        )
        val text = legacyMechaText(historical)
        val malformed = listOf(
            text.replace("\"ALLOY\"", "\"UNKNOWN\""),
            text.replace("\"ownedFinishes\":[\"PLAIN\",\"ALLOY\"]", "\"ownedFinishes\":[\"PLAIN\"]")
        )
        for (broken in malformed) {
            file.parentFile!!.mkdirs()
            file.writeText(broken)
            val bytes = file.readBytes()
            val s = store()
            assertNull(s.settled().state)
            s.buyPaint(MechaPaint(MechaPaintScheme.MOSS))
            s.idle()
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test
    fun failedLauncherAndPaintPurchasesNeverChargeOrGrantOwnership() = runBlocking {
        val initial = starterMechaState(parts, INIT).copy(salvage = 30)
        writeState(initial)
        val s = store()
        s.settled()
        val bytes = file.readBytes()
        assertTrue(File(file.parentFile, "state.json.tmp").mkdir())
        s.buyPaint(MechaPaint(MechaPaintScheme.CANDY, MechaFinish.HOLO))
        s.buyFolderIcons(SortieFolderIcons.CARTRIDGES)
        s.buyButtonStyle(SortieButtonStyle.INSTRUMENT)
        s.idle()
        assertEquals(initial, s.view.value.state)
        assertArrayEquals(bytes, file.readBytes())
        assertNotNull(s.view.value.error)
    }
}
