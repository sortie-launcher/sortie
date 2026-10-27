package io.github.esko997.sortie.mecha

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MechaSlotsTest {
    private fun starter() = MechaRulesTest.starter().copy(salvage = 20)

    @Test
    fun everyRollHasExactOddsAndEightyFivePercentExpectedReturnAtEveryStake() {
        for (stake in MechaSlotStake.entries) {
            val initial = starter()
            val results = (0..99).map { initial.slotSpun(stake, it) }
            val receipts = results.map { it.state.lastSpin!! }
            assertEquals(5, receipts.count { it.outcome == MechaSpinOutcome.JACKPOT })
            assertEquals(20, receipts.count { it.outcome == MechaSpinOutcome.WIN })
            assertEquals(20, receipts.count { it.outcome == MechaSpinOutcome.REFUND })
            assertEquals(55, receipts.count { it.outcome == MechaSpinOutcome.MISS })
            assertEquals(85 * stake.stake, receipts.sumOf { it.returnedCredits })
            assertEquals(15 * stake.stake, results.sumOf { initial.salvage - it.state.salvage })
            results.forEachIndexed { roll, result ->
                assertNull(result.error)
                assertEquals(result, initial.slotSpun(stake, roll))
                val receipt = result.state.lastSpin!!
                assertEquals(stake, receipt.stake)
                assertEquals(1, receipt.sequence)
                assertEquals(stake.stake * receipt.outcome.multiplier, receipt.returnedCredits)
                assertEquals(initial.salvage - stake.stake + receipt.returnedCredits, receipt.balanceAfter)
                assertEquals(receipt.balanceAfter, result.state.salvage)
                assertEquals(receipt.outcome.reels, receipt.reels)
                // The entire inventory, paint, stats, history and equipment remain untouched.
                assertEquals(initial.copy(salvage = receipt.balanceAfter, cabinetSpins = 1, lastSpin = receipt), result.state)
                assertNull(mechaSlotProblem(result.state))
                assertEquals(receipt, Json.decodeFromString(MechaSpinReceipt.serializer(), Json.encodeToString(MechaSpinReceipt.serializer(), receipt)))
            }
        }
    }

    @Test
    fun eachBoundarySelectsItsAdvertisedPayout() {
        val boundaries = mapOf(
            0 to MechaSpinOutcome.JACKPOT,
            4 to MechaSpinOutcome.JACKPOT,
            5 to MechaSpinOutcome.WIN,
            24 to MechaSpinOutcome.WIN,
            25 to MechaSpinOutcome.REFUND,
            44 to MechaSpinOutcome.REFUND,
            45 to MechaSpinOutcome.MISS,
            99 to MechaSpinOutcome.MISS
        )
        for (stake in MechaSlotStake.entries) {
            for ((roll, outcome) in boundaries) {
                assertEquals(outcome, starter().slotSpun(stake, roll).state.lastSpin!!.outcome)
            }
        }
    }

    @Test
    fun ceilingReservesTheWholeJackpotAndNeverClipsAnyOutcome() {
        for (stake in MechaSlotStake.entries) {
            val highest = MECHA_CREDIT_CEILING - 4 * stake.stake
            val permitted = starter().copy(salvage = highest)
            assertNull(permitted.slotEntryProblem(stake, 0))
            val jackpot = permitted.slotSpun(stake, 0).state
            assertEquals(50, jackpot.salvage)
            assertEquals(5 * stake.stake, jackpot.lastSpin!!.returnedCredits)
            for (balance in listOf(highest + 1, 50, 70, Int.MAX_VALUE)) {
                val blocked = permitted.copy(salvage = balance)
                assertNotNull(blocked.slotEntryProblem(stake, 0))
                for (roll in listOf(0, 5, 25, 45, 99)) {
                    assertSame(blocked, blocked.slotSpun(stake, roll).state)
                    assertNotNull(blocked.slotSpun(stake, roll).error)
                }
            }
        }
    }

    @Test
    fun reachingTheCeilingDiscardsPendingEarningsButOtherOutcomesPreserveThem() {
        for (stake in MechaSlotStake.entries) {
            val initial = starter().copy(salvage = MECHA_CREDIT_CEILING - 4 * stake.stake, pendingMinutes = 4)
            val jackpot = initial.slotSpun(stake, 0).state
            assertEquals(50, jackpot.salvage)
            assertEquals(0, jackpot.pendingMinutes)
            assertNull(mechaStateProblem(jackpot))
            for (roll in listOf(5, 25, 45)) {
                val next = initial.slotSpun(stake, roll).state
                assertEquals(4, next.pendingMinutes)
                assertNull(mechaStateProblem(next))
            }
        }
    }

    @Test
    fun insufficientFundsAndInvalidRollsLeaveEverythingUntouched() {
        for (stake in MechaSlotStake.entries) {
            val poor = starter().copy(salvage = stake.stake - 1)
            assertNotNull(poor.slotEntryProblem(stake, 0))
            for (roll in 0..99) {
                assertSame(poor, poor.slotSpun(stake, roll).state)
                assertNotNull(poor.slotSpun(stake, roll).error)
            }
            val exact = poor.copy(salvage = stake.stake)
            assertNull(exact.slotEntryProblem(stake, 0))
            assertEquals(0, exact.slotSpun(stake, 99).state.salvage)
            for (roll in listOf(Int.MIN_VALUE, -1, 100, Int.MAX_VALUE)) {
                assertSame(exact, exact.slotSpun(stake, roll).state)
                assertNotNull(exact.slotSpun(stake, roll).error)
            }
        }
    }

    @Test
    fun staleAndRepeatedConfirmationsAreRejectedBeforeTheTransition() {
        for (stake in MechaSlotStake.entries) {
            val initial = starter()
            val sequence = initial.cabinetSpins
            assertNotNull(initial.slotEntryProblem(stake, sequence - 1))
            assertNotNull(initial.slotEntryProblem(stake, sequence + 1))
            assertNull(initial.slotEntryProblem(stake, sequence))
            val after = initial.slotSpun(stake, 25).state
            assertEquals(sequence + 1, after.cabinetSpins)
            assertNotNull(after.slotEntryProblem(stake, sequence))
            assertNull(after.slotEntryProblem(stake, after.cabinetSpins))
        }
    }

    @Test
    fun finalSequenceCanBeCommittedButCanNeverOverflow() {
        val initial = starter().slotSpun(MechaSlotStake.ONE, 45).state
        val penultimate = initial.copy(
            cabinetSpins = Int.MAX_VALUE - 1,
            lastSpin = initial.lastSpin!!.copy(sequence = Int.MAX_VALUE - 1)
        )
        assertNull(mechaSlotProblem(penultimate))
        val final = penultimate.slotSpun(MechaSlotStake.ONE, 25).state
        assertEquals(Int.MAX_VALUE, final.cabinetSpins)
        assertNull(mechaSlotProblem(final))
        assertNotNull(final.slotEntryProblem(MechaSlotStake.ONE, Int.MAX_VALUE))
        assertSame(final, final.slotSpun(MechaSlotStake.ONE, 0).state)
    }

    @Test
    fun receiptValidationRejectsImpossiblePayoutsReelsAndBalancesButAllowsLaterSpending() {
        val valid = starter().slotSpun(MechaSlotStake.FIVE, 0).state
        val receipt = valid.lastSpin!!
        val invalid = listOf(
            valid.copy(cabinetSpins = -1),
            valid.copy(lastSpin = null),
            valid.copy(lastSpin = receipt.copy(sequence = 0)),
            valid.copy(lastSpin = receipt.copy(sequence = receipt.sequence + 1)),
            valid.copy(lastSpin = receipt.copy(reels = listOf(2, 2))),
            valid.copy(lastSpin = receipt.copy(reels = listOf(5, 5, 5))),
            valid.copy(lastSpin = receipt.copy(reels = MechaSpinOutcome.WIN.reels)),
            valid.copy(lastSpin = receipt.copy(returnedCredits = 24)),
            valid.copy(lastSpin = receipt.copy(returnedCredits = Int.MAX_VALUE)),
            valid.copy(lastSpin = receipt.copy(outcome = MechaSpinOutcome.MISS)),
            valid.copy(lastSpin = receipt.copy(balanceAfter = -1)),
            valid.copy(lastSpin = receipt.copy(balanceAfter = 51)),
            valid.copy(lastSpin = receipt.copy(balanceAfter = 24))
        )
        invalid.forEach { assertNotNull(mechaSlotProblem(it)) }
        val missed = starter().slotSpun(MechaSlotStake.FIVE, 99).state
        assertNotNull(mechaSlotProblem(missed.copy(lastSpin = missed.lastSpin!!.copy(balanceAfter = 30))))
        assertNull(mechaSlotProblem(starter()))
        assertNull(mechaSlotProblem(valid.copy(salvage = 0)))
        assertNull(mechaSlotProblem(valid.copy(salvage = 50)))
    }

    @Test
    fun newCreditSpinsPreserveHistoricalItemReceiptsWithoutReinterpretingThem() {
        val base = starter()
        val historical = MechaLegacySpinReceipt(
            sequence = 9,
            tier = MechaLegacyCabinetTier.PART,
            reels = listOf(0, 0, 0),
            outcome = MechaLegacySpinOutcome.REWARD,
            returnedCredits = 1,
            partId = base.owned.first()
        )
        val initial = base.copy(cabinetSpins = 9, legacyLastSpin = historical)
        assertNull(initial.lastSpin)
        assertNull(mechaSlotProblem(initial))
        assertNotNull(mechaSlotProblem(initial.copy(cabinetSpins = 10)))
        for (roll in listOf(0, 5, 25, 45)) {
            val next = initial.slotSpun(MechaSlotStake.THREE, roll).state
            assertEquals(10, next.cabinetSpins)
            assertEquals(historical, next.legacyLastSpin)
            assertEquals(10, next.lastSpin!!.sequence)
            assertEquals(initial.owned, next.owned)
            assertNull(mechaStateProblem(next))
        }
    }

    @Test
    fun owningEveryPartAndPaintNeverDisablesCreditWagers() {
        val initial = starter().copy(
            owned = MechaRulesTest.parts.map { it.id }.toSet(),
            ownedPaints = MechaPaintScheme.entries.flatMap { scheme -> MechaFinish.entries.map { MechaPaint(scheme, it) } }.toSet()
        )
        for (stake in MechaSlotStake.entries) {
            for (roll in listOf(0, 5, 25, 45)) {
                assertNull(initial.slotEntryProblem(stake, 0))
                val result = initial.slotSpun(stake, roll)
                assertNull(result.error)
                assertEquals(initial.owned, result.state.owned)
                assertEquals(initial.ownedPaints, result.state.ownedPaints)
                assertEquals(initial.origins, result.state.origins)
            }
        }
    }
}
