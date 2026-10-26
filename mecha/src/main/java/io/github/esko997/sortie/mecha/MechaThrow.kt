package io.github.esko997.sortie.mecha

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * A throw handed to the other display: where along the facing edge he left ([fraction] of the width, under his art's centre)
 * and how fast, in minimum-tap widths per second ([vx] positive right, [vy] positive down), so a screen of any size or
 * density carries on with the same throw.
 */
internal data class MechaThrowTransfer(val fraction: Float, val vx: Float, val vy: Float)

/** A throw out of this display: the [path] out through the facing edge, and what to hand over ([transfer]). */
internal data class MechaThrowExit(val path: MechaFlight, val transfer: MechaThrowTransfer)

/** Where an incoming throw would come down left to itself: pick the place nearest this ([MechaThrows.aim]). */
internal data class MechaAim(val x: Int, val floor: Int)

/**
 * A planned flight over [durationMs]: box left and feet floor as cubics in progress 0..1, from ([startX], [startFloor]) to
 * ([endX], [endFloor]) exactly. Progress at or before 0 (or NaN) is the start, at or after 1 the end. In between each value
 * is rounded to a whole pixel and held to the bounds the plan was checked against. Sampling allocates nothing.
 */
internal class MechaFlight internal constructor(
    val durationMs: Long,
    val startX: Int,
    val startFloor: Int,
    val endX: Int,
    val endFloor: Int,
    private val x3: Double,
    private val x2: Double,
    private val x1: Double,
    private val f3: Double,
    private val f2: Double,
    private val f1: Double,
    private val minX: Int = Int.MIN_VALUE,
    private val maxX: Int = Int.MAX_VALUE,
    private val minFloor: Int = Int.MIN_VALUE,
    private val maxFloor: Int = Int.MAX_VALUE
) {
    fun xAt(progress: Float): Int = when {
        !(progress > 0f) -> startX
        progress >= 1f -> endX
        else -> cubic(progress.toDouble(), startX, x1, x2, x3).coerceIn(minX, maxX)
    }

    fun floorAt(progress: Float): Int = when {
        !(progress > 0f) -> startFloor
        progress >= 1f -> endFloor
        else -> cubic(progress.toDouble(), startFloor, f1, f2, f3).coerceIn(minFloor, maxFloor)
    }

    private fun cubic(p: Double, d: Int, c: Double, b: Double, a: Double): Int = (((a * p + b) * p + c) * p + d).roundToInt()
}

/**
 * Pure path planning for throwing a carried mech to the other display of a stacked dual-screen handheld. Window pixels;
 * x is the box left, floor the feet line ([MechaBody]). Speeds and gravity scale with the minimum tap size, so a throw
 * feels the same on screens of different density.
 *
 * Out ([exit]): ballistic under [GRAVITY_TAPS], and only for a deliberate fling at the edge that faces the other display:
 * at least [MIN_SPEED_TAPS] toward it, within [MAX_SLANT] of straight at it, reaching fully past it within [EXIT_MS] and
 * not leaving through a side on the way. Anything else is a plain drop.
 *
 * In ([enter]): not free flight. He comes in from fully past the facing edge with the handed-over velocity and is guided
 * onto a target the caller has validated: a cubic matched to that velocity at the start, coming down onto the target at the
 * end (a parabola when that fits as it is), shortened or softened so it stays on screen. The landing itself is the caller's.
 */
internal object MechaThrows {
    /** Least speed toward the facing edge that counts as a throw: 16 taps/s, 640dp/s. */
    const val MIN_SPEED_TAPS = 16f

    /** Speeds beyond this (6000dp/s, past any real fling of a carried mech) are scaled down to it, keeping the direction. */
    const val MAX_SPEED_TAPS = 150f

    /** Downward acceleration, taps/s² (1200dp/s²): enough to bend a throw, not so much that a fling falls short. */
    const val GRAVITY_TAPS = 30f

    /** Most sideways speed per unit of speed toward the edge (about 50 degrees off straight). */
    const val MAX_SLANT = 1.2f

    /** Longest an outgoing throw may take to be fully past the edge. */
    const val EXIT_MS = 450L

    /** Least speed an incoming throw comes in with, so even one that barely made it out arrives rather than drifts. */
    const val ENTRY_MIN_TAPS = 8f

    /** Least downward speed coming onto the target: he lands, never arrives rising. */
    const val LAND_TAPS = 8f

    /** Bounds on an incoming flight. */
    const val ENTER_MIN_MS = 220L
    const val ENTER_MAX_MS = 650L

    /** How far into a falling entry [aim] looks. */
    const val AIM_FALL_MS = 300L

    /** Sprite pixels past the frame counted as off screen: a rig offset may push legs or head that far. */
    private const val MARGIN = MechaBody.HEADROOM

    private const val FIT_TRIES = 14
    private const val SHORTEN = 0.85
    private const val SOFTEN = 0.5
    private const val SOFTEST = 0.1

    /**
     * The throw out of [stage] through [toward] for a mech at ([x], [floor]) let go at [velocity] (pixels per second), or
     * null when it is no throw that way: too slow or wrong way, too sideways, unable to clear the edge within [EXIT_MS]
     * (gravity wins an upward throw), heading out through a side, already past the edge, or invalid input.
     */
    fun exit(x: Int, floor: Int, scale: Int, velocity: MechaVelocity, stage: MechaRect, toward: MechaEdge, minTap: Int): MechaThrowExit? {
        if (scale <= 0 || minTap <= 0 || stage.isEmpty) return null
        val tap = minTap.toDouble()
        var vx = velocity.x.toDouble()
        var vy = velocity.y.toDouble()
        if (!vx.isFinite() || !vy.isFinite()) return null
        val speed = hypot(vx, vy)
        val cap = MAX_SPEED_TAPS * tap
        if (speed > cap) {
            vx *= cap / speed
            vy *= cap / speed
        }
        val sign = if (toward == MechaEdge.TOP) -1.0 else 1.0
        val out = vy * sign
        if (out < MIN_SPEED_TAPS * tap || abs(vx) > MAX_SLANT * out) return null
        val endFloor = outside(toward, stage, scale)
        val distance = (endFloor - floor) * sign
        if (distance <= 0.0) return null
        val g = GRAVITY_TAPS * tap
        // distance = out t - sign g t² / 2: gravity slows a throw up and speeds one down
        val t = if (toward == MechaEdge.TOP) {
            val disc = out * out - 2 * g * distance
            if (disc < 0) return null
            (out - sqrt(disc)) / g
        } else {
            (sqrt(out * out + 2 * g * distance) - out) / g
        }
        if (!(t > 0.0) || t * 1000 > EXIT_MS) return null
        val endX = (x + vx * t).roundToInt()
        val artLeft = endX + MechaBody.ART_LEFT * scale
        val artRight = endX + MechaBody.ART_RIGHT * scale
        if ((vx < 0 && artLeft < stage.left) || (vx > 0 && artRight > stage.right)) return null
        val centre = endX + MechaBody.width(scale) / 2
        if (centre < stage.left || centre >= stage.right) return null
        val path = MechaFlight(
            durationMs = max(1L, (t * 1000).roundToLong()),
            startX = x,
            startFloor = floor,
            endX = endX,
            endFloor = endFloor,
            x3 = 0.0,
            x2 = 0.0,
            x1 = vx * t,
            f3 = 0.0,
            f2 = g * t * t / 2,
            f1 = vy * t
        )
        val fraction = ((centre - stage.left).toFloat() / stage.width).coerceIn(0f, 1f)
        return MechaThrowExit(path, MechaThrowTransfer(fraction, (vx / tap).toFloat(), ((vy + g * t) / tap).toFloat()))
    }

    /**
     * The flight in through [toward] of [stage] onto [target] (already validated by the caller): from fully past the edge at
     * the handed-over fraction, setting off at the handed-over velocity (at least [ENTRY_MIN_TAPS] inward, at most
     * [MAX_SPEED_TAPS]), over [ENTER_MIN_MS] to [ENTER_MAX_MS], and coming down onto [target] exactly. Kept inside the
     * stage's sides, not rising past the top or sinking back past where he came in, unless the target itself is there.
     */
    fun enter(transfer: MechaThrowTransfer, target: MechaPlacement, scale: Int, stage: MechaRect, toward: MechaEdge, minTap: Int): MechaFlight {
        val s = max(1, scale)
        val tap = max(1, minTap).toDouble()
        val x0 = entryX(transfer, stage, s)
        val f0 = outside(toward, stage, s)
        val vx = entrySide(transfer, tap)
        val vy = entryInward(transfer, toward, tap)
        val cx = (target.x - x0).toDouble()
        val cy = (target.floor - f0).toDouble()
        val minX = min(min(x0, target.x), stage.left - MechaBody.ART_LEFT * s)
        val maxX = max(max(x0, target.x), stage.right - MechaBody.ART_RIGHT * s)
        val minFloor = min(min(f0, target.floor), stage.top + (MechaBody.FRAME - 1) * s)
        val maxFloor = max(f0, target.floor)
        // start from the time free flight would take to come down onto the target's floor
        val g = GRAVITY_TAPS * tap
        val disc = vy * vy + 2 * g * cy
        val free = if (disc >= 0) (sqrt(disc) - vy) / g else ENTER_MAX_MS / 1000.0
        val shortest = ENTER_MIN_MS / 1000.0
        var t = free.coerceIn(shortest, ENTER_MAX_MS / 1000.0)
        var kx = 1.0
        var ky = 1.0
        var mx0 = 0.0
        var my0 = 0.0
        var mx1 = 0.0
        var my1 = 0.0
        for (i in 0 until FIT_TRIES) {
            mx0 = vx * t * kx
            my0 = vy * t * ky
            // the same acceleration throughout, a parabola; but always coming down onto the target at the end
            mx1 = 2 * cx - mx0
            my1 = max(2 * cy - my0, LAND_TAPS * tap * t)
            val fitsX = stays(mx0 + mx1 - 2 * cx, 3 * cx - 2 * mx0 - mx1, mx0, (minX - x0).toDouble(), (maxX - x0).toDouble())
            val fitsY = stays(my0 + my1 - 2 * cy, 3 * cy - 2 * my0 - my1, my0, (minFloor - f0).toDouble(), (maxFloor - f0).toDouble())
            if ((fitsX && fitsY) || i == FIT_TRIES - 1) break
            // a quicker flight overshoots less and keeps his speed; once as quick as allowed, soften the start on the
            // axis that still overshoots, down to none (then only the per-sample bounds hold him)
            if (t > shortest) {
                t = max(shortest, t * SHORTEN)
            } else {
                if (!fitsX) kx = if (kx > SOFTEST) kx * SOFTEN else 0.0
                if (!fitsY) ky = if (ky > SOFTEST) ky * SOFTEN else 0.0
            }
        }
        return MechaFlight(
            durationMs = (t * 1000).roundToLong(),
            startX = x0,
            startFloor = f0,
            endX = target.x,
            endFloor = target.floor,
            x3 = mx0 + mx1 - 2 * cx,
            x2 = 3 * cx - 2 * mx0 - mx1,
            x1 = mx0,
            f3 = my0 + my1 - 2 * cy,
            f2 = 3 * cy - 2 * my0 - my1,
            f1 = my0,
            minX = minX,
            maxX = maxX,
            minFloor = minFloor,
            maxFloor = maxFloor
        )
    }

    /**
     * Where an incoming [transfer] would come down if left to itself, held to [stage]: the top of a rising entry's arc (or
     * where it is after [ENTER_MAX_MS]), or a falling entry after [AIM_FALL_MS]. The caller picks the place nearest it.
     */
    fun aim(transfer: MechaThrowTransfer, scale: Int, stage: MechaRect, toward: MechaEdge, minTap: Int): MechaAim {
        val s = max(1, scale)
        val tap = max(1, minTap).toDouble()
        val vx = entrySide(transfer, tap)
        val vy = entryInward(transfer, toward, tap)
        val g = GRAVITY_TAPS * tap
        val t = if (vy < 0) min(-vy / g, ENTER_MAX_MS / 1000.0) else AIM_FALL_MS / 1000.0
        val x = entryX(transfer, stage, s) + vx * t
        val floor = outside(toward, stage, s) + vy * t + g * t * t / 2
        val lo = stage.left - MechaBody.ART_LEFT * s
        val hi = stage.right - MechaBody.ART_RIGHT * s
        val ax = if (hi < lo) (lo + hi) / 2 else x.roundToInt().coerceIn(lo, hi)
        val top = stage.top + (MechaBody.FRAME - 1) * s
        val af = if (stage.bottom < top) stage.bottom else floor.roundToInt().coerceIn(top, stage.bottom)
        return MechaAim(ax, af)
    }

    /** The floor at which the whole frame, with [MARGIN], is past [edge]. */
    private fun outside(edge: MechaEdge, stage: MechaRect, scale: Int): Int = when (edge) {
        MechaEdge.TOP -> stage.top - (1 + MARGIN) * scale
        MechaEdge.BOTTOM -> stage.bottom + (MechaBody.FRAME - 1 + MARGIN) * scale
    }

    /** Box left that puts the art's centre at the handed-over fraction of the width. */
    private fun entryX(transfer: MechaThrowTransfer, stage: MechaRect, scale: Int): Int {
        val f = transfer.fraction.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0.5f }
        return stage.left + (f * stage.width).roundToInt() - MechaBody.width(scale) / 2
    }

    /** Handed-over sideways speed in pixels per second. */
    private fun entrySide(transfer: MechaThrowTransfer, tap: Double): Double {
        val v = transfer.vx.toDouble()
        return if (v.isFinite()) (v * tap).coerceIn(-MAX_SPEED_TAPS * tap, MAX_SPEED_TAPS * tap) else 0.0
    }

    /** Handed-over vertical speed in pixels per second (positive down), always into the stage through [toward]. */
    private fun entryInward(transfer: MechaThrowTransfer, toward: MechaEdge, tap: Double): Double {
        val inward = if (toward == MechaEdge.TOP) 1.0 else -1.0
        val v = transfer.vy.toDouble().let { if (it.isFinite()) it * inward else 0.0 }
        return inward * (v * tap).coerceIn(ENTRY_MIN_TAPS * tap, MAX_SPEED_TAPS * tap)
    }

    /** Whether a p³ + b p² + c p stays within [lo] to [hi] for p in 0..1 (its ends are known to). */
    private fun stays(a: Double, b: Double, c: Double, lo: Double, hi: Double): Boolean {
        fun ok(p: Double): Boolean = p <= 0.0 || p >= 1.0 || ((a * p + b) * p + c) * p in lo..hi
        val qa = 3 * a
        val qb = 2 * b
        if (abs(qa) < 1e-9) return qb == 0.0 || ok(-c / qb)
        val disc = qb * qb - 4 * qa * c
        if (disc < 0) return true
        val r = sqrt(disc)
        return ok((-qb + r) / (2 * qa)) && ok((-qb - r) / (2 * qa))
    }
}
