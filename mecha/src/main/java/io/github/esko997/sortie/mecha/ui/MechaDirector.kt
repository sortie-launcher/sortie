package io.github.esko997.sortie.mecha.ui

import android.os.SystemClock
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.IntSize
import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaDo
import io.github.esko997.sortie.mecha.MechaDrop
import io.github.esko997.sortie.mecha.MechaEdge
import io.github.esko997.sortie.mecha.MechaMove
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaPlay
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaTouch
import io.github.esko997.sortie.mecha.MechaVignette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A stroll (or, now and then, a vignette) every so often while the stage is up. */
private const val STROLL_MIN_MS = 45_000L
private const val STROLL_MAX_MS = 90_000L

/** One time in this many a due stroll is a vignette instead, when one fits. */
private const val VIGNETTE_ODDS = 3

/** No key or touch for this long and he dozes off (asleep, breathing slowly) until the next one. */
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

/** In hand: the legs swing a pixel each step. */
private const val SWING_MS = 180L

/** Let go: a short glide down to the nearest place with this little hop. */
private const val LAND_STEPS = 8
private const val LAND_STEP_MS = 45L
private const val LAND_HOP = 6
private const val PET_STEP_MS = 130L
private const val PET_STEPS = 8

/**
 * Drives one stage's mech: while [MechaPresence] gives it the mech he is placed at a spot that fits, breathes and glances
 * about while he stands, strolls between spots now and then or plays a short vignette (a nap, a sit on an edge, a poke or a
 * lift of an element the host allows), reacts to pets, holds and carries, to returns from play and to the Hangar's news,
 * dozes when nobody is around, and may cross to the other screen. Everything waits on timers or the stage's signal, and the
 * whole loop exists only while this stage owns the mech, which needs its window resumed on a lit display.
 */
internal class MechaDirector(private val stage: MechaStage, private val measure: (String) -> IntSize, private val random: Random = Random.Default) {
    /** Where he stands; null while off screen or in hand. */
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
    private val idle = MechaIdle(random)
    private var nextIdle = 0L
    private var swing = 0

    private val state: MechaState? get() = stage.store.view.value.state
    private val motion: Boolean get() = state?.motion == true
    private val resting: Boolean get() = state?.sleeping == true

    private fun now(): Long = SystemClock.uptimeMillis()

    suspend fun run() {
        whileShown(snapshotFlow { MechaPresence.owner === stage }) {
            try {
                live()
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
        val now = now()
        nextStroll = now + strollDelay()
        stage.lastInput = now
        stage.layoutChanged = false
        stage.gestures.clear()
        arrive()
        while (true) {
            tick()
            withTimeoutOrNull(nextWake() - now()) { stage.signal.receive() }
        }
    }

    private fun strollDelay(): Long = random.nextLong(STROLL_MIN_MS, STROLL_MAX_MS)

    /** Idle life runs while he stands still in view with motion on and nothing else going on. */
    private val idling: Boolean get() = at != null && stage.shown && motion && !stage.hangarOpen && !stage.carried

    private fun nextWake(): Long {
        val now = now()
        if (stage.carried) return now + SWING_MS
        if (stage.gestures.isNotEmpty()) return now
        var t = now + STROLL_MAX_MS
        if (stage.bubble != null) t = min(t, bubbleUntil)
        if (idling) t = min(t, nextIdle)
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
        val now = now()
        val b = stage.bounds
        if (b == null || state == null) {
            hide()
            return
        }
        if (stage.carried) {
            // in the player's hand: the pointer watcher moves him; he only swings his legs
            if (motion) stage.rig = MechaRigs.swing(swing++)
            return
        }
        while (stage.gestures.isNotEmpty()) gesture(stage.gestures.removeFirst(), b)
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
            if (!stage.dozing && !(random.nextInt(VIGNETTE_ODDS) == 0 && vignette())) stroll()
        }
        if (idling && now() >= nextIdle) {
            val (rig, ms) = idle.next(asleep = resting || stage.dozing)
            stage.rig = rig
            nextIdle = now() + ms
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

    /** Becoming the owner: dropped in or walking in after a hand-off, else standing where he was (or anywhere that qualifies). */
    private suspend fun arrive() {
        val b = stage.bounds ?: return
        rescale(b)
        val dropped = MechaPresence.droppedIn
        MechaPresence.droppedIn = null
        val side = MechaPresence.arriving
        MechaPresence.arriving = null
        if (dropped != null) {
            strollsHere = 0
            dropIn(dropped, b)
            return
        }
        if (side != null && motion && !resting) {
            strollsHere = 0
            enterFrom(side, b)
            return
        }
        val opts = options(stage.scale, b, spread = false)
        val p = opts.firstOrNull { it.spotId == lastSpot } ?: pick(opts, null, b)
        if (p == null) {
            settleAt = now() + SETTLE_MS
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
            settleAt = now() + SETTLE_MS
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
        settleAt = now() + SETTLE_MS
    }

    /** Stands at [p] still: position, pose, tap target. */
    private fun place(p: MechaPlacement) {
        at = p
        anchorDx = p.x - (stage.elements[p.spotId]?.bounds?.left ?: p.x)
        stage.x = p.x
        stage.floor = p.floor
        stage.clip = p.clip
        stage.bob = 0
        stage.front = false
        still()
        stage.shown = true
        stage.stand(p)
    }

    /** The still pose (awake or asleep); idle life picks up from it a beat later. */
    private fun still() {
        stage.rig = if (resting || stage.dozing) MechaRigs.ASLEEP else MechaRigs.STAND
        stage.bob = 0
        idle.reset()
        nextIdle = now() + MechaIdle.BEAT_MS
    }

    private fun hide() {
        at = null
        stage.shown = false
        stage.stand(null)
        stage.bubble = null
        stage.front = false
        stage.heart = -1
        stage.snore = -1
        stage.nudge = null
    }

    private fun offstage() {
        hide()
        stage.dozing = false
        stage.carried = false
        stage.busy = false
        stage.interrupted = false
        stage.gestures.clear()
    }

    // ---- gestures ----

    private suspend fun gesture(touch: MechaTouch, b: MechaRect) {
        when (touch) {
            MechaTouch.PET -> pet()
            MechaTouch.HANGAR -> {
                // put down where he was picked up, and the Hangar opens over the window
                val p = at
                if (p != null) place(p) else comeBack()
                stage.openHangar()
            }
            MechaTouch.DROP -> {
                val (fx, fy) = stage.dropAt ?: (stage.x.toFloat() to stage.floor.toFloat())
                stage.dropAt = null
                drop(fx, fy, b)
            }
            else -> Unit
        }
    }

    /** A tap on him: wakes him from a doze, a happy bounce with a heart, and a little bond (rate-limited by the store). */
    private suspend fun pet() {
        stage.store.pet()
        stage.lastInput = now()
        if (stage.dozing) {
            stage.dozing = false
            stage.wakeRequested = false
        }
        if (at == null) return
        if (!motion) {
            stage.heart = 0
            pause(PET_STEP_MS * PET_STEPS)
            stage.heart = -1
            return
        }
        if (!resting) stage.bob = 0
        for (i in 0 until PET_STEPS) {
            if (!resting) stage.rig = MechaRigs.pet(i)
            stage.heart = i
            if (!pause(PET_STEP_MS)) break
        }
        stage.heart = -1
        if (!stage.carried) still()
    }

    /** Let go with the finger at ([fx], [fy]): across to the other display, down to the nearest place, or out of sight. */
    private suspend fun drop(fx: Float, fy: Float, b: MechaRect) {
        at = null
        stage.stand(null)
        stage.front = false
        rescale(b)
        val other = MechaPresence.otherWithRoom(stage)
        val places = options(stage.scale, b, spread = false)
        when (val d = MechaPlay.drop(fx, fy, stage.x, stage.floor, places, b, stage.toward, stage.edgeZone, other != null)) {
            is MechaDrop.Across -> {
                if (motion) {
                    // out through the edge that faces the other screen
                    val s = stage.scale
                    val out = if (stage.toward == MechaEdge.BOTTOM) b.bottom + MechaBody.FRAME * s else b.top - s
                    glide(stage.x, out, 0)
                }
                lastSpot = null
                hide()
                strollsHere = 0
                MechaPresence.handOffDrop(other!!, d.fraction)
            }
            is MechaDrop.Land -> {
                if (motion) glide(d.at.x, d.at.floor, LAND_HOP)
                previous = lastSpot
                place(d.at)
            }
            MechaDrop.Nowhere -> {
                hide()
                settleAt = now() + SETTLE_MS
            }
        }
    }

    /** Dropped in from the other screen at [fraction] of the width: in through the edge facing it, onto the nearest place. */
    private suspend fun dropIn(fraction: Float, b: MechaRect) {
        val s = stage.scale
        val w = MechaBody.width(s)
        val x = b.left + (fraction * b.width).roundToInt() - w / 2
        val floor = if (stage.toward == MechaEdge.TOP) b.top else b.bottom + MechaBody.FRAME * s
        val target = MechaPlay.drop(0f, 0f, x, floor, options(s, b, spread = false), b, null, 0, false)
        if (target !is MechaDrop.Land) {
            settleAt = now() + SETTLE_MS
            return
        }
        if (motion) {
            stage.x = x
            stage.floor = floor
            stage.clip = Int.MAX_VALUE
            stage.front = false
            still()
            stage.shown = true
            glide(target.at.x, target.at.floor, LAND_HOP)
        }
        place(target.at)
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

    /**
     * A short vignette instead of a stroll, when one fits ([MechaPlay.vignettes]): he goes there, plays it and comes back
     * to a spot. Any key or touch ends it at once (the element he holds is back in place before the input is handled).
     * Returns false when none fits.
     */
    private suspend fun vignette(): Boolean {
        val b = stage.bounds ?: return false
        val cur = at ?: return false
        if (!motion || resting) return false
        val v = MechaPlay.choose(
            MechaPlay.vignettes(cur, options(stage.scale, b, spread = false), stage.elements, stage.scale, b, stage.focused, stage.touches.values),
            random
        ) ?: return false
        stage.interrupted = false
        stage.busy = true
        try {
            if (v.spot != cur) {
                travel(cur, v.spot, b)
                previous = cur.spotId
            }
            if (stage.interrupted || stage.carried) return true
            if (v.kind == MechaDo.LIFT) {
                at = v.spot
                stage.x = v.spot.x
                stage.floor = v.spot.floor
                stage.clip = Int.MAX_VALUE
                stage.shown = true
            } else {
                place(v.spot)
            }
            when (v.kind) {
                MechaDo.NAP -> nap()
                MechaDo.SIT -> sit(v)
                MechaDo.POKE -> poke(v)
                MechaDo.LIFT -> lift(v)
            }
        } finally {
            stage.busy = false
            stage.interrupted = false
            stage.nudge = null
            stage.snore = -1
            stage.front = false
        }
        if (stage.carried) return true
        if (v.kind == MechaDo.LIFT) {
            // under an element is no spot: back to the nearest place that qualifies
            val from = at ?: return true
            val back = (MechaPlay.drop(0f, 0f, from.x, from.floor, options(stage.scale, b, spread = false), b, null, 0, false) as? MechaDrop.Land)?.at
            if (back == null) {
                pushedOff(from)
            } else {
                travel(from, back, b)
                place(back)
            }
        } else {
            place(v.spot)
        }
        return true
    }

    /** Waits [ms] unless any input ends the vignette (or he is picked up) first: false then. */
    private suspend fun pause(ms: Long): Boolean {
        val end = now() + ms
        while (true) {
            if (stage.interrupted || stage.carried) return false
            val left = end - now()
            if (left <= 0) return true
            withTimeoutOrNull(left) { stage.signal.receive() }
        }
    }

    /** A nap where he stands: asleep, breathing, a Z rising now and then; then a blink awake. */
    private suspend fun nap() {
        val beats = 8 + random.nextInt(5)
        for (i in 0 until beats) {
            stage.rig = MechaRigs.ASLEEP.upper(0, if (i % 2 == 0) 0 else -1)
            stage.snore = if (i % 3 == 2) -1 else i % 3
            if (!pause(900)) return
        }
        stage.snore = -1
        stage.rig = MechaRigs.BLINK
        pause(200)
    }

    /** Sits on the edge with his legs dangling over the element's face (drawn over it), kicking now and then. */
    private suspend fun sit(v: MechaVignette) {
        val s = stage.scale
        // down in two steps, then seated
        stage.front = true
        stage.clip = Int.MAX_VALUE
        stage.floor = v.spot.floor.coerceAtMost(v.pose.floor - MechaPlay.SIT_DROP / 2 * s)
        stage.x = v.pose.x
        if (!pause(120)) return
        stage.floor = v.pose.floor
        val beats = 16 + random.nextInt(8)
        for (i in 0 until beats) {
            stage.rig = MechaRigs.sit(i, blink = random.nextInt(8) == 0)
            if (!pause(450)) return
        }
    }

    /** Pokes the element beside him three times; it jiggles away a pixel each time. */
    private suspend fun poke(v: MechaVignette) {
        val id = v.prop ?: return
        val s = stage.scale
        repeat(3) {
            stage.rig = MechaRigs.poke(v.toward, out = true)
            stage.nudge = MechaNudge(id, v.toward * s, 0)
            if (!pause(150)) return
            stage.rig = MechaRigs.poke(v.toward, out = false)
            stage.nudge = null
            if (!pause(320)) return
        }
        stage.rig = MechaRigs.BLINK
        pause(200)
    }

    /** Under the element: squats, pushes it up a few pixels, strains (wobbling), and lets it down again. */
    private suspend fun lift(v: MechaVignette) {
        val id = v.prop ?: return
        val s = stage.scale
        stage.rig = MechaRigs.strain(-1, 0)
        if (!pause(350)) return
        for (h in 1..MechaPlay.LIFT_HEIGHT) {
            stage.rig = MechaRigs.strain(h, h)
            stage.nudge = MechaNudge(id, 0, -h * s)
            if (!pause(160)) return
        }
        for (i in 0 until 12) {
            stage.rig = MechaRigs.strain(MechaPlay.LIFT_HEIGHT - i % 2, i)
            stage.nudge = MechaNudge(id, 0, -(MechaPlay.LIFT_HEIGHT - i % 2) * s)
            if (!pause(130)) return
        }
        for (h in MechaPlay.LIFT_HEIGHT - 1 downTo 0) {
            stage.rig = MechaRigs.strain(h, h)
            stage.nudge = if (h == 0) null else MechaNudge(id, 0, -h * s)
            if (!pause(120)) return
        }
        stage.rig = MechaRigs.STAND
    }

    private suspend fun moveTo(from: MechaPlacement, to: MechaPlacement, b: MechaRect) {
        travel(from, to, b)
        previous = from.spotId
        // the layout may have moved while he walked: stand where the spot is now
        val spot = stage.elements[to.spotId]
        val now = spot?.let { MechaRoam.place(it, stage.scale, b, elements(), to.x, stage.minTap) }
        if (now != null) place(now) else pushedOff(to)
    }

    /** The way from [from] to [to]: a walk, a hop or a sink and rise ([MechaRoam.planMove]). */
    private suspend fun travel(from: MechaPlacement, to: MechaPlacement, b: MechaRect) {
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
                settleAt = now() + SETTLE_MS
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
            settleAt = now() + SETTLE_MS
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
        stage.lastInput = now()
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
            bubbleUntil = now() + BUBBLE_MS
        }
        if (cheer && at != null && motion && !resting) celebrate()
        still()
    }

    // ---- motion: each step moves whole sprite pixels and redraws only the layer ----

    private suspend fun celebrate() {
        val pose = MechaPose.CELEBRATE
        repeat(pose.steps * 2) { i ->
            stage.rig = MechaRig(pose.frameAt(i))
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
            stage.rig = MechaRig(MechaPose.WALK.frameAt(i))
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
        stage.rig = MechaRig(MechaPose.CELEBRATE.stillFrame)
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

    /** From where he is to ([toX], [toFloor]) in a few quick steps, arcing [arc] sprite pixels up on the way (whole pixels). */
    private suspend fun glide(toX: Int, toFloor: Int, arc: Int) {
        val s = stage.scale
        val x0 = stage.x
        val f0 = stage.floor
        stage.clip = Int.MAX_VALUE
        for (k in 1..LAND_STEPS) {
            delay(LAND_STEP_MS)
            val t = k.toFloat() / LAND_STEPS
            stage.x = x0 + ((toX - x0) * t / s).roundToInt() * s
            val up = (4 * t * (1 - t) * arc).roundToInt()
            stage.floor = f0 + ((toFloor - f0) * t / s).roundToInt() * s - up * s
        }
        stage.x = toX
        stage.floor = toFloor
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
