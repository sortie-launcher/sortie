package io.github.esko997.sortie.mecha.ui

import androidx.lifecycle.Lifecycle
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Whether a window is in front: [lifecycle] at least RESUMED and its display [lit]. Both come from their own callbacks (the
 * lifecycle's state flow, the display listener), never from composition or the frame clock: a stopped or dark window
 * composes and draws nothing, so a gate that waited for a frame would never close.
 */
internal fun mechaShown(lifecycle: Lifecycle, lit: Flow<Boolean>): Flow<Boolean> =
    combine(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.RESUMED) }, lit) { resumed, on -> resumed && on }
        .distinctUntilChanged()

/**
 * Runs [block] each time [shown] turns true and cancels it the moment it turns false, so no timer or loop of his outlives
 * his window being in front.
 */
internal suspend fun whileShown(shown: Flow<Boolean>, block: suspend () -> Unit) {
    shown.distinctUntilChanged().collectLatest { if (it) block() }
}

/** The rigs his behaviour is made of: authored frames (0 idle, 1 blink, 6 asleep, 7 arms up) plus per-part offsets. */
internal object MechaRigs {
    val STAND = MechaRig(0)
    val BLINK = MechaRig(1)
    val ASLEEP = MechaRig(6)

    /** Hanging from the hands: arms up, the body and legs stretched down, the legs further. */
    val DANGLE = MechaRig(7, legsY = 2, bodyY = 1)

    /** The dangle's swing: the legs a pixel to one side, then the other. */
    fun swing(step: Int): MechaRig = DANGLE.copy(legsX = intArrayOf(0, 1, 0, -1)[step.mod(4)])

    /** A pet: eyes shut happily, bouncing (head up, then the whole upper body). */
    fun pet(step: Int): MechaRig = if (step % 2 == 0) MechaRig(1, headY = -1) else MechaRig(1).upper(0, -1)

    /** Seated on an edge: legs tucked up a little, kicking now and then. */
    fun sit(step: Int, blink: Boolean): MechaRig = MechaRig(if (blink) 1 else 0, legsY = if (step % 2 == 0) -3 else -4, legsX = if (step % 4 == 1) 1 else 0)

    /** A poke reaching [toward] (-1 start, 1 end) with the arms, or back. */
    fun poke(toward: Int, out: Boolean): MechaRig = if (out) MechaRig(0, armsX = 2 * toward, headX = toward) else STAND

    /** Under a lifted element: arms up, the upper body [up] pixels raised (negative: squatting), wobbling with [step]. */
    fun strain(up: Int, step: Int): MechaRig = MechaRig(7, legsX = if (step % 2 == 0) 0 else 1, headX = if (step % 4 < 2) 0 else -1).upper(0, -up)
}

/**
 * Always-on idle life while he stands still: a slow breath (the upper body rises a pixel), a blink now and then and an
 * occasional glance to either side. Asleep (dozing or resting): a slower breath on the sleeping frame. Each step says how
 * long it lasts; only steps that change the picture are produced, so idling costs about one redraw a second.
 */
internal class MechaIdle(private val random: Random) {
    private var inhale = false
    private var look = 0
    private var lookBeats = 0
    private var reopen = false

    fun reset() {
        inhale = false
        look = 0
        lookBeats = 0
        reopen = false
    }

    /** The next idle rig and how long it shows. */
    fun next(asleep: Boolean): Pair<MechaRig, Long> {
        if (asleep) {
            inhale = !inhale
            look = 0
            reopen = false
            return MechaRigs.ASLEEP.upper(0, if (inhale) -1 else 0) to SLEEP_BEAT_MS
        }
        val base = MechaRig(0, headX = look).upper(0, if (inhale) -1 else 0)
        if (reopen) {
            reopen = false
            return base to BEAT_MS - BLINK_MS
        }
        inhale = !inhale
        if (lookBeats > 0) {
            lookBeats--
            if (lookBeats == 0) look = 0
        } else if (random.nextInt(6) == 0) {
            look = if (random.nextBoolean()) 1 else -1
            lookBeats = 2 + random.nextInt(2)
        }
        val breath = MechaRig(0, headX = look).upper(0, if (inhale) -1 else 0)
        if (random.nextInt(4) == 0) {
            reopen = true
            return breath.copy(frame = MechaRigs.BLINK.frame) to BLINK_MS
        }
        return breath to BEAT_MS
    }

    companion object {
        const val BEAT_MS = 1_400L
        const val BLINK_MS = 140L
        const val SLEEP_BEAT_MS = 2_200L
    }
}
