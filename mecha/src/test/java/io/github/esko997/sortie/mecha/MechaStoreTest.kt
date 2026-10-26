package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.INIT
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.parts
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.session
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

    private fun onDisk(): MechaState = Json.decodeFromString(MechaState.serializer(), file.readText())

    @Test
    fun firstOpenSavesTheStarterHangarAndLaterOpensReadIt() = runBlocking {
        val first = store().settled()
        val state = first.state!!
        assertEquals(state, onDisk())
        assertEquals(INIT, state.initializedAt)
        assertEquals(56, first.parts.size)
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
        s.spin(MechaCabinetTier.PART, 0)
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
        // Pet timing remains outside the companion save; commerce uses format version 2.
        assertEquals(2, once.version)
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
            "version", "ownedFinishes", "equippedFinishes", "cabinetSpins", "lastSpin"
        )
        file.parentFile!!.mkdirs()
        file.writeText(JsonObject(oldFields).toString())
        assertEquals(1, onDisk().version)
        table.value = listOf(session("gba/old.gba", INIT + 60_000L, 20))
        val s = store()
        s.settled()
        s.refreshSessions()
        s.idle()
        assertEquals(legacy.copy(version = 2), s.view.value.state)
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
        assertEquals(legacy.copy(version = 2), s.view.value.state)
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
        repeat(2) { s.buyFinish(MechaFinish.ALLOY) }
        s.setFinish(MechaSlot.HEAD, MechaFinish.ALLOY)
        val build = mutableMapOf(
            MechaSlot.HEAD to "survey_head_2",
            MechaSlot.LEGS to "survey_legs_1",
            MechaSlot.BODY to "yard_body_1",
            MechaSlot.ARMS to "yard_arms_1"
        )
        s.fitBuild(build)
        s.idle()
        val saved = onDisk()
        assertEquals(20 - MECHA_PART_PRICE - MechaFinish.ALLOY.price, saved.salvage)
        assertEquals(before.owned + "survey_head_2", saved.owned)
        assertEquals(setOf(MechaFinish.PLAIN, MechaFinish.ALLOY), saved.ownedFinishes)
        assertEquals(MechaFinish.ALLOY, saved.equippedFinishes[MechaSlot.HEAD])
        assertEquals(build, saved.equipped)
        assertEquals(saved, store().settled().state)
        s.setFinish(MechaSlot.HEAD, MechaFinish.PLAIN)
        s.idle()
        assertEquals(saved.salvage, onDisk().salvage)
        assertEquals(MechaFinish.PLAIN, onDisk().equippedFinishes[MechaSlot.HEAD] ?: MechaFinish.PLAIN)
    }

    @Test
    fun queuedSpinsResolveOnceAndStaleClicksNeverSpendOrDrawAgain() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val draws = Draws(0, 1)
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
            s.spin(MechaCabinetTier.PART, 0)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            s.spin(MechaCabinetTier.PART, 0)
            assertTrue(s.view.value.busy)
            assertEquals(before, s.view.value.state)
            assertArrayEquals(bytes, file.readBytes())
        } finally {
            release.countDown()
        }
        s.idle()
        val saved = onDisk()
        assertFalse(s.view.value.busy)
        assertEquals(listOf(100, parts.size - before.owned.size), draws.bounds)
        assertEquals(1, saved.cabinetSpins)
        assertEquals(before.salvage, saved.salvage)
        assertEquals(before.owned + before.unownedIn(parts)[1].id, saved.owned)
        assertEquals(MechaSpinOutcome.REWARD, saved.lastSpin!!.outcome)
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
        s.buyFinish(MechaFinish.ALLOY)
        s.spin(MechaCabinetTier.HOLO, 0)
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
        s.buyFinish(MechaFinish.ALLOY)
        s.spin(MechaCabinetTier.ALLOY, 0)
        s.spin(MechaCabinetTier.PART, 8)
        s.idle()
        assertEquals(before, s.view.value.state)
        assertTrue(draws.bounds.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun restoringAReceiptAndReloadingCannotGrantItsRewardAgain() = runBlocking {
        val draws = Draws(0, 0)
        val s = store(random = draws)
        s.settled()
        s.spin(MechaCabinetTier.PART, 0)
        s.idle()
        val backup = s.backupSave()!!
        val decoded = Json.decodeFromString(MechaState.serializer(), Json.encodeToString(MechaState.serializer(), backup))
        val other = Device("receipt-target", INIT)
        other.store.settled()
        other.store.restore(decoded, emptyList())
        other.store.restore(decoded, emptyList())
        assertEquals(backup, other.disk())
        assertEquals(backup.lastSpin, other.store.backupSave()!!.lastSpin)
        other.store.spin(MechaCabinetTier.PART, 0)
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
            good.copy(version = 1, ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.ALLOY))
        )
        for (bad in badStates) {
            writeState(bad)
            val bytes = file.readBytes()
            val s = store()
            assertNull(s.settled().state)
            assertNotNull(s.restoreProblem(bad))
            assertTrue(runCatching { s.restore(bad, emptyList()) }.isFailure)
            s.spin(MechaCabinetTier.PART, 0)
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
        assertEquals(legacy.copy(version = 2), onDisk())
        assertEquals(onDisk(), s.view.value.state)
    }

    @Test
    fun refundAndMissPersistTheirExactNetCreditReturn() = runBlocking {
        val draws = Draws(25, 0, 75, 0)
        val s = store(random = draws)
        val before = s.settled().state!!
        s.spin(MechaCabinetTier.PART, 0)
        s.idle()
        val refund = onDisk()
        assertEquals(before.salvage, refund.salvage)
        assertEquals(before.owned, refund.owned)
        assertEquals(MechaSpinOutcome.REFUND, refund.lastSpin!!.outcome)
        assertEquals(1, refund.lastSpin!!.returnedCredits)
        s.spin(MechaCabinetTier.PART, 1)
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
        assertEquals(legacy.copy(version = 2, pendingMinutes = 0), s.settled().state)
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
    fun completedCabinetTiersNeverSpendOrResolveAnotherOutcome() = runBlocking {
        val before = starterMechaState(parts, INIT).copy(
            salvage = 20,
            owned = parts.mapTo(LinkedHashSet()) { it.id },
            ownedFinishes = MechaFinish.entries.toSet()
        )
        writeState(before)
        val bytes = file.readBytes()
        val draws = Draws()
        val s = store(random = draws)
        s.settled()
        MechaCabinetTier.entries.forEach { s.spin(it, 0) }
        s.idle()
        assertEquals(before, s.view.value.state)
        assertTrue(draws.bounds.isEmpty())
        assertArrayEquals(bytes, file.readBytes())
    }
}
