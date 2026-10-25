package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.INIT
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.parts
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.session
import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    private fun store(now: Long = INIT, catalog: () -> List<MechaPart> = { parts }) = MechaStore(
        locate = { file },
        loadCatalog = catalog,
        sessions = { since -> table.map { rows -> rows.filter { it.startedAt >= since && it.endedAt != null && !it.abandoned } } },
        clock = { now },
        scope = scope
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
        assertEquals(24, first.parts.size)
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
        s.salvage(MechaSlot.ARMS)
        s.idle()
        val saved = onDisk()
        assertEquals(s.view.value.state, saved)
        assertEquals("survey_head_1", saved.equipped[MechaSlot.HEAD])
        assertEquals("Bolt", saved.name)
        assertTrue(saved.sleeping)
        assertFalse(saved.motion)
        assertEquals(MECHA_STARTER_CREDITS - 1, saved.salvage)
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
        s.salvage()
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
        s.salvage()
        s.idle()
        assertFalse(file.exists())
    }

    @Test
    fun petsArePersistedAndRateLimited() = runBlocking {
        val s = store()
        val before = s.settled().state!!
        s.pet()
        s.idle()
        val once = onDisk()
        assertEquals(before.bond + 1, once.bond)
        assertEquals(INIT, once.lastPetAt)
        val bytes = file.readBytes()
        repeat(5) { s.pet() }
        s.idle()
        // the clock has not moved: the same window, so nothing more is earned or written
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(once, s.view.value.state)
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
        // the DMG: 20 minutes of play, a rename and a salvage run, then a backup
        val dmg = Device("dmg", INIT)
        dmg.store.settled()
        val played = listOf(session("gba/a.gba", INIT + 60_000L, 20))
        dmg.table.value = played
        withTimeout(5_000) { dmg.store.view.first { it.state?.processedSessions?.size == 1 } }
        dmg.store.rename("Rivet")
        dmg.store.salvage(MechaSlot.HEAD)
        dmg.store.idle()
        val save = dmg.store.backupSave()!!
        assertEquals("Rivet", save.name)
        assertEquals(13, save.owned.size)
        assertEquals(MECHA_STARTER_CREDITS + 4 - 1, save.salvage)

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
}
