package io.github.esko997.sortie.mecha.ui

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaMove
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A stroll now and then while the stage is up (the dock's old cadence). */
private const val STROLL_MIN_MS = 45_000L
private const val STROLL_MAX_MS = 90_000L

/** No key or touch for this long and he dozes off (still, no frames) until the next one. */
private const val DOZE_MS = 180_000L

/** Layout must stay put this long before an off-screen mech comes back (not mid-scroll). */
private const val SETTLE_MS = 1_200L

/** Credit for a finished session lands a moment after the return; the greeting waits for it. */
private const val WELCOME_WAIT_MS = 1_500L

/** A return older than this when he gets to it is not greeted any more. */
private const val WELCOME_STALE_MS = 60_000L

private const val BUBBLE_MS = 5_000L

/** Walk pace: [STRIDE] frame pixels per step, one step per walk frame, so the feet and the floor agree. */
private const val STRIDE = 3
private const val WALK_STEP_MS = 120L
private const val HOP_STEPS = 7
private const val HOP_STEP_MS = 70L
private const val HOP_HEIGHT = 14
private const val SINK_PX = 8
private const val SINK_STEP_MS = 60L

/**
 * Drives one stage's mech: while [MechaPresence] gives it the mech he is placed at a spot that fits, strolls between spots
 * now and then, reacts to returns from play and to the Hangar's news with a cheer and a bubble, dozes when nobody is around,
 * and may cross to the other screen. Everything waits on timers or the stage's signal, so a mech standing still costs no frames.
 */
internal class MechaDirector(private val stage: MechaStage, private val measure: (String) -> IntSize, private val random: Random = Random.Default) {
    /** Where he stands; null while off screen. */
    private var at: MechaPlacement? = null

    /** His x relative to his spot's element, to follow it when it moves (a scrolled grid). */
    private var anchorDx = 0
    private var previous: String? = null
    private var lastSpot: String? = null
    private var strollsHere = 0
    private var nextStroll = 0L
    private var settleAt = 0L
    private var bubbleUntil = 0L
    private var pendingMessage: String? = null
    private var pendingCheer = false

    private val state: MechaState? get() = stage.store.view.value.state
    private val motion: Boolean get() = state?.motion == true
    private val resting: Boolean get() = state?.sleeping == true

    suspend fun run() {
        while (true) {
            snapshotFlow { MechaPresence.owner === stage }.first { it }
            try {
                coroutineScope {
                    val loop = launch { live() }
                    snapshotFlow { MechaPresence.owner === stage }.first { !it }
                    loop.cancel()
                }
            } finally {
                lastSpot = at?.spotId ?: lastSpot
                offstage()
            }
        }
    }

    private suspend fun live() = coroutineScope {
        // the store's news reaches the loop through the stage's signal
        launch {
            stage.store.view.collect { v ->
                if (!MechaPresence.primed) {
                    MechaPresence.primed = true
                    MechaPresence.seenMessage = v.message
                    MechaPresence.seenState = v.state
                }
                val before = MechaPresence.seenState
                val after = v.state
                if (v.message != null && v.message != MechaPresence.seenMessage) {
                    pendingMessage = v.message
                    if (before != null && after != null) {
                        pendingCheer = pendingCheer || after.owned.size > before.owned.size || after.bond > before.bond || after.name != before.name
                    }
                }
                MechaPresence.seenMessage = v.message
                MechaPresence.seenState = after
                stage.signal.trySend(Unit)
            }
        }
        val now = SystemClock.uptimeMillis()
        nextStroll = now + strollDelay()
        stage.lastInput = now
        stage.layoutChanged = false
        arrive()
        while (true) {
            tick()
            withTimeoutOrNull(nextWake() - SystemClock.uptimeMillis()) { stage.signal.receive() }
        }
    }

    private fun strollDelay(): Long = random.nextLong(STROLL_MIN_MS, STROLL_MAX_MS)

    private fun nextWake(): Long {
        val now = SystemClock.uptimeMillis()
        var t = now + STROLL_MAX_MS
        if (stage.bubble != null) t = min(t, bubbleUntil)
        // with the Hangar open nothing else happens until it closes (which signals)
        if (!stage.hangarOpen) {
            t = min(t, nextStroll)
            if (at != null && !stage.dozing && !resting) t = min(t, stage.lastInput + DOZE_MS)
            if (at == null && settleAt > 0) t = min(t, settleAt)
            val welcome = MechaPresence.welcomeAt
            if (welcome > MechaPresence.greeted) t = min(t, welcome + WELCOME_WAIT_MS)
        }
        return max(t, now + 16)
    }

    private suspend fun tick() {
        val now = SystemClock.uptimeMillis()
        val b = stage.bounds
        if (b == null || state == null) {
            hide()
            return
        }
        if (stage.layoutChanged) {
            stage.layoutChanged = false
            settle()
        }
        if (stage.bubble != null && now >= bubbleUntil) stage.bubble = null
        if (stage.wakeRequested) {
            stage.wakeRequested = false
            stage.dozing = false
            still()
        }
        if (at != null && !stage.dozing && !resting && now - stage.lastInput >= DOZE_MS) {
            stage.dozing = true
            stage.bubble = null
            still()
        }
        if (stage.hangarOpen) return // news waits until the Hangar is closed; he does not walk under it either
        val welcome = MechaPresence.welcomeAt
        if (welcome > MechaPresence.greeted && now >= welcome + WELCOME_WAIT_MS) {
            MechaPresence.greeted = welcome
            if (now - welcome < WELCOME_STALE_MS) {
                val news = pendingMessage
                pendingMessage = null
                pendingCheer = false
                react(if (news != null) "Welcome back!\n$news" else "Welcome back!", cheer = true)
            }
        }
        pendingMessage?.let { text ->
            // a return from play is greeted together with what it earned
            if (welcome > MechaPresence.greeted) return@let
            val cheer = pendingCheer
            pendingMessage = null
            pendingCheer = false
            react(text, cheer)
        }
        if (at == null && settleAt in 1..now) {
            settleAt = 0
            comeBack()
        }
        if (now >= nextStroll) {
            nextStroll = now + strollDelay()
            if (!stage.dozing) stroll()
        }
    }

    // ---- placement ----

    private fun elements() = stage.elements.values

    /** Where he may stand now (tappable spots, else the home base, else nowhere); [spread] varies x along wide spots. */
    private fun options(scale: Int, bounds: MechaRect, spread: Boolean): List<MechaPlacement> = MechaRoam.places(elements(), scale, bounds, stage.minTap) { e ->
        if (!spread || !motion) null else e.bounds.left + random.nextInt(max(1, e.bounds.width)) - MechaBody.width(scale) / 2
    }

    private fun rescale(b: MechaRect) {
        stage.scale = MechaRoam.chooseScale(elements(), b, stage.preferredScale, stage.minTap)
    }

    /** Motion off: always the first qualifying place in declaration order, so a still companion is placed the same way every time. */
    private fun pick(opts: List<MechaPlacement>, from: MechaPlacement?, b: MechaRect): MechaPlacement? =
        if (!motion) opts.firstOrNull() else MechaRoam.chooseNext(opts, null, previous, from, b.width, random)

    /** Becoming the owner: walk in after a hand-off, else stand where he was (or anywhere that qualifies) at once. */
    private suspend fun arrive() {
        val b = stage.bounds ?: return
        rescale(b)
        val side = MechaPresence.arriving
        MechaPresence.arriving = null
        if (side != null && motion && !resting) {
            strollsHere = 0
            enterFrom(side, b)
            return
        }
        val opts = options(stage.scale, b, spread = false)
        val p = opts.firstOrNull { it.spotId == lastSpot } ?: pick(opts, null, b)
        if (p == null) {
            settleAt = SystemClock.uptimeMillis() + SETTLE_MS
            return
        }
        place(p)
    }

    /** Off screen and the layout has settled: back to a place that qualifies, rising into it. */
    private suspend fun comeBack() {
        val b = stage.bounds ?: return
        rescale(b)
        val p = pick(options(stage.scale, b, spread = true), null, b) ?: return
        if (motion && !resting) rise(p)
        place(p)
    }

    /** The layout changed under him: follow his element, or get out of the way when it no longer qualifies. */
    private suspend fun settle() {
        val b = stage.bounds ?: return
        val cur = at
        if (cur == null) {
            settleAt = SystemClock.uptimeMillis() + SETTLE_MS
            return
        }
        val spot = stage.elements[cur.spotId]
        val p = spot?.let { MechaRoam.place(it, stage.scale, b, elements(), it.bounds.left + anchorDx, stage.minTap) }
        if (p == null) {
            pushedOff(cur)
            return
        }
        if (p != cur) place(p) else stage.stand(p) // things may have moved around him: re-check his tap area and bubble
    }

    private suspend fun pushedOff(cur: MechaPlacement) {
        stage.bubble = null
        stage.stand(null)
        if (motion && !resting && stage.elements.containsKey(cur.spotId)) sink(cur)
        lastSpot = cur.spotId
        hide()
        settleAt = SystemClock.uptimeMillis() + SETTLE_MS
    }

    /** Stands at [p] still: position, pose, tap target. */
    private fun place(p: MechaPlacement) {
        at = p
        anchorDx = p.x - (stage.elements[p.spotId]?.bounds?.left ?: p.x)
        stage.x = p.x
        stage.floor = p.floor
        stage.clip = p.clip
        stage.bob = 0
        still()
        stage.shown = true
        stage.stand(p)
    }

    private fun still() {
        stage.frame = if (resting || stage.dozing) MechaPose.SLEEP.stillFrame else MechaPose.IDLE.stillFrame
        stage.bob = 0
    }

    private fun hide() {
        at = null
        stage.shown = false
        stage.stand(null)
        stage.bubble = null
    }

    private fun offstage() {
        hide()
        stage.dozing = false
    }

    // ---- behaviour ----

    private suspend fun stroll() {
        val b = stage.bounds ?: return
        if (!motion || resting) return
        val cur = at
        val other = MechaPresence.otherWithRoom(stage)
        if (cur != null) strollsHere++
        val here = options(stage.scale, b, spread = true)
        if (other != null && (MechaRoam.crossOver(strollsHere, true, random) || cur == null && here.isEmpty())) {
            leave(other, b)
            return
        }
        val next = MechaRoam.chooseNext(here, cur?.spotId, previous, cur, b.width, random)
        when {
            cur == null && next != null -> {
                rise(next)
                place(next)
            }
            next != null && cur != null -> moveTo(cur, next, b)
        }
    }

    private suspend fun moveTo(from: MechaPlacement, to: MechaPlacement, b: MechaRect) {
        stage.bubble = null
        stage.stand(null)
        when (MechaRoam.planMove(from, to, stage.scale, b.width)) {
            MechaMove.WALK -> walk(to.x)
            MechaMove.HOP -> hop(to)
            MechaMove.SINK -> {
                sink(from)
                rise(to)
            }
        }
        previous = from.spotId
        // the layout may have moved while he walked: stand where the spot is now
        val spot = stage.elements[to.spotId]
        val now = spot?.let { MechaRoam.place(it, stage.scale, b, elements(), to.x, stage.minTap) }
        if (now != null) place(now) else pushedOff(to)
    }

    /** Off this screen by its nearest edge and onto [other]. */
    private suspend fun leave(other: MechaStage, b: MechaRect) {
        val cur = at
        val edges = MechaRoam.edges(elements(), stage.scale, b)
        val e = if (cur == null) edges.randomOrNull(random) else edges.minByOrNull { abs(it.x - cur.x) + abs(it.floor - cur.floor) }
        stage.bubble = null
        stage.stand(null)
        val side = e?.kind ?: if (cur != null && cur.x > b.left + b.width / 2) MechaSpotKind.EDGE_END else MechaSpotKind.EDGE_START
        if (e != null) {
            if (cur == null) {
                walkInFrom(e, b)
            } else if (cur.floor == e.floor && cur.clip == e.clip && abs(cur.x - e.x) <= b.width / 2) {
                walk(e.x)
            } else {
                sink(cur)
                rise(e)
            }
            walk(MechaRoam.offstageX(e.kind, b, stage.scale))
        } else if (cur != null) {
            sink(cur)
        }
        lastSpot = null
        hide()
        strollsHere = 0
        MechaPresence.handOff(other, side)
    }

    /** Taken over from the other screen: in from [side]'s edge (or up through a spot), then on to a spot that fits. */
    private suspend fun enterFrom(side: MechaSpotKind, b: MechaRect) {
        val edges = MechaRoam.edges(elements(), stage.scale, b)
        val e = edges.firstOrNull { it.kind == side } ?: edges.firstOrNull()
        val next = MechaRoam.chooseNext(options(stage.scale, b, spread = true), null, null, e, b.width, random)
        if (e == null) {
            if (next == null) {
                settleAt = SystemClock.uptimeMillis() + SETTLE_MS
                return
            }
            rise(next)
            place(next)
            return
        }
        walkInFrom(e, b)
        place(e)
        if (next == null) {
            // nowhere to stand here (not even the home base): straight back out of sight
            stage.stand(null)
            walk(MechaRoam.offstageX(e.kind, b, stage.scale))
            hide()
            settleAt = SystemClock.uptimeMillis() + SETTLE_MS
            return
        }
        delay(400)
        moveTo(e, next, b)
    }

    /**
     * A cheer and/or a bubble: where he stands when the bubble fits beside him, else at the nearest spot where it does; with no
     * room for a bubble anywhere just the cheer. Resting he only shows the bubble.
     */
    private suspend fun react(text: String, cheer: Boolean) {
        val b = stage.bounds ?: return
        stage.dozing = false
        stage.lastInput = SystemClock.uptimeMillis()
        val size = measure(text)
        val gap = 2 * stage.scale
        var cur = at
        var rect = cur?.let { MechaRoam.bubble(it, size.width, size.height, gap, b, elements()) }
        if (rect == null) {
            val roomy = options(stage.scale, b, spread = false).mapNotNull { p ->
                MechaRoam.bubble(p, size.width, size.height, gap, b, elements())?.let { p to it }
            }
            val pick = roomy.minByOrNull { (p, _) -> if (cur == null) 0 else abs(p.x - cur!!.x) + abs(p.floor - cur!!.floor) }
            if (pick != null) {
                val (p, r) = pick
                when {
                    cur != null && motion && !resting -> moveTo(cur, p, b)
                    cur == null && motion && !resting -> {
                        rise(p)
                        place(p)
                    }
                    else -> place(p)
                }
                cur = at
                rect = cur?.let { MechaRoam.bubble(it, size.width, size.height, gap, b, elements()) } ?: r.takeIf { at?.spotId == p.spotId }
            }
        }
        if (rect != null && at != null) {
            stage.bubble = MechaBubble(text, rect)
            bubbleUntil = SystemClock.uptimeMillis() + BUBBLE_MS
        }
        if (cheer && at != null && motion && !resting) celebrate()
        still()
    }

    // ---- motion: each step moves whole sprite pixels and redraws only the layer ----

    private suspend fun celebrate() {
        val pose = MechaPose.CELEBRATE
        repeat(pose.steps * 2) { i ->
            stage.frame = pose.frameAt(i)
            stage.bob = pose.bobAt(i)
            delay(pose.stepMs)
        }
        stage.bob = 0
    }

    private suspend fun walk(target: Int) {
        val s = stage.scale
        val stride = STRIDE * s * if (target > stage.x) 1 else -1
        var i = 0
        while (abs(target - stage.x) >= abs(stride)) {
            if (!motion) break
            stage.frame = MechaPose.WALK.frameAt(i)
            stage.bob = MechaPose.WALK.bobAt(i)
            delay(WALK_STEP_MS)
            stage.x += stride
            i++
        }
        stage.x = target
        still()
    }

    private suspend fun hop(to: MechaPlacement) {
        val s = stage.scale
        val x0 = stage.x
        val f0 = stage.floor
        stage.clip = Int.MAX_VALUE
        stage.frame = MechaPose.CELEBRATE.stillFrame
        for (k in 1..HOP_STEPS) {
            delay(HOP_STEP_MS)
            val t = k.toFloat() / HOP_STEPS
            stage.x = x0 + ((to.x - x0) * t / s).roundToInt() * s
            val arc = (4 * t * (1 - t) * HOP_HEIGHT).roundToInt()
            stage.floor = f0 + ((to.floor - f0) * t / s).roundToInt() * s - arc * s
        }
        stage.x = to.x
        stage.floor = to.floor
        still()
    }

    /** Down out of sight through the line he stands on (a peek through its element's top edge). */
    private suspend fun sink(from: MechaPlacement) {
        val s = stage.scale
        val line = if (from.kind == MechaSpotKind.PEEK) from.clip else from.floor
        stage.clip = line
        still()
        while (MechaBody.frameTop(stage.floor, s) < line) {
            delay(SINK_STEP_MS)
            stage.floor += SINK_PX * s
        }
        stage.shown = false
    }

    /** Up into [to] from below its line. */
    private suspend fun rise(to: MechaPlacement) {
        val s = stage.scale
        val line = if (to.kind == MechaSpotKind.PEEK) to.clip else to.floor
        stage.x = to.x
        stage.clip = line
        stage.floor = line + (MechaBody.FRAME - 1) * s
        still()
        stage.shown = true
        while (stage.floor - SINK_PX * s > to.floor) {
            delay(SINK_STEP_MS)
            stage.floor -= SINK_PX * s
        }
        delay(SINK_STEP_MS)
        stage.floor = to.floor
        stage.clip = to.clip
    }

    /** From just out of sight past [e]'s edge to its spot. */
    private suspend fun walkInFrom(e: MechaPlacement, b: MechaRect) {
        stage.x = MechaRoam.offstageX(e.kind, b, stage.scale)
        stage.floor = e.floor
        stage.clip = Int.MAX_VALUE
        still()
        stage.shown = true
        walk(e.x)
    }
}
