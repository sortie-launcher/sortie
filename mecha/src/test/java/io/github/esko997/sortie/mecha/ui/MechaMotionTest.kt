package io.github.esko997.sortie.mecha.ui

import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaSpotKind
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Travel choreography: weight and intent from the layered rig, whole sprite pixels, and feet that agree with the floor. */
class MechaMotionTest {
    private val s = 3

    @Test
    fun aWalkLooksFirstMatchesItsDistanceAndSettles() {
        for (seed in 0 until 20) {
            val from = 100
            val to = from + (40 + seed * 7) * s
            val beats = MechaMotion.walk(from, to, 500, s, Random(seed))
            // a look the way he goes before a foot moves
            assertEquals(MechaPhase.LOOK, beats.first().phase)
            assertEquals(from, beats.first().x)
            assertEquals(1, beats.first().rig.dx(MechaSlot.HEAD))
            val steps = beats.filter { it.phase == MechaPhase.WALK }
            var x = from
            for (b in steps) {
                val d = b.x - x
                // forward every step, about a stride: never sliding a long way on one frame, never shuffling in place
                assertTrue("step $d", d in (MechaMotion.STRIDE - 1) * s..(MechaMotion.STRIDE + 1) * s)
                // whole sprite pixels from where he started, the last step landing exactly
                if (b !== steps.last()) assertEquals(0, (b.x - from) % s)
                assertTrue(b.rig.frame in 2..5)
                // feet stay on the floor: only the authored walk frames move the legs; the upper body leans his way
                assertEquals(0, b.rig.dx(MechaSlot.LEGS))
                assertEquals(0, b.rig.dy(MechaSlot.LEGS))
                assertEquals(1, b.rig.dx(MechaSlot.BODY))
                assertEquals(500, b.floor)
                x = b.x
            }
            assertEquals(to, steps.last().x)
            // contact and passing frames alternate
            steps.zipWithNext().forEach { (a, b) -> assertTrue(a.rig.frame != b.rig.frame) }
            // only steps move him; stopping, the weight comes down and he holds where he arrived
            assertTrue(beats.filter { it.phase != MechaPhase.WALK }.all { it.x == from || it.x == to })
            assertEquals(MechaPhase.SETTLE, beats.last().phase)
            assertTrue(beats.any { it.phase == MechaPhase.SETTLE && it.rig.dy(MechaSlot.BODY) > 0 })
        }
    }

    @Test
    fun aWalkHeadsTheWayItGoesAndAMidRouteWalkNeitherLooksNorStops() {
        val left = MechaMotion.walk(400, 100, 300, s, Random(1), start = false, stop = false)
        assertTrue(left.all { it.phase == MechaPhase.WALK })
        assertTrue(left.all { it.rig.dx(MechaSlot.HEAD) == -1 })
        assertEquals(100, left.last().x)
        assertTrue(MechaMotion.walk(100, 100, 300, s, Random(1)).isEmpty())
    }

    @Test
    fun aHopAnticipatesFliesTheCheckedArcAndLandsWithWeight() {
        val sampled = ArrayList<Float>()
        val fromX = 100
        val fromFloor = 600
        val toX = 100 + 60 * s
        val toFloor = 600 - 30 * s
        val from = MechaPlacement("from", MechaSpotKind.LEDGE, fromX, fromFloor, Int.MAX_VALUE, MechaBody.art(fromX, fromFloor, s))
        val to = MechaPlacement("to", MechaSpotKind.LEDGE, toX, toFloor, Int.MAX_VALUE, MechaBody.art(toX, toFloor, s))
        val arcX = { t: Float ->
            sampled += t
            MechaRoam.hopX(from, to, s, t)
        }
        val arcFloor = { t: Float -> MechaRoam.hopFloor(from, to, s, t) }
        val beats = MechaMotion.hop(fromX, fromFloor, toX, toFloor, s, Random(3), arcX, arcFloor)
        val phases = beats.map { it.phase }
        // look, crouch, push off, fly, land, recover: in that order, each present
        val order = listOf(MechaPhase.LOOK, MechaPhase.CROUCH, MechaPhase.PUSH, MechaPhase.FLIGHT, MechaPhase.LAND, MechaPhase.RECOVER)
        assertEquals(order, phases.distinct())
        assertEquals(phases.sortedBy { order.indexOf(it) }, phases)
        // looking where he goes: right and up
        val look = beats.first()
        assertEquals(1, look.rig.dx(MechaSlot.HEAD))
        assertEquals(-1, look.rig.dy(MechaSlot.HEAD))
        // anticipation on the spot: the upper body sinks onto the legs, deepest for this high jump, feet planted
        val ground = beats.filter { it.phase in setOf(MechaPhase.LOOK, MechaPhase.CROUCH, MechaPhase.PUSH) }
        assertTrue(ground.all { it.x == fromX && it.floor == fromFloor && it.rig.dy(MechaSlot.LEGS) == 0 })
        assertEquals(3, beats.filter { it.phase == MechaPhase.CROUCH }.maxOf { it.rig.dy(MechaSlot.BODY) })
        // the flight follows exactly the arc the geometry checked, forward in time, on whole sprite pixels
        val flight = beats.filter { it.phase == MechaPhase.FLIGHT }
        assertEquals(sampled, sampled.sorted())
        assertTrue(sampled.all { it > 0f && it <= 1f })
        assertTrue(flight.zipWithNext().all { (a, b) -> b.x >= a.x })
        assertTrue(flight.any { it.floor < toFloor })
        assertEquals(toX, flight.last().x)
        assertEquals(toFloor, flight.last().floor)
        flight.forEach { b ->
            assertEquals(0, (b.x - fromX) % s)
            assertTrue(b.airborne)
            // rig shifts stay within the 2 frame pixel margin the path is checked with
            for (slot in MechaSlot.entries) assertTrue(b.rig.dy(slot) >= -2 && abs(b.rig.dx(slot)) <= 2)
        }
        // arms up and legs tucked on the way up, reaching down on the way down
        assertTrue(flight.first().rig.dy(MechaSlot.LEGS) < 0)
        assertTrue(flight.last().rig.dy(MechaSlot.LEGS) == 0)
        // touchdown exactly on the target, compressing, then recovering a pixel at a time to standing
        val landing = beats.dropWhile { it.phase != MechaPhase.LAND }
        assertTrue(landing.all { it.x == toX && it.floor == toFloor && !it.airborne })
        assertEquals(listOf(2, 1, 0), landing.map { it.rig.dy(MechaSlot.BODY) })
    }

    @Test
    fun aLongFallLandsHarderAndAJumpDownLooksDown() {
        val beats = MechaMotion.hop(100, 300, 100, 300 + 40 * s, s, Random(5), { 100 }, { t -> 300 + (40 * s * t).roundToInt() / s * s })
        assertEquals(1, beats.first().rig.dy(MechaSlot.HEAD))
        assertEquals(0, beats.first().rig.dx(MechaSlot.HEAD))
        assertEquals(3, beats.first { it.phase == MechaPhase.LAND }.rig.dy(MechaSlot.BODY))
        assertEquals(2, MechaMotion.land(0, 0, 0, fell = 4, random = Random(0)).first().rig.dy(MechaSlot.BODY))
    }

    @Test
    fun frameTravelIsMonotonicAndReachesOffGridEndpointsInBothDirections() {
        for ((from, to) in listOf(101 to 300, 300 to 101, 0 to 2, 2 to 0, 100 to 300, 300 to 100)) {
            val positions = (0..100).map { MechaMotion.position(from, to, 3, it / 100f) }
            assertEquals(from, positions.first())
            assertEquals(to, positions.last())
            assertTrue(positions.all { it in minOf(from, to)..maxOf(from, to) })
            assertTrue(positions.zipWithNext().all { (a, b) -> if (to > from) b >= a else b <= a })
        }
    }

    @Test
    fun idleVariesItsBeatsAndStaysSparseWhenQuiet() {
        val busy = MechaIdle(Random(11))
        val lengths = HashSet<Long>()
        var glances = 0
        var awake = 0L
        repeat(400) {
            val (rig, ms) = busy.next(asleep = false)
            lengths += ms
            if (rig.dx(MechaSlot.HEAD) != rig.dx(MechaSlot.BODY)) glances++
            awake += ms
        }
        // not a constant loop
        assertTrue(lengths.size > 10)
        val quiet = MechaIdle(Random(11))
        var quietGlances = 0
        var quietTotal = 0L
        var last: MechaRig? = null
        repeat(400) {
            val (rig, ms) = quiet.next(asleep = false, quiet = true)
            assertTrue(rig != last)
            if (rig.dx(MechaSlot.HEAD) != 0) quietGlances++
            // no weight shifting while the player reads
            assertEquals(0, rig.dx(MechaSlot.BODY))
            assertEquals(0, rig.dy(MechaSlot.LEGS))
            quietTotal += ms
            last = rig
        }
        assertTrue(quietTotal > awake)
        assertTrue(quietGlances < glances)
    }

    @Test
    fun aGlanceTurnsTheHeadWithoutRestartingTheBreath() {
        val idle = MechaIdle(Random(2))
        var (rig, _) = idle.next(asleep = false)
        while (rig.frame != 0) rig = idle.next(asleep = false).first
        val breath = rig.dy(MechaSlot.HEAD) - rig.dy(MechaSlot.LEGS)
        idle.glance(-1)
        val next = idle.next(asleep = false).first
        assertEquals(-1, next.dx(MechaSlot.HEAD) - next.dx(MechaSlot.BODY))
        // the breath carried on: the next beat is the other half of it
        assertTrue(next.dy(MechaSlot.BODY) != breath || next.frame == MechaRigs.BLINK.frame)
    }
}
