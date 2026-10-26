package io.github.esko997.sortie.mecha.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** What a beat of travel is; only a [FLIGHT] beat has him off the ground. */
internal enum class MechaPhase { LOOK, WALK, SETTLE, CROUCH, PUSH, FLIGHT, LAND, RECOVER }

/**
 * One beat of travel: [rig] drawn with the box left at [x] and the feet on [floor], held for [ms]. Positions move in whole
 * sprite pixels from where the move began and parts only shift by whole sprite pixels, so the art stays crisp all the way.
 */
internal data class MechaBeat(val phase: MechaPhase, val rig: MechaRig, val x: Int, val floor: Int, val ms: Long) {
    val airborne: Boolean get() = phase == MechaPhase.FLIGHT
}

/**
 * Choreography for getting about, as plain beat lists the director plays (and checks for interruptions between). The art
 * faces the player, so weight and intent come from the layered rig: the head turns to where he is going first, the upper body
 * leans into a walk and dips when he stops, a jump crouches, pushes off, flies with arms up and legs tucked, reaches down and
 * lands with the weight compressing and recovering. Small random variations in tempo; the silhouette stays the authored one.
 */
internal object MechaMotion {
    /** Frame pixels per walk step, one step per walk frame, so the feet and the floor agree. */
    const val STRIDE = 3
    const val WALK_STEP_MS = 120L
    const val FLIGHT_STEP_MS = 60L

    /** A drop of more than this many frame pixels lands harder. */
    private const val HARD_FALL = 24

    /** A jump up by more than this many frame pixels crouches deeper. */
    private const val HIGH_JUMP = 16

    /** Box-left offset snapped to whole sprite pixels. */
    private fun snap(v: Float, scale: Int): Int = (v / scale).roundToInt() * scale

    /** Display-frame travel between beats; exact endpoints even when a layout edge is off the sprite-pixel lattice. */
    fun position(from: Int, to: Int, scale: Int, progress: Float): Int = when {
        progress <= 0f -> from
        progress >= 1f -> to
        else -> (from + snap((to - from) * progress, scale)).coerceIn(min(from, to), max(from, to))
    }

    /**
     * Along a floor from [fromX] to [toX]: a look the way he goes when [start] (from standing), then as many steps as the
     * distance takes at about [STRIDE] frame pixels each (the last one lands exactly), the first and last a little slower;
     * when [stop], the weight settles onto both feet and he holds a look the way he came to a stop. Legs never shift: the
     * feet stay on the floor and only the authored walk frames move them.
     */
    fun walk(fromX: Int, toX: Int, floor: Int, scale: Int, random: Random, start: Boolean = true, stop: Boolean = true): List<MechaBeat> {
        val dx = toX - fromX
        if (dx == 0) return emptyList()
        val dir = dx.sign
        val out = ArrayList<MechaBeat>()
        if (start) out += MechaBeat(MechaPhase.LOOK, MechaRig(0, headX = dir), fromX, floor, 160L + random.nextLong(100))
        val n = max(1, (abs(dx).toFloat() / (STRIDE * scale)).roundToInt())
        val tempo = WALK_STEP_MS + random.nextLong(-12, 13)
        // which foot leads
        val lead = if (random.nextBoolean()) 0 else 2
        for (k in 1..n) {
            val x = if (k == n) toX else fromX + snap(dx * k.toFloat() / n, scale)
            val i = k - 1 + lead
            // leaning into the walk; on a passing frame the upper body rides a pixel higher over the straight leg
            val rig = MechaRig(MechaPose.WALK.frameAt(i)).upper(dir, MechaPose.WALK.bobAt(i))
            val ms = if ((k == 1 && start) || (k == n && stop)) tempo + 30 else tempo
            out += MechaBeat(MechaPhase.WALK, rig, x, floor, ms)
        }
        if (stop) {
            out += MechaBeat(MechaPhase.SETTLE, MechaRig(0, headX = dir).upper(0, 1), toX, floor, 110)
            out += MechaBeat(MechaPhase.SETTLE, MechaRig(0, headX = dir), toX, floor, 220L + random.nextLong(120))
        }
        return out
    }

    /**
     * A jump from ([fromX], [fromFloor]) to ([toX], [toFloor]) through [flightX]/[flightFloor] (box left and floor at progress
     * 0..1 in whole pixels: the geometry that checked the path owns the arc). He looks where he is going, up or down too
     * ([start]: from standing; else a quicker glance mid-route), crouches (deeper and longer for a higher jump), pushes off
     * leaning in, flies arms up with the legs tucked, reaches down with the legs past the top, and lands ([land]).
     */
    fun hop(
        fromX: Int,
        fromFloor: Int,
        toX: Int,
        toFloor: Int,
        scale: Int,
        random: Random,
        flightX: (Float) -> Int,
        flightFloor: (Float) -> Int,
        start: Boolean = true
    ): List<MechaBeat> {
        val dir = (toX - fromX).sign
        val up = (fromFloor - toFloor) / scale
        val across = abs(toX - fromX) / scale
        val tilt = when {
            up > 4 -> -1
            up < -4 -> 1
            else -> 0
        }
        val out = ArrayList<MechaBeat>()
        val looking = MechaRig(0, headX = dir, headY = tilt)
        out += MechaBeat(MechaPhase.LOOK, looking, fromX, fromFloor, if (start) 180L + random.nextLong(100) else 90L)
        out += MechaBeat(MechaPhase.CROUCH, looking.upper(0, 1), fromX, fromFloor, 70)
        val depth = if (up > HIGH_JUMP) 3 else 2
        val gather = 120L + min(100, max(0, up) * 4) + random.nextLong(30)
        out += MechaBeat(MechaPhase.CROUCH, looking.copy(armsY = 1).upper(0, depth), fromX, fromFloor, gather)
        out += MechaBeat(MechaPhase.PUSH, MechaRig(0, headX = dir).upper(dir, -1), fromX, fromFloor, 50)
        val dist = sqrt((across * across + up * up).toFloat())
        val n = (4 + dist / 14).roundToInt().coerceIn(4, 9)
        for (k in 1..n) {
            val t = k.toFloat() / n
            val rig = if (2 * k < n) {
                MechaRig(0, headX = dir, armsY = -2, legsY = -2)
            } else {
                MechaRig(0, headX = dir, armsY = -1).upper(0, -1)
            }
            out += MechaBeat(MechaPhase.FLIGHT, rig, flightX(t), flightFloor(t), FLIGHT_STEP_MS)
        }
        out += land(toX, toFloor, dir, fell = -up, random = random)
        return out
    }

    /**
     * Touching down at ([x], [floor]) after falling [fell] frame pixels (negative: rising): the weight compresses the upper
     * body onto the legs (more after a long fall), comes back up a pixel at a time, and he holds a look toward [dir].
     */
    fun land(x: Int, floor: Int, dir: Int, fell: Int, random: Random): List<MechaBeat> {
        val depth = if (fell > HARD_FALL) 3 else 2
        return listOf(
            MechaBeat(MechaPhase.LAND, MechaRig(0, headX = dir, armsY = 1).upper(0, depth), x, floor, 90L + depth * 15),
            MechaBeat(MechaPhase.RECOVER, MechaRig(0, headX = dir).upper(0, 1), x, floor, 100),
            MechaBeat(MechaPhase.RECOVER, MechaRig(0, headX = dir), x, floor, 200L + random.nextLong(120))
        )
    }
}
