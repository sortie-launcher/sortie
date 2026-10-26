package io.github.esko997.sortie.mecha

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaThrowTest {
    // the lower display, its top edge facing the other one: offset origin, scale 3, 40dp taps at 2.625x
    private val lower = MechaRect(24, 60, 1264, 1140)
    private val lowerScale = 3
    private val lowerTap = 105

    // the upper display, its bottom edge facing: wider, scale 2, 40dp taps at 1.75x
    private val upper = MechaRect(100, 20, 2020, 1100)
    private val upperScale = 2
    private val upperTap = 70

    private val steps = 200

    private fun v(x: Double, y: Double) = MechaVelocity(x.toFloat(), y.toFloat())

    private fun up(taps: Double, sideways: Double = 0.0) = v(sideways, -taps * lowerTap)

    private fun throwUp(x: Int, floor: Int, velocity: MechaVelocity) = MechaThrows.exit(x, floor, lowerScale, velocity, lower, MechaEdge.TOP, lowerTap)

    private fun at(x: Int, floor: Int, scale: Int) = MechaPlacement("ledge", MechaSpotKind.LEDGE, x, floor, Int.MAX_VALUE, MechaBody.art(x, floor, scale))

    private fun floors(f: MechaFlight) = IntArray(steps + 1) { f.floorAt(it.toFloat() / steps) }

    private fun xs(f: MechaFlight) = IntArray(steps + 1) { f.xAt(it.toFloat() / steps) }

    /** Average speed (pixels per second) over progress [from] to [to]. */
    private fun speed(f: MechaFlight, from: Float, to: Float, value: (Float) -> Int): Double = (value(to) - value(from)) / ((to - from) * f.durationMs / 1000.0)

    private fun assertClose(expected: Double, actual: Double, relative: Double) =
        assertTrue("expected about $expected, was $actual", abs(actual - expected) <= abs(expected) * relative)

    // ---- out ----

    @Test
    fun aFlingUpLeavesThroughTheTopFullyAndSlowsOnTheWay() {
        val e = throwUp(500, 660, up(30.0))!!
        val p = e.path
        assertEquals(500, p.xAt(0f))
        assertEquals(660, p.floorAt(0f))
        assertEquals(p.endFloor, p.floorAt(1f))
        // the whole frame (its bottom row hangs one pixel under the floor) is past the edge
        assertTrue(p.endFloor + lowerScale <= lower.top)
        assertTrue(p.durationMs in 1..MechaThrows.EXIT_MS)
        val f = floors(p)
        assertTrue((0 until steps).all { f[it + 1] <= f[it] })
        // straight up, slowed by gravity but still going
        assertEquals(0f, e.transfer.vx, 0f)
        assertTrue(e.transfer.vy < 0f && e.transfer.vy > -30f)
        assertEquals((500 + MechaBody.width(lowerScale) / 2 - lower.left).toFloat() / lower.width, e.transfer.fraction, 1e-6f)
        // the hand-over is how fast he was going at the end of the path
        val gravity = MechaThrows.GRAVITY_TAPS * lowerTap
        val expected = e.transfer.vy * lowerTap - gravity * 0.05 * p.durationMs / 1000.0
        assertClose(expected, speed(p, 0.9f, 1f, p::floorAt), 0.08)
    }

    @Test
    fun aFlingDownLeavesThroughTheBottomFullyAndSpeedsUp() {
        val e = MechaThrows.exit(500, 660, lowerScale, v(0.0, 19.0 * lowerTap), lower, MechaEdge.BOTTOM, lowerTap)!!
        val p = e.path
        assertTrue(MechaBody.frameTop(p.endFloor, lowerScale) - MechaBody.HEADROOM * lowerScale >= lower.bottom)
        assertEquals(p.endFloor, p.floorAt(1f))
        assertTrue(p.durationMs in 1..MechaThrows.EXIT_MS)
        val f = floors(p)
        assertTrue((0 until steps).all { f[it + 1] >= f[it] })
        assertTrue(e.transfer.vy > 19f)
    }

    @Test
    fun tooSlowOrTheWrongWayIsNoThrow() {
        assertNull(throwUp(500, 660, up(15.9)))
        assertNotNull(throwUp(500, 160, up(16.5)))
        assertNull(throwUp(500, 660, MechaVelocity.ZERO))
        // away from the facing edge, whichever it is
        assertNull(throwUp(500, 660, up(-30.0)))
        assertNull(MechaThrows.exit(500, 660, lowerScale, up(30.0), lower, MechaEdge.BOTTOM, lowerTap))
    }

    @Test
    fun aSidewaysFlingIsNoThrow() {
        assertNull(throwUp(200, 660, up(30.0, sideways = 3.0 * 30 * lowerTap)))
        assertNotNull(throwUp(200, 660, up(30.0, sideways = 1.1 * 30 * lowerTap)))
    }

    @Test
    fun anUpwardThrowThatCannotClearTheEdgeInTimeIsNoThrow() {
        // from the bottom of the screen gravity stops it short of the top
        assertNull(throwUp(500, lower.bottom, up(17.0)))
        // it would get there, but only after the window
        assertNull(throwUp(500, 660, up(19.0)))
        val quick = throwUp(500, 660, up(20.0))!!
        assertTrue(quick.path.durationMs <= MechaThrows.EXIT_MS)
    }

    @Test
    fun aThrowHeadingOutThroughASideIsNoThrow() {
        val nearRight = lower.right - MechaBody.ART_RIGHT * lowerScale - 10
        assertNull(throwUp(nearRight, 660, up(30.0, sideways = 1000.0)))
        assertNotNull(throwUp(nearRight, 660, up(30.0, sideways = -1000.0)))
    }

    @Test
    fun aDiagonalThrowHandsOverWhereItCrossesNotWhereItWasLetGo() {
        val e = throwUp(200, 660, up(30.0, sideways = 2000.0))!!
        val released = (200 + MechaBody.width(lowerScale) / 2 - lower.left).toFloat() / lower.width
        val crossed = (e.path.endX + MechaBody.width(lowerScale) / 2 - lower.left).toFloat() / lower.width
        assertEquals(crossed, e.transfer.fraction, 1e-6f)
        assertTrue(e.transfer.fraction > released + 0.2f)
        assertEquals(2000f / lowerTap, e.transfer.vx, 1e-3f)
        val x = xs(e.path)
        assertTrue((0 until steps).all { x[it + 1] >= x[it] })
    }

    @Test
    fun absurdSpeedIsClampedButARealFlingIsNot() {
        val wild = throwUp(500, 660, up(1e5, sideways = 1e4))!!
        assertTrue(hypot(wild.transfer.vx, wild.transfer.vy) <= MechaThrows.MAX_SPEED_TAPS)
        assertTrue(wild.path.durationMs >= 1)
        // 60 taps/s (2400dp/s) arrives at the edge only slowed by gravity
        assertTrue(throwUp(500, 660, up(60.0))!!.transfer.vy < -55f)
    }

    @Test
    fun invalidInputIsNoThrow() {
        val fling = up(30.0)
        assertNull(throwUp(500, 660, MechaVelocity(Float.NaN, -3000f)))
        assertNull(throwUp(500, 660, MechaVelocity(0f, Float.NEGATIVE_INFINITY)))
        assertNull(MechaThrows.exit(500, 660, lowerScale, fling, lower, MechaEdge.TOP, 0))
        assertNull(MechaThrows.exit(500, 660, 0, fling, lower, MechaEdge.TOP, lowerTap))
        assertNull(MechaThrows.exit(500, 660, lowerScale, fling, MechaRect(0, 0, 0, 0), MechaEdge.TOP, lowerTap))
        // already past the edge
        assertNull(throwUp(500, lower.top - 20, fling))
    }

    // ---- in ----

    @Test
    fun anUpwardThrowRisesInFromBelowAndComesDownOntoItsTarget() {
        val out = throwUp(200, 660, up(30.0, sideways = 2000.0))!!
        val t = out.transfer
        val p = MechaThrows.enter(t, at(900, 500, upperScale), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        // in at the handed-over fraction, fully under the edge
        assertEquals(upper.left + (t.fraction * upper.width).roundToInt(), p.xAt(0f) + MechaBody.width(upperScale) / 2)
        assertTrue(MechaBody.frameTop(p.floorAt(0f), upperScale) - MechaBody.HEADROOM * upperScale >= upper.bottom)
        assertEquals(900, p.xAt(1f))
        assertEquals(500, p.floorAt(1f))
        assertTrue(p.durationMs in MechaThrows.ENTER_MIN_MS..MechaThrows.ENTER_MAX_MS)
        val f = floors(p)
        val x = xs(p)
        // an arc: up past the target's floor, then down onto it
        assertTrue(f.any { it < 500 })
        assertTrue(p.floorAt(0.95f) < 500)
        assertTrue(f.all { MechaBody.frameTop(it, upperScale) >= upper.top && it <= f[0] })
        assertTrue(x.all { it + MechaBody.ART_LEFT * upperScale >= upper.left && it + MechaBody.ART_RIGHT * upperScale <= upper.right })
        // setting off as fast and the same way as he left, in this screen's taps
        assertClose(t.vy.toDouble() * upperTap, speed(p, 0f, 0.02f, p::floorAt), 0.08)
        assertClose(t.vx.toDouble() * upperTap, speed(p, 0f, 0.02f, p::xAt), 0.08)
    }

    @Test
    fun aFallingEntryDropsInFromAboveOntoItsTarget() {
        val t = MechaThrowTransfer(0.5f, -5f, 20f)
        val p = MechaThrows.enter(t, at(600, 900, lowerScale), lowerScale, lower, MechaEdge.TOP, lowerTap)
        assertTrue(p.floorAt(0f) + lowerScale <= lower.top)
        assertEquals(600, p.xAt(1f))
        assertEquals(900, p.floorAt(1f))
        assertTrue(p.durationMs in MechaThrows.ENTER_MIN_MS..MechaThrows.ENTER_MAX_MS)
        val f = floors(p)
        assertTrue((0 until steps).all { f[it + 1] >= f[it] })
        assertClose(20.0 * lowerTap, speed(p, 0f, 0.02f, p::floorAt), 0.08)
    }

    @Test
    fun aThrowBetweenVeryDifferentScreensKeepsItsSpeedInTaps() {
        // a small, dense screen with a negative origin, to the wide upper one
        val small = MechaRect(-300, 500, 340, 980)
        val smallTap = 140
        val out = MechaThrows.exit(-100, 900, 4, v(300.0, -25.0 * smallTap), small, MechaEdge.TOP, smallTap)!!
        val gravity = MechaThrows.GRAVITY_TAPS * smallTap
        val leaving = speed(out.path, 0.9f, 1f, out.path::floorAt) + gravity * 0.05 * out.path.durationMs / 1000.0
        assertClose(out.transfer.vy.toDouble() * smallTap, leaving, 0.08)
        val p = MechaThrows.enter(out.transfer, at(900, 500, upperScale), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        assertEquals(upper.left + (out.transfer.fraction * upper.width).roundToInt(), p.xAt(0f) + MechaBody.width(upperScale) / 2)
        // half the pixels per second on a screen whose taps are half as many pixels
        assertClose(out.transfer.vy.toDouble() * upperTap, speed(p, 0f, 0.02f, p::floorAt), 0.08)
        assertEquals(900, p.xAt(1f))
        assertEquals(500, p.floorAt(1f))
    }

    @Test
    fun aWildHandOverStaysOnScreenAndStillLandsExactly() {
        val target = at(900, 500, upperScale)
        // out at the very corner, flung outward as hard as allowed
        val p = MechaThrows.enter(MechaThrowTransfer(0f, -150f, -150f), target, upperScale, upper, MechaEdge.BOTTOM, upperTap)
        val x = xs(p)
        val f = floors(p)
        assertTrue(x.all { it >= p.xAt(0f).coerceAtMost(upper.left - MechaBody.ART_LEFT * upperScale) })
        assertTrue(f.all { MechaBody.frameTop(it, upperScale) >= upper.top && it <= f[0] })
        assertEquals(900, p.xAt(1f))
        assertEquals(500, p.floorAt(1f))
        assertTrue(p.durationMs in MechaThrows.ENTER_MIN_MS..MechaThrows.ENTER_MAX_MS)
        // straight up, very fast, onto a place just under the top: the head never leaves the screen
        val high = upper.top + (MechaBody.FRAME - 1) * upperScale + 10
        val q = MechaThrows.enter(MechaThrowTransfer(0.5f, 0f, -150f), at(1000, high, upperScale), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        assertTrue(floors(q).all { MechaBody.frameTop(it, upperScale) >= upper.top })
        assertEquals(high, q.floorAt(1f))
    }

    @Test
    fun anInvalidOrBackwardsHandOverStillComesInAndLands() {
        val target = at(600, 900, lowerScale)
        val p = MechaThrows.enter(MechaThrowTransfer(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN), target, lowerScale, lower, MechaEdge.TOP, lowerTap)
        assertEquals(lower.left + lower.width / 2, p.xAt(0f) + MechaBody.width(lowerScale) / 2)
        assertTrue(p.floorAt(0f) + lowerScale <= lower.top)
        assertEquals(600, p.xAt(1f))
        assertEquals(900, p.floorAt(1f))
        assertTrue(p.durationMs in MechaThrows.ENTER_MIN_MS..MechaThrows.ENTER_MAX_MS)
        // NaN or out-of-range progress samples an end, never something in between
        assertEquals(p.xAt(0f), p.xAt(Float.NaN))
        assertEquals(p.floorAt(0f), p.floorAt(-1f))
        assertEquals(900, p.floorAt(2f))
        // handed over heading the wrong way: still comes in through the edge
        val back = MechaThrows.enter(MechaThrowTransfer(0.5f, 0f, 30f), at(900, 500, upperScale), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        assertTrue(back.floorAt(0.1f) < back.floorAt(0f))
        assertEquals(500, back.floorAt(1f))
    }

    @Test
    fun theAimFollowsTheThrowAndStaysOnScreen() {
        val weak = MechaThrows.aim(MechaThrowTransfer(0.5f, 0f, -12f), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        val strong = MechaThrows.aim(MechaThrowTransfer(0.5f, 0f, -25f), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        val slanted = MechaThrows.aim(MechaThrowTransfer(0.5f, 6f, -25f), upperScale, upper, MechaEdge.BOTTOM, upperTap)
        assertTrue(strong.floor < weak.floor)
        assertTrue(slanted.x > strong.x)
        val falling = MechaThrows.aim(MechaThrowTransfer(0.5f, 0f, 80f), lowerScale, lower, MechaEdge.TOP, lowerTap)
        for (a in listOf(weak, strong, slanted)) {
            assertTrue(a.floor in upper.top + (MechaBody.FRAME - 1) * upperScale..upper.bottom)
            assertTrue(a.x + MechaBody.ART_LEFT * upperScale >= upper.left && a.x + MechaBody.ART_RIGHT * upperScale <= upper.right)
        }
        assertTrue(falling.floor in lower.top + (MechaBody.FRAME - 1) * lowerScale..lower.bottom)
    }
}
