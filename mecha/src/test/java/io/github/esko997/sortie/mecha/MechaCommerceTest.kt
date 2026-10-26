package io.github.esko997.sortie.mecha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaCommerceTest {
    private val parts = MechaRulesTest.parts

    private fun starter() = MechaRulesTest.starter()

    @Test
    fun legacyDefaultsUpgradeWithoutAwardingOrLosingAnything() {
        val legacy = starter().copy(
            version = 1,
            name = "Rivet",
            salvage = 39,
            salvageRuns = 9,
            creditedMinutes = 27,
            pendingMinutes = 2,
            processedSessions = setOf("old@123")
        )
        val encoded = Json.encodeToString(MechaState.serializer(), legacy)
        val withoutVersion = JsonObject(Json.parseToJsonElement(encoded).jsonObject - "version")
        val decoded = Json.decodeFromString(MechaState.serializer(), withoutVersion.toString())
        assertEquals(legacy, decoded)
        assertNull(mechaStateProblem(decoded, parts))
        val upgraded = decoded.upgraded()
        assertEquals(legacy.copy(version = 2), upgraded)
        assertSame(upgraded, upgraded.upgraded())
        assertEquals(12, upgraded.owned.size)
        assertEquals(setOf(MechaFinish.PLAIN), upgraded.ownedFinishes)
        assertTrue(runCatching { legacy.copy(version = 3).upgraded() }.isFailure)
    }

    @Test
    fun everyNewPartIsPurchasableExactlyOnceWithoutAutoEquipping() {
        var state = starter().copy(salvage = 200)
        for (part in state.unownedIn(parts)) {
            val before = state
            val result = state.buyingPart(part.id, parts)
            assertNull(result.error)
            state = result.state
            assertEquals(before.salvage - MECHA_PART_PRICE, state.salvage)
            assertEquals(before.owned + part.id, state.owned)
            assertEquals(before.equipped, state.equipped)
            assertEquals(before.copy(salvage = state.salvage, owned = state.owned, origins = state.origins), state)
            assertNull(mechaStateProblem(state, parts))
            assertSame(state, state.buyingPart(part.id, parts).state)
            assertNotNull(state.buyingPart(part.id, parts).error)
            assertEquals(part.id, state.equipping(part.id, parts).state.equipped[part.slot])
        }
        assertEquals(56, state.owned.size)
        assertEquals(200 - 44 * MECHA_PART_PRICE, state.salvage)
        val broke = starter().copy(salvage = MECHA_PART_PRICE - 1)
        assertSame(broke, broke.buyingPart("foundry_head_1", parts).state)
        assertSame(broke, broke.buyingPart("missing", parts).state)
        val exact = broke.copy(salvage = MECHA_PART_PRICE).buyingPart("foundry_head_1", parts).state
        assertEquals(0, exact.salvage)
    }

    @Test
    fun finishesAreGlobalReusableUnlocksAppliedFreelyPerSlot() {
        val initial = starter().copy(salvage = 16)
        assertSame(initial, initial.buyingFinish(MechaFinish.PLAIN).state)
        assertSame(initial, initial.finishing(MechaSlot.HEAD, MechaFinish.HOLO).state)
        var state = initial.buyingFinish(MechaFinish.ALLOY).state.buyingFinish(MechaFinish.HOLO).state
        assertEquals(0, state.salvage)
        assertEquals(MechaFinish.entries.toSet(), state.ownedFinishes)
        assertTrue(state.equippedFinishes.isEmpty())
        for (slot in MechaSlot.entries) state = state.finishing(slot, MechaFinish.ALLOY).state
        assertEquals(MechaSlot.entries.associateWith { MechaFinish.ALLOY }, state.equippedFinishes)
        val changedPart = state.equipping("survey_head_1", parts).state
        assertEquals(MechaFinish.ALLOY, changedPart.equippedFinishes[MechaSlot.HEAD])
        assertEquals(0, changedPart.salvage)
        val plain = changedPart.finishing(MechaSlot.HEAD, MechaFinish.PLAIN).state
        assertFalse(MechaSlot.HEAD in plain.equippedFinishes)
        assertSame(plain, plain.finishing(MechaSlot.HEAD, MechaFinish.PLAIN).state)
        assertSame(plain, plain.buyingFinish(MechaFinish.ALLOY).state)
        assertEquals(0, plain.salvage)
        assertNull(mechaStateProblem(plain, parts))
        for (finish in listOf(MechaFinish.ALLOY, MechaFinish.HOLO)) {
            val insufficient = starter().copy(salvage = finish.price - 1)
            assertSame(insufficient, insufficient.buyingFinish(finish).state)
            assertNotNull(insufficient.buyingFinish(finish).error)
        }
    }

    @Test
    fun cabinetHasExactTwentyThirtyFiftyDistributionAndTwentyFivePercentPremiumForEveryTier() {
        val initial = starter().copy(salvage = 100, salvageRuns = 7)
        for (tier in MechaCabinetTier.entries) {
            val results = (0..99).map { roll -> initial.cabinetSpun(tier, parts, roll, 0) }
            val receipts = results.map { it.state.lastSpin!! }
            assertEquals(20, receipts.count { it.outcome == MechaSpinOutcome.REWARD })
            assertEquals(30, receipts.count { it.outcome == MechaSpinOutcome.REFUND })
            assertEquals(50, receipts.count { it.outcome == MechaSpinOutcome.MISS })
            val spent = results.sumOf { initial.salvage - it.state.salvage }
            val rewards = receipts.count { it.outcome == MechaSpinOutcome.REWARD }
            val directPrice = tier.finish?.price ?: MECHA_PART_PRICE
            assertEquals(50 * tier.stake, spent)
            // Integer cross multiplication proves expected cost/reward is exactly 5/4 of direct purchase.
            assertEquals(5 * directPrice * rewards, 4 * spent)
            for ((roll, result) in results.withIndex()) {
                assertNull(result.error)
                assertEquals(result, initial.cabinetSpun(tier, parts, roll, 0))
                val state = result.state
                val receipt = state.lastSpin!!
                assertEquals(1, state.cabinetSpins)
                assertEquals(1, receipt.sequence)
                assertEquals(tier, receipt.tier)
                assertEquals(if (roll < 50) tier.stake else 0, receipt.returnedCredits)
                assertEquals(if (roll < 20) 1 else if (roll < 50) 2 else 3, receipt.reels.toSet().size)
                assertTrue(receipt.reels.all { it in 0..4 })
                assertEquals(
                    initial.copy(
                        salvage = state.salvage,
                        owned = state.owned,
                        origins = state.origins,
                        ownedFinishes = state.ownedFinishes,
                        cabinetSpins = 1,
                        lastSpin = receipt
                    ),
                    state
                )
                assertNull(mechaStateProblem(state, parts))
            }
        }
    }

    @Test
    fun eachRewardIndexChoosesExactlyOneUnownedPartAndNeverChangesOnReplay() {
        val initial = starter()
        val candidates = initial.unownedIn(parts)
        val awarded = candidates.indices.map { index ->
            val result = initial.cabinetSpun(MechaCabinetTier.PART, parts, 0, index)
            assertEquals(result, initial.cabinetSpun(MechaCabinetTier.PART, parts.reversed(), 0, index))
            assertEquals(setOf(candidates[index].id), result.state.owned - initial.owned)
            assertEquals(candidates[index].id, result.state.lastSpin!!.partId)
            candidates[index].id
        }
        assertEquals(candidates.map { it.id }.toSet(), awarded.toSet())
        var state = initial
        repeat(candidates.size) { state = state.cabinetSpun(MechaCabinetTier.PART, parts, 0, 0).state }
        assertEquals(parts.map { it.id }.toSet(), state.owned)
        assertEquals(initial.salvage, state.salvage)
        for (roll in listOf(0, 20, 99)) assertSame(state, state.cabinetSpun(MechaCabinetTier.PART, parts, roll, 0).state)
    }

    @Test
    fun completedUnaffordableAndInvalidSpinsNeverSpendOrPublishReceipts() {
        for (tier in MechaCabinetTier.entries) {
            val poor = starter().copy(salvage = tier.stake - 1)
            for (roll in listOf(0, 20, 99)) {
                assertSame(poor, poor.cabinetSpun(tier, parts, roll, 0).state)
                assertNotNull(poor.cabinetSpun(tier, parts, roll, 0).error)
            }
            val exact = starter().copy(salvage = tier.stake)
            assertEquals(0, exact.cabinetSpun(tier, parts, 99, 0).state.salvage)
            for (roll in listOf(-1, 100, Int.MIN_VALUE, Int.MAX_VALUE)) assertSame(exact, exact.cabinetSpun(tier, parts, roll, 0).state)
            if (tier.finish != null) {
                val complete = exact.cabinetSpun(tier, parts, 0, 0).state
                assertEquals(setOf(tier.finish), complete.ownedFinishes - exact.ownedFinishes)
                for (roll in listOf(0, 20, 99)) assertSame(complete, complete.cabinetSpun(tier, parts, roll, 0).state)
            }
        }
        val initial = starter()
        for (index in listOf(-1, initial.unownedIn(parts).size, Int.MAX_VALUE)) {
            assertSame(initial, initial.cabinetSpun(MechaCabinetTier.PART, parts, 0, index).state)
        }
    }

    @Test
    fun walletAndSpinCounterCannotOverflow() {
        val initial = starter().copy(salvage = Int.MAX_VALUE)
        for (tier in MechaCabinetTier.entries) {
            assertEquals(Int.MAX_VALUE, initial.cabinetSpun(tier, parts, 0, 0).state.salvage)
            assertEquals(Int.MAX_VALUE, initial.cabinetSpun(tier, parts, 20, 0).state.salvage)
            assertEquals(Int.MAX_VALUE - tier.stake, initial.cabinetSpun(tier, parts, 99, 0).state.salvage)
        }
        val receipt = MechaSpinReceipt(Int.MAX_VALUE, MechaCabinetTier.PART, listOf(0, 1, 2), MechaSpinOutcome.MISS, 0)
        val full = initial.copy(cabinetSpins = Int.MAX_VALUE, lastSpin = receipt)
        assertNull(mechaStateProblem(full, parts))
        assertSame(full, full.cabinetSpun(MechaCabinetTier.PART, parts, 0, 0).state)
        val penultimate = initial.copy(cabinetSpins = Int.MAX_VALUE - 1, lastSpin = receipt.copy(sequence = Int.MAX_VALUE - 1))
        assertEquals(Int.MAX_VALUE, penultimate.cabinetSpun(MechaCabinetTier.PART, parts, 99, 0).state.cabinetSpins)
    }

    @Test
    fun allSixHundredSeventyTwoOwnedBuildsAreDistinctEligibleAndFreeToFit() {
        val initial = starter().copy(owned = parts.map { it.id }.toSet(), salvage = 0)
        val builds = mechaBuilds(parts, initial.owned)
        assertEquals(672, builds.size)
        assertEquals(672, builds.toSet().size)
        assertEquals(builds, mechaBuilds(parts.reversed(), initial.owned))
        val byId = parts.associateBy { it.id }
        assertTrue(builds.any { byId.getValue(it.equipped.getValue(MechaSlot.BODY)).variant != byId.getValue(it.equipped.getValue(MechaSlot.ARMS)).variant })
        for (build in builds) {
            val result = initial.fittingBuild(build.equipped, parts)
            assertNull(result.error)
            assertEquals(initial.copy(equipped = build.equipped), result.state)
            assertNull(mechaStateProblem(result.state, parts))
            assertSame(result.state, result.state.fittingBuild(build.equipped, parts).state)
        }
        assertEquals(6, mechaBuilds(parts, starter().owned).size)
        assertTrue(mechaBuilds(parts, emptySet()).isEmpty())
        assertTrue(mechaBuilds(parts, parts.filter { it.family == "yard" }.map { it.id }.toSet()).isEmpty())
    }

    @Test
    fun fittingRejectsIncompleteUnownedWrongSlotAndNonBenchCombinations() {
        val initial = starter()
        val good = mechaBuilds(parts, initial.owned).first().equipped
        val invalid = listOf(
            good - MechaSlot.HEAD,
            good + (MechaSlot.HEAD to "foundry_head_1"),
            good + (MechaSlot.HEAD to good.getValue(MechaSlot.BODY)),
            good + (MechaSlot.HEAD to "missing"),
            initial.equipped,
            mapOf(MechaSlot.HEAD to "yard_head_1", MechaSlot.LEGS to "survey_legs_1", MechaSlot.BODY to "courier_body_1", MechaSlot.ARMS to "courier_arms_1")
        )
        for (build in invalid) {
            assertSame(initial, initial.fittingBuild(build, parts).state)
            assertNotNull(initial.fittingBuild(build, parts).error)
        }
        val mutable = good.toMutableMap()
        val fitted = initial.fittingBuild(mutable, parts).state
        mutable.clear()
        assertEquals(good, fitted.equipped)
    }

    @Test
    fun persistentCommerceFieldsAndReceiptRelationshipsAreStrictlyValidated() {
        val initial = starter()
        val reward = initial.cabinetSpun(MechaCabinetTier.PART, parts, 0, 0).state
        val receipt = reward.lastSpin!!
        val invalidStates = listOf(
            initial.copy(ownedFinishes = emptySet()),
            initial.copy(equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.HOLO)),
            initial.copy(cabinetSpins = -1),
            initial.copy(cabinetSpins = 1),
            reward.copy(cabinetSpins = 0),
            reward.copy(lastSpin = receipt.copy(sequence = 0)),
            reward.copy(lastSpin = receipt.copy(sequence = 2)),
            reward.copy(lastSpin = receipt.copy(reels = listOf(0, 0))),
            reward.copy(lastSpin = receipt.copy(reels = listOf(-1, -1, -1))),
            reward.copy(lastSpin = receipt.copy(reels = listOf(5, 5, 5))),
            reward.copy(lastSpin = receipt.copy(reels = listOf(0, 1, 2))),
            reward.copy(lastSpin = receipt.copy(returnedCredits = 0)),
            reward.copy(lastSpin = receipt.copy(returnedCredits = 2)),
            reward.copy(lastSpin = receipt.copy(partId = null)),
            reward.copy(lastSpin = receipt.copy(partId = "missing")),
            reward.copy(lastSpin = receipt.copy(finish = MechaFinish.ALLOY)),
            initial.copy(version = 1, ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.ALLOY)),
            initial.copy(version = 1, equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.PLAIN)),
            reward.copy(version = 1)
        )
        for (state in invalidStates) {
            assertNotNull(mechaStateProblem(state))
            assertNotNull(mechaStateProblem(state, parts))
        }
        for (roll in listOf(20, 99)) {
            val state = initial.cabinetSpun(MechaCabinetTier.PART, parts, roll, 0).state
            val nonReward = state.lastSpin!!
            assertNotNull(mechaStateProblem(state.copy(lastSpin = nonReward.copy(partId = "yard_head_1"))))
            assertNotNull(mechaStateProblem(state.copy(lastSpin = nonReward.copy(finish = MechaFinish.PLAIN))))
            assertNotNull(mechaStateProblem(state.copy(lastSpin = nonReward.copy(reels = listOf(0, 0, 0)))))
        }
        val alloy = initial.copy(salvage = 6).cabinetSpun(MechaCabinetTier.ALLOY, parts, 0, 0).state
        val alloyReceipt = alloy.lastSpin!!
        for (finish in listOf(null, MechaFinish.PLAIN, MechaFinish.HOLO)) {
            assertNotNull(mechaStateProblem(alloy.copy(lastSpin = alloyReceipt.copy(finish = finish))))
        }
        assertNotNull(mechaStateProblem(alloy.copy(ownedFinishes = setOf(MechaFinish.PLAIN))))
        assertNotNull(mechaStateProblem(alloy.copy(lastSpin = alloyReceipt.copy(partId = "yard_head_1"))))
        assertNull(mechaStateProblem(initial.copy(equippedFinishes = mapOf(MechaSlot.HEAD to MechaFinish.PLAIN))))
    }

    @Test
    fun serializedReceiptsRemainDescriptiveAndUnknownEnumsAreRejected() {
        for (tier in MechaCabinetTier.entries) {
            for (roll in listOf(0, 20, 99)) {
                val state = starter().copy(salvage = 20).cabinetSpun(tier, parts, roll, 0).state
                val encoded = Json.encodeToString(MechaState.serializer(), state)
                val decoded = Json.decodeFromString(MechaState.serializer(), encoded)
                assertEquals(state, decoded)
                assertEquals(state, decoded.upgraded())
                assertNull(mechaStateProblem(decoded, parts))
            }
        }
        val state = starter().copy(ownedFinishes = setOf(MechaFinish.PLAIN, MechaFinish.HOLO))
        val encoded = Json.encodeToString(MechaState.serializer(), state)
        assertTrue(runCatching { Json.decodeFromString(MechaState.serializer(), encoded.replace("HOLO", "FUTURE")) }.isFailure)
    }
}
