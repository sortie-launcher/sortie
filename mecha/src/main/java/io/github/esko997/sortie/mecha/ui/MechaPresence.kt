package io.github.esko997.sortie.mecha.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSeat
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaThrowTransfer

/** A one-shot arrival for this exact window; never replayed when a different stage adopts him. */
internal data class MechaArrival(val stage: MechaStage, val fraction: Float, val thrown: MechaThrowTransfer?)

/**
 * The one mech, process-wide: which display he is on and so which stage has him ([owner]). Stages register while composed
 * and say when they are active; the owner is the most recently activated active stage on his display. When his display has
 * no stage at all (a window went away for good) the next active stage adopts him. He changes display only by walking off
 * one screen ([handOff]) into another. Main thread only.
 */
object MechaPresence {
    private val stages = ArrayList<MechaStage>()
    private var display: Int? = null

    internal var owner by mutableStateOf<MechaStage?>(null)
        private set

    private var pointerOwner by mutableStateOf<MechaStage?>(null)

    /** Defer companion controller handback until the carried mech has finished landing, on either display. */
    val handlingPointer: Boolean get() = pointerOwner != null

    internal fun holdPointer(stage: MechaStage) {
        if (owner === stage) pointerOwner = stage
    }

    internal fun releasePointer(stage: MechaStage) {
        if (pointerOwner === stage) pointerOwner = null
    }

    /** The edge he walks in from on the stage that just took him over, until it does. */
    internal var arriving: MechaSpotKind? = null

    /** A carried or thrown arrival, consumed by its destination's director. */
    internal var carriedIn: MechaArrival? = null

    /** Process-wide reaction bookkeeping, so a second stage never repeats what the first already said. */
    internal var welcomeAt = 0L
        private set
    internal var greeted = 0L
    internal var primed = false
    internal var seenMessage: String? = null
    internal var seenState: MechaState? = null

    /**
     * The player came back from a game or app (the host closed its play session): the mech, wherever he is, greets them
     * on his next look at the stage, together with any salvage the session earned.
     */
    fun playerReturned() {
        welcomeAt = SystemClock.uptimeMillis()
        owner?.signal?.trySend(Unit)
    }

    internal fun register(stage: MechaStage) {
        if (stage !in stages) stages += stage
        recompute()
    }

    internal fun unregister(stage: MechaStage) {
        stages -= stage
        stage.active = false
        recompute()
    }

    internal fun setActive(stage: MechaStage, active: Boolean) {
        if (stage.active == active) return
        stage.active = active
        if (active && stages.remove(stage)) stages += stage // most recently activated last
        recompute()
    }

    /** An active stage on another display with room for him, the one a stroll may take him to. */
    internal fun otherWithRoom(from: MechaStage): MechaStage? = stages.lastOrNull { it.displayId != from.displayId && canReceive(it) }

    /** A covered, inactive or replaced window must not receive an in-flight handoff. */
    private fun canReceive(to: MechaStage): Boolean = to.active && stages.lastOrNull { it.active && it.displayId == to.displayId } === to && to.hasRoom()

    /** He left [from] by its [side] edge: [to]'s display has him now, and he walks in there from the same side. */
    internal fun handOff(to: MechaStage, side: MechaSpotKind) {
        display = to.displayId
        arriving = side
        carriedIn = null
        recompute()
    }

    /**
     * Carried to the edge facing [to]'s display and let go: that display has him now and he drops in there through the
     * facing edge, at [fraction] of its width.
     */
    internal fun handOffDrop(to: MechaStage, fraction: Float, thrown: MechaThrowTransfer? = null): Boolean {
        if (!canReceive(to)) return false
        if (pointerOwner === owner) pointerOwner = to
        display = to.displayId
        arriving = null
        carriedIn = MechaArrival(to, fraction, thrown)
        recompute()
        return true
    }

    private fun recompute() {
        val (d, index) = MechaRoam.host(stages.map { MechaSeat(it.displayId, it.active) }, display)
        display = d
        owner = stages.getOrNull(index)
        if (owner !== carriedIn?.stage) carriedIn = null
        if (pointerOwner !== owner) pointerOwner = null
    }
}
