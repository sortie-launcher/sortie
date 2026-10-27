package io.github.esko997.sortie.mecha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaArcadeSessionTest {
    private val ticket = MechaArcadeTicket(1, MechaArcadeGame.FLAPPY_FLIGHT, 42L)

    @Test
    fun aCommittedTicketIsReadyButNeverStartsItself() {
        val session = MechaArcadeSession()
        val host = Any()
        assertFalse(session.resume(host))
        session.sync(ticket)
        assertEquals(MechaArcadePhase.PAUSED, session.run.value.phase)
        session.advance(host, Long.MAX_VALUE)
        session.flap(host)
        assertEquals(0L, session.engine!!.elapsedNanos)
        assertEquals(0f, session.engine!!.velocity, 0f)
        assertTrue(session.resume(host))
    }

    @Test
    fun onlyOneHostCanAdvanceFlapOrPauseTheRun() {
        val session = MechaArcadeSession()
        val first = Any()
        val other = Any()
        session.sync(ticket)
        assertTrue(session.resume(first))
        assertFalse(session.resume(other))
        session.flap(other)
        session.advance(other, Long.MAX_VALUE)
        session.pause(other)
        assertEquals(0L, session.engine!!.elapsedNanos)
        assertTrue(session.owns(first))
        session.flap(first)
        assertTrue(session.engine!!.velocity < 0f)
        session.advance(first, MechaFlightEngine.STEP_NANOS)
        assertEquals(MechaFlightEngine.STEP_NANOS, session.engine!!.elapsedNanos)
    }

    @Test
    fun hidingClearsOwnershipAndResumeRetainsTheSamePaidFlight() {
        val session = MechaArcadeSession()
        val first = Any()
        val second = Any()
        session.sync(ticket)
        val engine = session.engine!!
        session.resume(first)
        session.flap(first)
        session.advance(first, MechaFlightEngine.STEP_NANOS * 3)
        session.pause(first)
        val pausedY = engine.y
        val pausedTime = engine.elapsedNanos
        session.advance(first, Long.MAX_VALUE)
        session.flap(first)
        assertEquals(pausedY, engine.y, 0f)
        assertEquals(pausedTime, engine.elapsedNanos)
        session.sync(ticket)
        assertSame(engine, session.engine)
        assertTrue(session.resume(second))
        assertFalse(session.owns(first))
        session.advance(second, MechaFlightEngine.STEP_NANOS)
        assertEquals(pausedTime + MechaFlightEngine.STEP_NANOS, engine.elapsedNanos)
    }

    @Test
    fun stallsAndNegativeDeltasCannotCreateUnboundedCatchUp() {
        val session = MechaArcadeSession()
        val host = Any()
        session.sync(ticket)
        session.resume(host)
        session.advance(host, -1L)
        assertEquals(0L, session.engine!!.elapsedNanos)
        session.advance(host, Long.MAX_VALUE)
        assertEquals(MechaFlightEngine.STEP_NANOS * 6, session.engine!!.elapsedNanos)
        session.advance(host, 0L)
        assertEquals(MechaFlightEngine.STEP_NANOS * 6, session.engine!!.elapsedNanos)
    }

    @Test
    fun aRestartRecreatesTheSameCourseWithoutResumingOrAnotherTicket() {
        val first = MechaArcadeSession()
        val restarted = MechaArcadeSession()
        first.sync(ticket)
        restarted.sync(ticket)
        assertNotSame(first.engine, restarted.engine)
        assertEquals(ticket, restarted.run.value.ticket)
        assertEquals(MechaArcadePhase.PAUSED, restarted.run.value.phase)
        for (i in 0 until MechaFlightEngine.PIPE_COUNT) {
            assertEquals(first.engine!!.pipeX(i), restarted.engine!!.pipeX(i), 0f)
            assertEquals(first.engine!!.gapY(i), restarted.engine!!.gapY(i), 0f)
        }
    }

    @Test
    fun anEndedRunWaitsForTheCommittedClearAndCannotRestart() {
        val session = MechaArcadeSession()
        val host = Any()
        session.sync(ticket)
        session.resume(host)
        repeat(100) { session.advance(host, MechaFlightEngine.STEP_NANOS * 6) }
        assertEquals(MechaArcadePhase.FINISHED, session.run.value.phase)
        assertTrue(session.engine!!.ended)
        assertFalse(session.resume(host))
        assertTrue(session.claimFinish())
        assertFalse(session.claimFinish())
        val ended = session.engine
        session.sync(ticket) // A failed write leaves the committed ticket unchanged.
        assertEquals(MechaArcadePhase.FINISHED, session.run.value.phase)
        assertSame(ended, session.engine)
        session.sync(null)
        assertEquals(MechaArcadePhase.SAVED, session.run.value.phase)
        assertSame(ended, session.engine)
        session.sync(null)
        assertEquals(MechaArcadePhase.SAVED, session.run.value.phase)
    }

    @Test
    fun abandoningClearsThePausedRunAndNewTicketsResetTheSimulation() {
        val session = MechaArcadeSession()
        session.sync(ticket)
        session.sync(null)
        assertEquals(MechaArcadePhase.EMPTY, session.run.value.phase)
        assertEquals(null, session.engine)
        session.sync(ticket.copy(sequence = 2, seed = 99L))
        assertEquals(0, session.engine!!.score)
        assertEquals(0L, session.engine!!.elapsedNanos)
        assertEquals(MechaArcadePhase.PAUSED, session.run.value.phase)
    }

    @Test
    fun gravityAndRepeatedFlapsEndAtTheFloorAndCeiling() {
        val falling = MechaFlightEngine(42L)
        repeat(240) { falling.step() }
        assertTrue(falling.ended)
        assertTrue(falling.y + MechaFlightEngine.BOT_RADIUS >= 1f)
        val elapsed = falling.elapsedNanos
        falling.flap()
        falling.step()
        assertEquals(elapsed, falling.elapsedNanos)
        val rising = MechaFlightEngine(42L)
        repeat(240) {
            rising.flap()
            rising.step()
        }
        assertTrue(rising.ended)
        assertTrue(rising.y - MechaFlightEngine.BOT_RADIUS <= 0f)
    }

    @Test
    fun fixedInputReplaysExactlyAndScoresPassedPipesWithBoundedStorage() {
        val first = MechaFlightEngine(42L)
        val replay = MechaFlightEngine(42L)
        repeat(7_200) {
            var nearest = -1
            var leftmost = Float.MAX_VALUE
            for (i in 0 until MechaFlightEngine.PIPE_COUNT) {
                val x = first.pipeX(i)
                if (x + MechaFlightEngine.PIPE_WIDTH >= MechaFlightEngine.BOT_X - MechaFlightEngine.BOT_RADIUS && x < leftmost) {
                    nearest = i
                    leftmost = x
                }
            }
            val target = if (nearest < 0) 0.5f else first.gapY(nearest)
            if (first.y > target + 0.02f) {
                first.flap()
                replay.flap()
            }
            first.step()
            replay.step()
            assertEquals(first.y, replay.y, 0f)
            assertEquals(first.score, replay.score)
            assertEquals(first.ended, replay.ended)
            for (i in 0 until MechaFlightEngine.PIPE_COUNT) {
                assertEquals(first.pipeX(i), replay.pipeX(i), 0f)
                assertEquals(first.gapY(i), replay.gapY(i), 0f)
                assertTrue(first.gapY(i) in 0.30f..0.70f)
                assertTrue(first.pipeX(i) < 1f + MechaFlightEngine.PIPE_SPACING * MechaFlightEngine.PIPE_COUNT)
            }
        }
        assertTrue("A guided flight passes multiple obstacles", first.score >= 5)
    }

    @Test
    fun touchingThePipeEndsFlightWithoutAwardingThatPipe() {
        val engine = MechaFlightEngine(42L)
        repeat(600) {
            if (engine.y > 0.65f) engine.flap()
            engine.step()
        }
        assertTrue(engine.ended)
        assertTrue("Collision was a pipe, not a boundary", engine.y in 0.5f..0.9f)
        assertEquals(0, engine.score)
    }

    @Test
    fun splittingFramesDoesNotChangeTheFixedStepSimulation() {
        val one = MechaArcadeSession()
        val split = MechaArcadeSession()
        val host = Any()
        one.sync(ticket)
        split.sync(ticket)
        one.resume(host)
        split.resume(host)
        one.flap(host)
        split.flap(host)
        one.advance(host, MechaFlightEngine.STEP_NANOS * 6)
        repeat(12) { split.advance(host, MechaFlightEngine.STEP_NANOS / 2) }
        split.advance(host, 6L) // Each integer half-step dropped one nanosecond.
        assertEquals(one.engine!!.elapsedNanos, split.engine!!.elapsedNanos)
        assertEquals(one.engine!!.y, split.engine!!.y, 0f)
        assertEquals(one.engine!!.velocity, split.engine!!.velocity, 0f)
    }

    @Test
    fun scoreValidationAllowsTheWholeNonnegativeCounterRange() {
        assertFalse(MechaArcadeGame.FLAPPY_FLIGHT.acceptsScore(-1))
        assertTrue(MechaArcadeGame.FLAPPY_FLIGHT.acceptsScore(0))
        assertTrue(MechaArcadeGame.FLAPPY_FLIGHT.acceptsScore(Int.MAX_VALUE))
    }
}
