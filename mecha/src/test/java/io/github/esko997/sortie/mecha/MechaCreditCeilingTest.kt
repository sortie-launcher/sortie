package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.INIT
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.MINUTE
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.parts
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.session
import io.github.esko997.sortie.mecha.MechaRulesTest.Companion.starter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaCreditCeilingTest {
    @Test
    fun crossingCeilingGrantsOnlyRemainingRoomAndDiscardsEveryExcessMinute() {
        val initial = starter().copy(salvage = 49, creditedMinutes = 4, pendingMinutes = 4)
        val play = session("crossing", INIT + MINUTE, 12)
        val state = initial.credited(listOf(play)).state
        assertEquals(MECHA_CREDIT_CEILING, state.salvage)
        assertEquals(0, state.pendingMinutes)
        assertEquals(16, state.creditedMinutes)
        assertEquals(initial.charge - 2, state.charge)
        assertEquals(initial.bond + 1, state.bond)
        assertEquals(setOf(mechaSessionKey(play)), state.processedSessions)
        assertNull(mechaStateProblem(state, parts))
        assertSame(state, state.credited(listOf(play)).state)
    }

    @Test
    fun saturatedPlayStillCountsHistoryStatsAndDedupWithoutBankingCredits() {
        val initial = starter().copy(salvage = MECHA_CREDIT_CEILING)
        val sessions = listOf(session("first", INIT + MINUTE, 120), session("second", INIT + 200 * MINUTE, 7))
        val state = initial.credited(sessions + sessions).state
        assertEquals(MECHA_CREDIT_CEILING, state.salvage)
        assertEquals(0, state.pendingMinutes)
        assertEquals(127, state.creditedMinutes)
        assertEquals(2, state.processedSessions.size)
        assertEquals(initial.bond + 2, state.bond)
        assertTrue(state.charge < initial.charge)
        assertSame(state, state.credited(sessions.reversed()).state)
        assertEquals(state, initial.credited(sessions.take(1)).state.credited(sessions).state)
    }

    @Test
    fun grandfatheredBalancesStayIntactAndDoNotGrowEvenAtIntegerMaximum() {
        for (balance in listOf(51, 120, Int.MAX_VALUE)) {
            val initial = starter().copy(salvage = balance)
            val state = initial.credited(listOf(session("overflow", INIT + MINUTE, 120))).state
            assertEquals(balance, state.salvage)
            assertEquals(120, state.creditedMinutes)
            assertEquals(0, state.pendingMinutes)
            assertNull(mechaStateProblem(state, parts))
        }
    }

    @Test
    fun spendingBelowCeilingResumesOnlyNewProgressNeverSaturatedHistory() {
        val oldPlay = session("capped", INIT + MINUTE, 119)
        val capped = starter().copy(salvage = 50).credited(listOf(oldPlay)).state
        val spent = capped.buyingPart("foundry_head_1", parts).state
        assertEquals(48, spent.salvage)
        assertSame(spent, spent.credited(listOf(oldPlay)).state)
        val newPlay = session("fresh", INIT + 200 * MINUTE, 4)
        val pending = spent.credited(listOf(oldPlay, newPlay)).state
        assertEquals(48, pending.salvage)
        assertEquals(4, pending.pendingMinutes)
        val earned = pending.credited(listOf(oldPlay, newPlay, session("one", INIT + 300 * MINUTE, 1))).state
        assertEquals(49, earned.salvage)
        assertEquals(0, earned.pendingMinutes)
        assertEquals(124, earned.creditedMinutes)
    }

    @Test
    fun legacyMigrationOnlyDiscardsSaturatedProgressNotBalancesOrHistoricalCounters() {
        for (balance in listOf(49, 50, 80)) {
            val legacy = starter().copy(
                version = 1,
                salvage = balance,
                pendingMinutes = 4,
                creditedMinutes = 124,
                salvageRuns = 8,
                processedSessions = setOf("historic@1")
            )
            assertNull(mechaStateProblem(legacy, parts))
            val upgraded = legacy.upgraded()
            assertEquals(legacy.copy(version = 4, pendingMinutes = if (balance >= 50) 0 else 4), upgraded)
            assertNull(mechaStateProblem(upgraded, parts))
            if (balance >= 50) assertNotNull(mechaStateProblem(legacy.copy(version = 2), parts))
        }
    }

    @Test
    fun restoreCannotPaySaturatedSessionsAgainAfterSpending() {
        val oldPlay = session("saturated", INIT + MINUTE, 120)
        val current = starter().copy(salvage = 50).credited(listOf(oldPlay)).state
        val restored = starter().copy(salvage = 2).restoredOver(current)
        assertSame(restored, restored.credited(listOf(oldPlay)).state)
        val newPlay = session("new", INIT + 200 * MINUTE, 5)
        val earned = restored.credited(listOf(oldPlay, newPlay)).state
        assertEquals(3, earned.salvage)
        assertEquals(5, earned.creditedMinutes)
        assertEquals(2, earned.processedSessions.size)
    }

    @Test
    fun capCrossingDoesNotDependOnSessionDeliveryBatches() {
        val initial = starter().copy(salvage = 48, pendingMinutes = 3, creditedMinutes = 3)
        val first = session("first", INIT + MINUTE, 3)
        val second = session("second", INIT + 10 * MINUTE, 7)
        val third = session("third", INIT + 20 * MINUTE, 4)
        val sessions = listOf(first, second, third)
        val together = initial.credited(sessions).state
        val separately = initial.credited(listOf(first)).state.credited(listOf(first, second)).state.credited(sessions).state
        assertEquals(together, separately)
        assertEquals(50, together.salvage)
        assertEquals(0, together.pendingMinutes)
        assertEquals(17, together.creditedMinutes)
    }
}
