package io.github.esko997.sortie.mecha

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconstructs the actual historical wire schema, not a current model merely stamped with an older version. */
internal fun legacyMechaText(state: MechaState): String {
    require(state.version in 1..3)
    val json = Json { encodeDefaults = true }
    val root = json.encodeToJsonElement(MechaState.serializer(), state).jsonObject.toMutableMap()
    root.remove("ownedPaints")
    root.remove("equippedPaints")
    root.remove("ownedFolderIcons")
    root.remove("selectedFolderIcons")
    root.remove("ownedButtonStyles")
    root.remove("selectedButtonStyle")
    root.remove("legacyLastSpin")
    root["ownedFinishes"] = json.encodeToJsonElement(SetSerializer(MechaFinish.serializer()), state.ownedPaints.mapTo(linkedSetOf()) { it.finish })
    root["equippedFinishes"] = json.encodeToJsonElement(
        MapSerializer(MechaSlot.serializer(), MechaFinish.serializer()), state.equippedPaints.mapValues { it.value.finish }
    )
    root["lastSpin"] = json.encodeToJsonElement(MechaLegacySpinReceipt.serializer().nullable, state.legacyLastSpin)
    if (state.version == 1) {
        listOf("version", "ownedFinishes", "equippedFinishes", "cabinetSpins", "lastSpin").forEach(root::remove)
    }
    if (state.version < 3) {
        listOf("ownedThemes", "selectedTheme", "hangarIntroSeen", "arcadeRuns", "activeArcade", "arcadeBest").forEach(root::remove)
    }
    return JsonObject(root).toString()
}

class MechaMigrationTest {
    private fun historical(version: Int): MechaState {
        val original = MechaRulesTest.starter().copy(
            version = version, name = "Rivet", enabled = false, motion = false, sleeping = true,
            salvage = 39, bond = 63, charge = 43, condition = 71, salvageRuns = 8,
            creditedMinutes = 27, pendingMinutes = 2, processedSessions = setOf("old@123")
        )
        val commerce = if (version == 1) {
            original
        } else {
            original.copy(
                ownedPaints = MechaFinish.entries.mapTo(linkedSetOf()) { MechaPaint(finish = it) },
                equippedPaints = mapOf(MechaSlot.HEAD to MechaPaint(finish = MechaFinish.ALLOY), MechaSlot.BODY to MechaPaint(finish = MechaFinish.HOLO)),
                cabinetSpins = 9,
                legacyLastSpin = MechaLegacySpinReceipt(
                    9,
                    MechaLegacyCabinetTier.HOLO,
                    listOf(2, 2, 2),
                    MechaLegacySpinOutcome.REWARD,
                    5,
                    finish = MechaFinish.HOLO
                )
            )
        }
        return if (version < 3) {
            commerce
        } else {
            commerce.copy(
                ownedThemes = setOf("default", "plum"),
                selectedTheme = "plum",
                hangarIntroSeen = true,
                arcadeRuns = 7,
                activeArcade = MechaArcadeTicket(7, null, Long.MIN_VALUE),
                arcadeBest = mapOf(MechaArcadeGame.FLAPPY_FLIGHT to 11, MechaArcadeGame.SCRAP_CATCH to 20)
            )
        }
    }

    @Test
    fun allHistoricalVersionsPreserveEveryDurableValueAndEquippedOriginalEdition() {
        for (version in 1..3) {
            val before = historical(version)
            val decoded = decodeMechaState(legacyMechaText(before))
            assertEquals(before, decoded)
            assertNull(mechaStateProblem(decoded, MechaRulesTest.parts))
            val current = decoded.upgraded()
            assertEquals(before.copy(version = 4), current)
            assertEquals(current, decodeMechaState(Json.encodeToString(MechaState.serializer(), current)))
            assertNull(current.lastSpin)
            assertEquals(before.legacyLastSpin, current.legacyLastSpin)
        }
    }

    @Test
    fun everyHistoricalOutcomeAndRewardKindRetainsItsMeaningWithoutAnyPayout() {
        for (tier in MechaLegacyCabinetTier.entries) {
            for (outcome in MechaLegacySpinOutcome.entries) {
                val reward = outcome == MechaLegacySpinOutcome.REWARD
                val reels = when (outcome) {
                    MechaLegacySpinOutcome.REWARD -> listOf(1, 1, 1)
                    MechaLegacySpinOutcome.REFUND -> listOf(1, 1, 2)
                    MechaLegacySpinOutcome.MISS -> listOf(1, 2, 3)
                }
                val receipt = MechaLegacySpinReceipt(
                    9,
                    tier,
                    reels,
                    outcome,
                    if (outcome == MechaLegacySpinOutcome.MISS) 0 else tier.stake,
                    partId = if (reward && tier == MechaLegacyCabinetTier.PART) "yard_head_1" else null,
                    finish = if (reward) tier.finish else null
                )
                val before = historical(3).copy(legacyLastSpin = receipt)
                val current = decodeMechaState(legacyMechaText(before)).upgraded()
                assertEquals(before.copy(version = 4), current)
                assertNull(mechaStateProblem(current))
            }
        }
    }

    @Test
    fun malformedHistoricalOwnershipReceiptsAndVersionsAreRejectedRatherThanRepaired() {
        val before = historical(3)
        val receipt = before.legacyLastSpin!!
        val invalid = listOf(
            before.copy(ownedPaints = setOf(MechaPaint())),
            before.copy(ownedPaints = emptySet()),
            before.copy(legacyLastSpin = receipt.copy(sequence = 8)),
            before.copy(legacyLastSpin = receipt.copy(reels = listOf(0, 1, 2))),
            before.copy(legacyLastSpin = receipt.copy(returnedCredits = 0)),
            before.copy(legacyLastSpin = receipt.copy(finish = MechaFinish.ALLOY))
        )
        invalid.forEach { assertNotNull(mechaStateProblem(decodeMechaState(legacyMechaText(it)))) }
        val text = legacyMechaText(before)
        assertTrue(runCatching { decodeMechaState(text.replace("HOLO", "UNKNOWN")) }.isFailure)
        val root = Json.parseToJsonElement(text).jsonObject
        assertTrue(runCatching { decodeMechaState(JsonObject(root + ("version" to JsonPrimitive(99))).toString()) }.isFailure)
        assertTrue(runCatching { decodeMechaState(JsonObject(root + ("version" to JsonPrimitive(4))).toString()) }.isFailure)
    }
}
