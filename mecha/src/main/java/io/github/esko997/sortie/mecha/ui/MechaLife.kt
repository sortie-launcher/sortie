package io.github.esko997.sortie.mecha.ui

import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

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
 * Idle life while he stands still: a breath (the upper body rises a pixel), a blink now and then, an occasional glance to
 * one side (likelier toward the player's focus) and, now and then, his weight shifting a pixel to one side for a few
 * breaths. Beat lengths vary so it never settles into a fixed loop. Quiet (a reading surface) breathes slower, blinks and
 * glances seldom and never shifts. Asleep (dozing or resting): a slower breath on the sleeping frame. The legs never move.
 * Each step says how long it lasts and only steps that change the picture are produced, so idling costs about one redraw a
 * second or less.
 */
internal class MechaIdle(private val random: Random) {
    private var inhale = false
    private var look = 0
    private var lookBeats = 0
    private var sway = 0
    private var swayBeats = 0
    private var reopen = false

    fun reset() {
        inhale = false
        look = 0
        lookBeats = 0
        sway = 0
        swayBeats = 0
        reopen = false
    }

    /** The player settled on something to the [dir] side (-1 or 1): the next beats glance that way; the breath carries on. */
    fun glance(dir: Int) {
        if (dir == 0) return
        look = dir
        lookBeats = 2 + random.nextInt(2)
    }

    /** The next idle rig and how long it shows; [attention] (-1, 0, 1) is the side the player's focus is on. */
    fun next(asleep: Boolean, quiet: Boolean = false, attention: Int = 0): Pair<MechaRig, Long> {
        if (asleep) {
            inhale = !inhale
            look = 0
            lookBeats = 0
            sway = 0
            swayBeats = 0
            reopen = false
            return MechaRigs.ASLEEP.upper(0, if (inhale) -1 else 0) to SLEEP_BEAT_MS
        }
        val beat = if (quiet) QUIET_BEAT_MS + random.nextLong(-400, 401) else BEAT_MS + random.nextLong(-300, 301)
        if (reopen) {
            reopen = false
            return rig() to beat - BLINK_MS
        }
        inhale = !inhale
        if (lookBeats > 0) {
            lookBeats--
            if (lookBeats == 0) look = 0
        } else if (random.nextInt(if (quiet) 14 else 6) == 0) {
            look = if (attention != 0 && random.nextInt(3) != 0) attention else if (random.nextBoolean()) 1 else -1
            lookBeats = 2 + random.nextInt(2)
        }
        if (quiet) {
            sway = 0
            swayBeats = 0
        } else if (swayBeats > 0) {
            swayBeats--
            if (swayBeats == 0) sway = 0
        } else if (random.nextInt(10) == 0) {
            sway = if (random.nextBoolean()) 1 else -1
            swayBeats = 3 + random.nextInt(3)
        }
        if (random.nextInt(if (quiet) 7 else 4) == 0) {
            reopen = true
            return rig().copy(frame = MechaRigs.BLINK.frame) to BLINK_MS
        }
        return rig() to beat
    }

    private fun rig(): MechaRig = MechaRig(0, headX = look).upper(sway, if (inhale) -1 else 0)

    companion object {
        const val BEAT_MS = 1_400L
        const val QUIET_BEAT_MS = 2_600L
        const val BLINK_MS = 140L
        const val SLEEP_BEAT_MS = 2_200L
    }
}
