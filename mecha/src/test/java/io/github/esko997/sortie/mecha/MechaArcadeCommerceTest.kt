package io.github.esko997.sortie.mecha

import io.github.esko997.sortie.ui.SortiePalette
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaArcadeCommerceTest {
    private val game = MechaArcadeGame.FLAPPY_FLIGHT

    @Test
    fun themesBuyOnceWithoutApplyingAndOwnedSelectionsAreFreeEvenWhenHidden() {
        var state = MechaRulesTest.starter().copy(salvage = 24, enabled = false)
        for (palette in SortiePalette.entries.filter { it != SortiePalette.DEFAULT }) {
            assertSame(state, state.selectingTheme(palette).state)
            val bought = state.buyingTheme(palette).state
            assertEquals(state.salvage - 8, bought.salvage)
            assertEquals(state.selectedTheme, bought.selectedTheme)
            assertSame(bought, bought.buyingTheme(palette).state)
            val applied = bought.selectingTheme(palette).state
            assertEquals(palette.id, applied.selectedTheme)
            assertEquals(bought.salvage, applied.salvage)
            assertFalse(applied.enabled)
            assertNull(mechaStateProblem(applied))
            state = applied
        }
        assertEquals(0, state.salvage)
        val original = state.selectingTheme(SortiePalette.DEFAULT).state
        assertEquals("default", original.selectedTheme)
        assertEquals(0, original.salvage)
        assertSame(original, original.buyingTheme(SortiePalette.DEFAULT).state)
        val poor = MechaRulesTest.starter()
        assertSame(poor, poor.buyingTheme(SortiePalette.HARBOR).state)
    }

    @Test
    fun introductionDoesNotEnableHiddenCompanionAndAcknowledgementIsIdempotent() {
        val initial = MechaRulesTest.starter()
        assertFalse(initial.hangarIntroSeen)
        val seen = initial.introduced(false).state
        assertTrue(seen.hangarIntroSeen)
        assertTrue(seen.enabled)
        assertSame(seen, seen.introduced(false).state)
        val hidden = seen.introduced(true).state
        assertEquals(seen.copy(enabled = false), hidden)
        assertSame(hidden, hidden.introduced(false).state)
        assertSame(hidden, hidden.introduced(true).state)
        assertEquals(initial.copy(enabled = false, hangarIntroSeen = true), initial.copy(enabled = false).introduced(false).state)
    }

    @Test
    fun entryDebitsOnceAndExactTicketGuardsEveryResultWithoutRewards() {
        val initial = MechaRulesTest.starter().copy(salvage = 10)
        val paid = initial.paidRound(game, Long.MIN_VALUE)
        val ticket = paid.activeArcade!!
        assertEquals(MechaArcadeTicket(1, game, Long.MIN_VALUE), ticket)
        assertEquals(initial.copy(salvage = 9, arcadeRuns = 1, activeArcade = ticket), paid)
        assertSame(paid, paid.insertingArcadeCoin(0, 4).state)
        assertSame(paid, paid.insertingArcadeCoin(1, 4).state)
        assertSame(paid, paid.finishingArcade(ticket.copy(seed = 2), 10).state)
        assertSame(paid, paid.abandoningArcade(ticket.copy(sequence = 2)).state)
        assertSame(paid, paid.finishingArcade(ticket, -1).state)
        val finished = paid.finishingArcade(ticket, 12).state
        assertEquals(paid.copy(activeArcade = null, arcadeBest = mapOf(game to 12)), finished)
        assertSame(finished, finished.finishingArcade(ticket, 20).state)
        assertSame(finished, finished.abandoningArcade(ticket).state)
        val next = finished.paidRound(game, 4)
        assertSame(next, next.finishingArcade(ticket, 20).state)
        assertSame(next, next.abandoningArcade(ticket).state)
        val lower = next.finishingArcade(next.activeArcade!!, 3).state
        assertEquals(12, lower.arcadeBest[game])
        val third = lower.paidRound(game, 7)
        val abandoned = third.abandoningArcade(third.activeArcade!!).state
        assertEquals(third.copy(activeArcade = null), abandoned)
        assertEquals(7, abandoned.salvage)
        assertEquals(initial.processedSessions, abandoned.processedSessions)
        assertEquals(initial.creditedMinutes, abandoned.creditedMinutes)
        assertEquals(initial.owned, abandoned.owned)
        assertNull(mechaStateProblem(abandoned))
    }

    @Test
    fun scoresHaveNoTimedCapAndEntryCountersNeverOverflow() {
        val initial = MechaRulesTest.starter()
        val poor = initial.copy(salvage = 0)
        assertSame(poor, poor.insertingArcadeCoin(0, 0).state)
        val full = initial.copy(arcadeRuns = Int.MAX_VALUE)
        assertSame(full, full.insertingArcadeCoin(Int.MAX_VALUE, 0).state)
        val paid = initial.copy(arcadeRuns = Int.MAX_VALUE - 1).paidRound(game, Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, paid.activeArcade!!.sequence)
        val finished = paid.finishingArcade(paid.activeArcade!!, Int.MAX_VALUE).state
        assertEquals(Int.MAX_VALUE, finished.arcadeBest[game])
        assertNull(mechaStateProblem(finished))
        val zero = initial.paidRound(game, 0)
        assertEquals(0, zero.finishingArcade(zero.activeArcade!!, 0).state.arcadeBest[game])
    }

    @Test
    fun versionTwoCommerceAndHistorySurviveUpgradeExactly() {
        val legacy = MechaRulesTest.starter().copy(
            version = 2,
            salvage = 73,
            processedSessions = setOf("old@1"),
            salvageRuns = 14,
            ownedPaints = setOf(MechaPaint(), MechaPaint(finish = MechaFinish.ALLOY)),
            equippedPaints = mapOf(MechaSlot.HEAD to MechaPaint(finish = MechaFinish.ALLOY)),
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
        assertNull(mechaStateProblem(legacy))
        val upgraded = legacy.upgraded()
        assertEquals(legacy.copy(version = 4), upgraded)
        assertSame(upgraded, upgraded.upgraded())
        assertEquals(73, upgraded.salvage)
        assertFalse(upgraded.hangarIntroSeen)
    }

    @Test
    fun malformedThemesTicketsScoresAndLegacyNewFieldsAreRejected() {
        val initial = MechaRulesTest.starter()
        val ticket = MechaArcadeTicket(1, game, 0)
        val invalid = listOf(
            initial.copy(ownedThemes = emptySet()),
            initial.copy(ownedThemes = setOf("default", "unknown")),
            initial.copy(selectedTheme = "unknown"),
            initial.copy(selectedTheme = "harbor"),
            initial.copy(arcadeRuns = -1),
            initial.copy(activeArcade = ticket),
            initial.copy(arcadeRuns = 1, activeArcade = ticket.copy(sequence = 0)),
            initial.copy(arcadeRuns = 2, activeArcade = ticket),
            initial.copy(arcadeBest = mapOf(game to 0)),
            initial.copy(arcadeRuns = 1, activeArcade = ticket, arcadeBest = mapOf(game to 0)),
            initial.copy(arcadeRuns = 1, arcadeBest = mapOf(game to -1)),
            initial.copy(version = 2, ownedThemes = setOf("default", "harbor")),
            initial.copy(version = 2, hangarIntroSeen = true),
            initial.copy(version = 2, arcadeRuns = 1),
            initial.copy(version = 1, hangarIntroSeen = true)
        )
        for (state in invalid) {
            assertNotNull(mechaStateProblem(state))
            assertTrue(runCatching { state.upgraded() }.isFailure)
        }
    }

    @Test
    fun versionThreeSerializationPreservesPaidSeedAndThemeWhileUnknownGameIsRejected() {
        val state = MechaRulesTest.starter().copy(salvage = 20).buyingTheme(SortiePalette.PLUM).state
            .selectingTheme(SortiePalette.PLUM).state.introduced(true).state.paidRound(game, Long.MIN_VALUE)
        val text = Json.encodeToString(MechaState.serializer(), state)
        val decoded = Json.decodeFromString(MechaState.serializer(), text)
        assertEquals(state, decoded)
        assertNull(mechaStateProblem(decoded))
        assertTrue(
            runCatching {
                Json.decodeFromString(MechaState.serializer(), text.replace("FLAPPY_FLIGHT", "UNKNOWN_GAME"))
            }.isFailure
        )
    }

    @Test
    fun insertedCoinIsDurableUnboundAndCanOnlySelectOneGameWithoutChargingAgain() {
        for (choice in MechaArcadeGame.entries) {
            val initial = MechaRulesTest.starter()
            val inserted = initial.insertingArcadeCoin(0, 42).state
            val coin = inserted.activeArcade!!
            assertEquals(MechaArcadeTicket(1, null, 42), coin)
            assertEquals(initial.copy(salvage = initial.salvage - 1, arcadeRuns = 1, activeArcade = coin), inserted)
            assertNull(mechaStateProblem(inserted))
            val decoded = Json.decodeFromString(MechaState.serializer(), Json.encodeToString(MechaState.serializer(), inserted))
            assertEquals(inserted, decoded)
            assertSame(inserted, inserted.finishingArcade(coin, 0).state)
            assertSame(inserted, inserted.insertingArcadeCoin(0, 1).state)
            assertSame(inserted, inserted.insertingArcadeCoin(1, 1).state)
            assertSame(inserted, inserted.selectingArcadeGame(coin.copy(seed = 99), choice).state)
            val selected = inserted.selectingArcadeGame(coin, choice).state
            assertEquals(inserted.copy(activeArcade = coin.copy(game = choice)), selected)
            for (other in MechaArcadeGame.entries) {
                assertSame(selected, selected.selectingArcadeGame(coin, other).state)
                assertSame(selected, selected.selectingArcadeGame(selected.activeArcade!!, other).state)
            }
            assertSame(selected, selected.abandoningArcade(coin).state)
            val finished = selected.finishingArcade(selected.activeArcade!!, 0).state
            assertEquals(selected.copy(activeArcade = null, arcadeBest = mapOf(choice to 0)), finished)
            assertEquals(inserted.copy(activeArcade = null), inserted.abandoningArcade(coin).state)
        }
    }

    @Test
    fun allThreeGamesKeepIndependentBoundedBestScoresWithoutRewards() {
        val initial = MechaRulesTest.starter().copy(salvage = 10)
        val best = mapOf(
            MechaArcadeGame.FLAPPY_FLIGHT to Int.MAX_VALUE,
            MechaArcadeGame.SCRAP_CATCH to 450,
            MechaArcadeGame.CIRCUIT_MATCH to 150
        )
        var state = initial
        for ((choice, score) in best) {
            val paid = state.paidRound(choice, choice.ordinal.toLong())
            val ticket = paid.activeArcade!!
            assertSame(paid, paid.finishingArcade(ticket, -1).state)
            if (choice != MechaArcadeGame.FLAPPY_FLIGHT) {
                assertSame(paid, paid.finishingArcade(ticket, score + 1).state)
                assertSame(paid, paid.finishingArcade(ticket, 1).state)
            }
            state = paid.finishingArcade(ticket, score).state
            assertNull(mechaStateProblem(state))
        }
        assertEquals(initial.copy(salvage = 7, arcadeRuns = 3, arcadeBest = best), state)
    }

    private fun MechaState.paidRound(game: MechaArcadeGame, seed: Long): MechaState {
        val inserted = insertingArcadeCoin(arcadeRuns, seed).state
        return inserted.selectingArcadeGame(inserted.activeArcade!!, game).state
    }
}
