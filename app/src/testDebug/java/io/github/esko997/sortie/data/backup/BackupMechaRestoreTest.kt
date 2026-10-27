package io.github.esko997.sortie.data.backup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.mecha.MechaArcadeGame
import io.github.esko997.sortie.mecha.MechaArcadeTicket
import io.github.esko997.sortie.mecha.MechaCabinetTier
import io.github.esko997.sortie.mecha.MechaFinish
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaSpinOutcome
import io.github.esko997.sortie.mecha.MechaSpinReceipt
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupMechaRestoreTest {
    @Test
    fun exportedCosmeticsAndPaidRoundSurviveRestoreAndRestartWithoutAnotherChargeOrReward() = runBlocking {
        val dir = Files.createTempDirectory("sortie-backup-mecha").toFile()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = dir
        }
        try {
            val store = MechaStore(context) { flowOf(emptyList()) }
            val initial = withTimeout(10_000) { store.view.first { it.state != null || it.error != null } }
            assertNull(initial.error)
            val save = initial.state!!.copy(
                salvage = 7,
                enabled = false,
                ownedThemes = setOf("default", "plum"),
                selectedTheme = "plum",
                hangarIntroSeen = true,
                arcadeRuns = 2,
                activeArcade = MechaArcadeTicket(2, MechaArcadeGame.FLAPPY_FLIGHT, 812L),
                arcadeBest = mapOf(MechaArcadeGame.FLAPPY_FLIGHT to 6),
                ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.HOLO),
                equippedFinishes = mapOf(MechaSlot.BODY to MechaFinish.HOLO),
                cabinetSpins = 1,
                lastSpin = MechaSpinReceipt(
                    sequence = 1,
                    tier = MechaCabinetTier.HOLO,
                    reels = listOf(1, 1, 1),
                    outcome = MechaSpinOutcome.REWARD,
                    returnedCredits = 5,
                    finish = MechaFinish.HOLO
                )
            )
            // Restore a paid round and selected cosmetics alongside the already-resolved slot-machine receipt.
            store.restore(save, emptyList())
            val exported = buildBackup(UserData(), emptyList(), store.backupSave(), 42, "test", "test")
            val parsed = parseBackup(encodeBackup(exported)) as ParsedBackup.Valid
            assertNull(store.restoreProblem(parsed.backup.mecha!!))
            store.restore(parsed.backup.mecha, emptyList())
            store.restore(parsed.backup.mecha, emptyList())
            assertEquals(save, store.backupSave())
            val file = File(dir, "mecha/state.json")
            assertEquals(save, Json.decodeFromString(MechaState.serializer(), file.readText()))
            val restarted = MechaStore(context) { flowOf(emptyList()) }
            val reopened = withTimeout(10_000) { restarted.view.first { it.state != null || it.error != null } }
            assertNull(reopened.error)
            assertEquals(save, reopened.state)
            assertEquals(7, reopened.state!!.salvage)
            assertEquals(save.owned, reopened.state!!.owned)
            assertEquals(save.lastSpin, reopened.state!!.lastSpin)
        } finally {
            dir.deleteRecursively()
        }
    }
}
