package io.github.esko997.sortie.mecha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaArcadeEnginesTest {
    @Test
    fun anUnboundCoinPersistsAsAChooserAndCannotRunAnyEngine() {
        val session = MechaArcadeSession()
        val coin = MechaArcadeTicket(1, null, 99L)
        session.sync(coin)
        assertEquals(MechaArcadePhase.CHOOSING, session.run.value.phase)
        assertFalse(session.resume(Any()))
        assertNull(session.engine)
        assertNull(session.scrap)
        assertNull(session.circuit)
        session.sync(coin.copy(game = MechaArcadeGame.SCRAP_CATCH))
        assertEquals(MechaArcadePhase.PAUSED, session.run.value.phase)
        assertNotNull(session.scrap)
        assertNull(session.engine)
        assertNull(session.circuit)
        assertEquals(coin.sequence, session.run.value.ticket!!.sequence)
        assertEquals(coin.seed, session.run.value.ticket!!.seed)
    }

    @Test
    fun catchingEveryBoltAndAvoidingJunkReachesTheExactMaximumInFortyFiveSeconds() {
        val game = MechaScrapEngine(1234L)
        repeat(5_401) {
            var nearest = -1
            var lowest = -1f
            for (i in 0 until MechaScrapEngine.DROP_COUNT) {
                if (game.active(i) && game.y(i) > lowest) {
                    nearest = i
                    lowest = game.y(i)
                }
            }
            if (nearest >= 0) {
                game.moveTo(
                    if (game.junk(nearest)) {
                        if (game.x(nearest) > 0.5f) 0.105f else 0.895f
                    } else {
                        game.x(nearest)
                    }
                )
            }
            game.step()
        }
        assertTrue(game.ended)
        assertEquals(45_000_000_000L, game.elapsedNanos)
        assertEquals(0, game.secondsLeft)
        assertEquals(450, game.score)
        game.step()
        assertEquals(450, game.score)
    }

    @Test
    fun junkCannotDriveTheScoreBelowZeroAndTheTrayCannotLeaveTheField() {
        val game = MechaScrapEngine(88L)
        game.moveTo(-100f)
        assertEquals(MechaScrapEngine.TRAY_HALF, game.tray, 0f)
        game.moveTo(Float.NaN)
        assertEquals(MechaScrapEngine.TRAY_HALF, game.tray, 0f)
        game.moveTo(100f)
        assertEquals(1f - MechaScrapEngine.TRAY_HALF, game.tray, 0f)
        repeat(5_401) {
            var nearest = -1
            var lowest = -1f
            for (i in 0 until MechaScrapEngine.DROP_COUNT) {
                if (game.active(i) && game.y(i) > lowest) {
                    nearest = i
                    lowest = game.y(i)
                }
            }
            if (nearest >= 0) {
                game.moveTo(if (game.junk(nearest)) game.x(nearest) else if (game.x(nearest) > 0.5f) 0.105f else 0.895f)
            }
            game.step()
            assertEquals(0, game.score)
        }
    }

    @Test
    fun scrapSeedReplaysTheSameDropsAndPausingClearsHeldMovement() {
        val a = MechaScrapEngine(88L)
        val b = MechaScrapEngine(88L)
        repeat(600) {
            a.step()
            b.step()
            for (i in 0 until MechaScrapEngine.DROP_COUNT) {
                assertEquals(a.x(i), b.x(i), 0f)
                assertEquals(a.y(i), b.y(i), 0f)
                assertEquals(a.junk(i), b.junk(i))
                assertEquals(a.active(i), b.active(i))
            }
        }
        val session = MechaArcadeSession()
        val host = Any()
        session.sync(MechaArcadeTicket(1, MechaArcadeGame.SCRAP_CATCH, 88L))
        session.resume(host)
        session.steer(host, 1)
        session.advance(host, MechaFlightEngine.STEP_NANOS * 6)
        session.pause(host)
        val tray = session.scrap!!.tray
        assertEquals(0, session.scrap!!.direction)
        session.advance(host, Long.MAX_VALUE)
        session.resume(host)
        session.advance(host, MechaFlightEngine.STEP_NANOS)
        assertEquals(tray, session.scrap!!.tray, 0f)
    }

    @Test
    fun matchingBoardHasSixSeededPairsAndAPerfectRunScoresOneHundredFifty() {
        val game = MechaCircuitEngine(123L)
        val replay = MechaCircuitEngine(123L)
        val counts = IntArray(6)
        for (i in 0 until MechaCircuitEngine.CELLS) {
            assertEquals(game.symbol(i), replay.symbol(i))
            counts[game.symbol(i)]++
        }
        counts.forEach { assertEquals(2, it) }
        for (symbol in 0 until 6) {
            for (i in 0 until MechaCircuitEngine.CELLS) if (game.symbol(i) == symbol) assertTrue(game.choose(i))
        }
        assertTrue(game.ended)
        assertEquals(6, game.attempts)
        assertEquals(6, game.pairs)
        assertEquals(150, game.score)
        assertFalse(game.choose(0))
    }

    @Test
    fun mismatchesRejectExtraInputThenHideAndExhaustAfterTwentyFourAttempts() {
        val game = MechaCircuitEngine(5L)
        val other = (1 until MechaCircuitEngine.CELLS).first { game.symbol(it) != game.symbol(0) }
        repeat(24) {
            assertTrue(game.choose(0))
            assertFalse(game.choose(0))
            assertTrue(game.choose(other))
            assertFalse(game.choose(11))
            assertTrue(game.revealed(0))
            assertTrue(game.revealed(other))
            assertFalse(game.ended)
            repeat(103) { game.step() }
            assertFalse(game.revealed(0))
            assertFalse(game.revealed(other))
        }
        assertTrue(game.ended)
        assertEquals(24, game.attempts)
        assertEquals(0, game.score)
    }

    @Test
    fun aPausedRevealKeepsItsRemainingTimeAndIdleMatchingNeedsNoFrames() {
        val session = MechaArcadeSession()
        val host = Any()
        session.sync(MechaArcadeTicket(1, MechaArcadeGame.CIRCUIT_MATCH, 5L))
        session.resume(host)
        assertFalse(session.needsFrames)
        val game = session.circuit!!
        val other = (1 until MechaCircuitEngine.CELLS).first { game.symbol(it) != game.symbol(0) }
        session.choose(host, 0)
        assertFalse(session.needsFrames)
        session.choose(host, other)
        assertTrue(session.needsFrames)
        session.advance(host, MechaFlightEngine.STEP_NANOS * 6)
        session.pause(host)
        val remaining = game.remainingReveal
        session.advance(host, Long.MAX_VALUE)
        session.choose(host, 11)
        assertEquals(remaining, game.remainingReveal)
        session.resume(host)
        repeat(20) { session.advance(host, MechaFlightEngine.STEP_NANOS * 6) }
        assertEquals(0L, game.remainingReveal)
        assertFalse(session.needsFrames)
        assertEquals(MechaArcadePhase.RUNNING, session.run.value.phase)
    }

    @Test
    fun engineScoreDomainsRejectImpossibleStoredBestValues() {
        assertTrue(MechaArcadeGame.SCRAP_CATCH.acceptsScore(450))
        assertFalse(MechaArcadeGame.SCRAP_CATCH.acceptsScore(455))
        assertFalse(MechaArcadeGame.SCRAP_CATCH.acceptsScore(11))
        assertTrue(MechaArcadeGame.CIRCUIT_MATCH.acceptsScore(50))
        assertTrue(MechaArcadeGame.CIRCUIT_MATCH.acceptsScore(60))
        assertTrue(MechaArcadeGame.CIRCUIT_MATCH.acceptsScore(150))
        assertFalse(MechaArcadeGame.CIRCUIT_MATCH.acceptsScore(55))
        assertFalse(MechaArcadeGame.CIRCUIT_MATCH.acceptsScore(155))
    }
}
