package io.github.esko997.sortie.mecha.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.IntSize
import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaDo
import io.github.esko997.sortie.mecha.MechaDrop
import io.github.esko997.sortie.mecha.MechaEdge
import io.github.esko997.sortie.mecha.MechaFlight
import io.github.esko997.sortie.mecha.MechaMove
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaPlay
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaThrowTransfer
import io.github.esko997.sortie.mecha.MechaThrows
import io.github.esko997.sortie.mecha.MechaTouch
import io.github.esko997.sortie.mecha.MechaVignette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MechaDirector"

/** A stroll (or, now and then, a vignette) every so often while the stage is up. */
private const val STROLL_MIN_MS = 45_000L
private const val STROLL_MAX_MS = 90_000L

/** One time in this many a due stroll is a vignette instead, when one fits. */
private const val VIGNETTE_ODDS = 3

/** One time in this many a stroll goes somewhere no safe route reaches (down out of sight and up there), when there is one. */
private const val EXPLORE_ODDS = 5

/** He sets off on his own only after this long without a key or touch, and with the layout this long unchanged. */
private const val ROAM_CALM_MS = 8_000L
private const val LAYOUT_CALM_MS = 2_000L

/** He glances at what the player settled on this long after the last input, at most once per cooldown. */
private const val ATTENTION_SETTLE_MS = 600L
private const val ATTENTION_COOLDOWN_MS = 5_000L

/** Frame pixels either side of his middle where what the player is on counts as straight ahead (no glance). */
private const val ATTENTION_AHEAD = 16

/** A stopped trip stands him on the ground under him only within this many frame pixels of where he stopped. */
private const val RECOVER_SNAP = 8

/** No key or touch for this long and he dozes off (asleep, breathing slowly) until the next one. */
private const val DOZE_MS = 180_000L

/** Layout must stay put this long before an off-screen mech comes back (not mid-scroll). */
private const val SETTLE_MS = 1_200L

/** Credit for a finished session lands a moment after the return; the greeting waits for it. */
private const val WELCOME_WAIT_MS = 1_500L

/** A return older than this when he gets to it is not greeted any more. */
private const val WELCOME_STALE_MS = 60_000L

private const val BUBBLE_MS = 5_000L

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
 *
 * Trips ([MechaMotion]) follow a safe route of nearby perches ([MechaRoam.route]) with weight: a look, a crouch, a push-off,
 * an arc and a landing that compresses and recovers. They start only once the player has been still for a while, never on a
 * quiet (reading) surface, install no tap target until he stands again, and stop between beats when the layout, the motion
 * setting or the surface changes, leaving him on freshly checked ground or out of sight, never on a stale target.
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

    /** When the director last saw the layout change: trips wait for it to stay put. */
    private var layoutAt = 0L

    /** What he last glanced at (the focus x) and when he may glance again. */
    private var attended: Int? = null
    private var attentionAt = 0L

    /** The beat of the trip shown last (null before a trip's first): whether a stopped trip has him in the air. */
    private var lastBeat: MechaBeat? = null

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
        layoutAt = now
        attended = stage.attentionX
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
        // nothing can happen before the first layout or the store's state, both of which signal
        if (stage.bounds == null || state == null) return now + STROLL_MAX_MS
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
        if (MechaPresence.owner !== stage) return
        if (stage.layoutChanged) {
            stage.layoutChanged = false
            layoutAt = now
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
        if (now() >= nextStroll) {
            val t = now()
            // only once the player has left things be for a while and the layout has stayed put
            val calm = max(stage.lastInput + ROAM_CALM_MS, layoutAt + LAYOUT_CALM_MS)
            when {
                stage.quiet || stage.dozing || !motion || resting -> nextStroll = t + strollDelay()
                t < calm -> nextStroll = calm
                else -> {
                    if (!(random.nextInt(VIGNETTE_ODDS) == 0 && vignette())) stroll()
                    nextStroll = now() + strollDelay()
                }
            }
        }
        if (idling && !resting && !stage.dozing) attend(now())
        if (idling && now() >= nextIdle) {
            val (rig, ms) = idle.next(asleep = resting || stage.dozing, quiet = stage.quiet, attention = stage.attentionX?.let { side(it) } ?: 0)
            stage.rig = rig
            nextIdle = now() + ms
        }
    }

    /**
     * Once the player's input has settled on something new, a glance its way on the next idle beat; at most once per
     * cooldown, never while keys keep coming, and the breath carries on (nothing restarts).
     */
    private fun attend(now: Long) {
        if (now - stage.lastInput < ATTENTION_SETTLE_MS || now < attentionAt) return
        val ax = stage.attentionX ?: return
        if (ax == attended) return
        attended = ax
        attentionAt = now + ATTENTION_COOLDOWN_MS
        val dir = side(ax)
        if (dir == 0) return
        idle.glance(dir)
        nextIdle = now
    }

    /** Which side of him window x [x] is: -1, 1, or 0 when about straight ahead. */
    private fun side(x: Int): Int {
        val d = x - (stage.x + MechaBody.width(stage.scale) / 2)
        return if (abs(d) < ATTENTION_AHEAD * stage.scale) 0 else d.sign
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
        val dropped = MechaPresence.carriedIn
        MechaPresence.carriedIn = null
        val side = MechaPresence.arriving
        MechaPresence.arriving = null
        if (dropped != null) {
            strollsHere = 0
            if (dropped.thrown != null) thrownIn(dropped.thrown, b) else dropIn(dropped.fraction, b)
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
        if (motion && !resting && !rise(p)) {
            outOfSight()
            return
        }
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
            pushedOff(cur, moved = true)
            return
        }
        if (p != cur) place(p) else stage.stand(p) // things may have moved around him: re-check his tap area and bubble
    }

    /**
     * Out of the way from [cur]: sinking through its line while the layout is as it was, at once when it [moved] (he is drawn
     * over the interface; the spot's surroundings may already be somewhere else).
     */
    private suspend fun pushedOff(cur: MechaPlacement, moved: Boolean = false) {
        stage.bubble = null
        stage.stand(null)
        if (!moved && motion && !resting && stage.elements.containsKey(cur.spotId)) sink(line(cur))
        lastSpot = cur.spotId
        outOfSight()
    }

    /** Hidden until the layout has settled, then back ([comeBack]). */
    private fun outOfSight() {
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
        stage.dropAt = null
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
                val release = stage.dropAt ?: MechaRelease(stage.x.toFloat(), stage.floor.toFloat(), allowAcross = false)
                stage.dropAt = null
                drop(release, b)
            }
            else -> Unit
        }
    }

    /** A tap on him: wakes him from a doze, a happy bounce with a heart, and a little bond (rate-limited by the store). */
    private suspend fun pet() {
        stage.store.pet()
        stage.lastInput = now()
        // a quiet surface marks any vignette interrupted; this tap starts a fresh little reaction
        stage.interrupted = false
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

    /** Released after a carry: thrown across, gently handed over, or put down on a local perch. */
    private suspend fun drop(release: MechaRelease, b: MechaRect) {
        at = null
        stage.stand(null)
        stage.front = false
        rescale(b)
        val (fx, fy) = release
        val other = if (release.allowAcross) MechaPresence.otherWithRoom(stage) else null
        // Geometry collected while he was in hand is the starting point, not an interruption of this new flight.
        stage.layoutChanged = false
        layoutAt = now()
        if (other != null && throwAcross(release, b, other)) return
        val places = options(stage.scale, b, spread = false)
        val d = MechaPlay.drop(fx, fy, stage.x, stage.floor, places, b, stage.toward, stage.edgeZone, other != null)
        Log.i(TAG, "display ${stage.displayId}: dropped at ${fx.roundToInt()},${fy.roundToInt()} -> $d")
        when (d) {
            is MechaDrop.Across -> {
                if (motion) {
                    // out through the edge that faces the other screen
                    val s = stage.scale
                    val out = if (stage.toward == MechaEdge.BOTTOM) b.bottom + MechaBody.FRAME * s else b.top - s
                    glide(stage.x, out, 0, land = false)
                }
                lastSpot = null
                hide()
                strollsHere = 0
                if (!MechaPresence.handOffDrop(other!!, d.fraction)) outOfSight()
            }
            is MechaDrop.Land -> {
                if (motion) glide(d.at.x, d.at.floor, LAND_HOP, land = true)
                previous = lastSpot
                if (validLanding(d.at)) place(d.at) else outOfSight()
            }
            MechaDrop.Nowhere -> outOfSight()
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
            glide(target.at.x, target.at.floor, LAND_HOP, land = true)
        }
        place(target.at)
    }

    /** A deliberate flick continues beyond the facing edge; only a still-active destination can take him over. */
    private suspend fun throwAcross(release: MechaRelease, b: MechaRect, other: MechaStage): Boolean {
        val toward = stage.toward
        val exit = MechaThrows.exit(stage.x, stage.floor, stage.scale, release.velocity, b, toward, stage.minTap) ?: return false
        Log.i(TAG, "display ${stage.displayId}: throw toward ${other.displayId}")
        if (motion && !fly(exit.path, other)) {
            outOfSight()
            return true
        }
        lastSpot = null
        hide()
        strollsHere = 0
        if (!MechaPresence.handOffDrop(other, exit.transfer.fraction, exit.transfer)) outOfSight()
        return true
    }

    /** A fresh safe perch is selected on arrival; the source never sends a stale destination coordinate. */
    private suspend fun thrownIn(transfer: MechaThrowTransfer, b: MechaRect) {
        val toward = stage.toward
        val s = stage.scale
        val aim = MechaThrows.aim(transfer, s, b, toward, stage.minTap)
        val target = MechaPlay.drop(0f, 0f, aim.x, aim.floor, options(s, b, spread = false), b, null, 0, false)
        if (target !is MechaDrop.Land) {
            outOfSight()
            return
        }
        val p = target.at
        val path = MechaThrows.enter(transfer, p, s, b, toward, stage.minTap)
        if (motion && !fly(path)) {
            if (!motion && validLanding(p)) place(p) else outOfSight()
            return
        }
        if (!validLanding(p)) {
            outOfSight()
            return
        }
        if (motion) {
            stage.front = true
            stage.busy = true
            stage.interrupted = false
            lastBeat = null
            val landed = try {
                play(MechaMotion.land(p.x, p.floor, 0, fell = abs(p.floor - path.floorAt(0f)) / s, random = random), roaming = false)
            } finally {
                stage.front = false
                stage.busy = false
            }
            if (!landed && motion) {
                outOfSight()
                return
            }
        }
        if (validLanding(p)) {
            place(p)
            Log.i(TAG, "display ${stage.displayId}: throw landed")
        } else {
            outOfSight()
        }
    }

    private fun validLanding(p: MechaPlacement): Boolean {
        val b = stage.bounds ?: return false
        val element = stage.elements[p.spotId] ?: return false
        return !stage.hangarOpen && MechaRoam.place(element, stage.scale, b, elements(), p.x, stage.minTap) == p
    }

    /** Display-paced, whole-pixel flight. No hit target, physics allocations or timer loop survive the flight. */
    private suspend fun fly(path: MechaFlight, destination: MechaStage? = null): Boolean {
        stage.stand(null)
        stage.x = path.xAt(0f)
        stage.floor = path.floorAt(0f)
        stage.clip = Int.MAX_VALUE
        stage.bob = 0
        stage.rig = MechaRigs.DANGLE
        stage.shown = true
        stage.front = true
        stage.busy = true
        stage.interrupted = false
        var started = -1L
        var complete = false
        try {
            while (!complete) {
                val valid = withFrameMillis { frame ->
                    if (!motion || !stage.active || MechaPresence.owner !== stage || stage.layoutChanged ||
                        stage.interrupted || stage.hangarOpen || stage.carried || destination?.active == false
                    ) {
                        return@withFrameMillis false
                    }
                    if (started < 0) started = frame
                    val t = ((frame - started).toFloat() / path.durationMs).coerceIn(0f, 1f)
                    stage.x = path.xAt(t)
                    stage.floor = path.floorAt(t)
                    complete = t >= 1f
                    true
                }
                if (!valid) return false
            }
            return true
        } finally {
            stage.front = false
            stage.busy = false
        }
    }

    // ---- behaviour ----

    /**
     * Somewhere else to stand: mostly a perch a safe route of walks and hops reaches ([MechaRoam.route]); when none does, or
     * now and then to see the rest of the screen, a place no route reaches, down out of sight and up there. Now and then
     * across to the other screen instead.
     */
    private suspend fun stroll() {
        val b = stage.bounds ?: return
        if (!motion || resting || stage.quiet) return
        val cur = at
        val other = MechaPresence.otherWithRoom(stage)
        if (cur != null) strollsHere++
        val here = options(stage.scale, b, spread = true)
        if (other != null && (MechaRoam.crossOver(strollsHere, true, random) || cur == null && here.isEmpty())) {
            leave(other, b)
            return
        }
        if (cur == null) {
            val next = MechaRoam.chooseNext(here, null, previous, null, b.width, random) ?: return
            if (rise(next)) place(next) else outOfSight()
            return
        }
        val routes = here.filter { it.spotId != cur.spotId || abs(it.x - cur.x) >= cur.exposed.width }
            .map { it to MechaRoam.route(cur, it, stage.scale, b, elements()) }
        val near = routes.filter { it.second.isNotEmpty() }
        val far = routes.filter { it.second.isEmpty() }
        val pool = if (near.isEmpty() || far.isNotEmpty() && random.nextInt(EXPLORE_ODDS) == 0) far else near
        val next = MechaRoam.chooseNext(pool.map { it.first }, cur.spotId, previous, cur, b.width, random) ?: return
        moveTo(cur, next, roaming = true, known = pool.firstOrNull { it.first == next }?.second)
    }

    /**
     * A short vignette instead of a stroll, when one fits ([MechaPlay.vignettes]): he goes there, plays it and comes back
     * to a spot. Any key or touch ends it at once (the element he holds is back in place before the input is handled), as
     * does the surface turning quiet. Returns false when none fits.
     */
    private suspend fun vignette(): Boolean {
        val b = stage.bounds ?: return false
        val cur = at ?: return false
        if (!motion || resting || stage.quiet) return false
        val v = MechaPlay.choose(
            MechaPlay.vignettes(cur, options(stage.scale, b, spread = false), stage.elements, stage.scale, b, stage.focused, stage.touches.values),
            random
        ) ?: return false
        Log.i(TAG, "display ${stage.displayId}: ${v.kind} ${v.prop ?: v.spot.spotId}")
        stage.interrupted = false
        stage.busy = true
        try {
            if (v.spot != cur) {
                val reached = travel(cur, v.spot, roaming = true)
                previous = cur.spotId
                // stopped on the way: he already stands on freshly checked ground, or is out of sight
                if (!reached) return true
            }
            if (stage.carried) return true
            if (v.kind == MechaDo.LIFT) {
                at = v.spot
                stage.x = v.spot.x
                stage.floor = v.spot.floor
                stage.clip = Int.MAX_VALUE
                stage.shown = true
            } else {
                place(v.spot)
            }
            if (!stage.interrupted) {
                when (v.kind) {
                    MechaDo.NAP -> nap()
                    MechaDo.SIT -> sit(v)
                    MechaDo.POKE -> poke(v)
                    MechaDo.LIFT -> lift(v)
                }
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
            val now = stage.bounds ?: return true
            val back = (MechaPlay.drop(0f, 0f, from.x, from.floor, options(stage.scale, now, spread = false), now, null, 0, false) as? MechaDrop.Land)?.at
            if (back == null) {
                pushedOff(from)
            } else if (travel(from, back, roaming = false)) {
                place(back)
            }
        } else {
            // back on his feet where the spot is now: the layout may have moved during the vignette
            val e = stage.elements[v.spot.spotId]
            val now = stage.bounds
            val p = if (e == null || now == null) null else MechaRoam.place(e, stage.scale, now, elements(), e.bounds.left + anchorDx, stage.minTap)
            if (p != null) place(p) else pushedOff(v.spot, moved = true)
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

    /**
     * From [from] to [to] ([travel]; [known]: its route, already worked out), then standing at [to] as the layout has it
     * now, looking over at what the player is on. False when the trip stopped on the way (he stands on freshly checked
     * ground or is out of sight then).
     */
    private suspend fun moveTo(from: MechaPlacement, to: MechaPlacement, roaming: Boolean = false, known: List<MechaPlacement>? = null): Boolean {
        val arrived = travel(from, to, roaming, known)
        previous = from.spotId
        if (!arrived) return false
        val b = stage.bounds
        val spot = stage.elements[to.spotId]
        val now = if (b == null) null else spot?.let { MechaRoam.place(it, stage.scale, b, elements(), to.x, stage.minTap) }
        if (now == null) {
            pushedOff(to)
            return true
        }
        place(now)
        stage.attentionX?.let { idle.glance(side(it)) }
        return true
    }

    /**
     * The way from [from] to [to]: along a safe route of walks and hops when there is one ([MechaRoam.route], or [known]),
     * else the deliberate fallback, down out of sight here and up there. No tap target on the way. True when he got there;
     * false when stopped (then [recover] or the fallback has left him on fresh ground or out of sight).
     */
    private suspend fun travel(from: MechaPlacement, to: MechaPlacement, roaming: Boolean, known: List<MechaPlacement>? = null): Boolean {
        val b = stage.bounds ?: return false
        stage.bubble = null
        stage.stand(null)
        val route = known ?: MechaRoam.route(from, to, stage.scale, b, elements())
        if (route.isNotEmpty()) return follow(from, route, roaming)
        sink(line(from))
        if (disturbed(roaming, airborne = false) || !rise(to)) {
            lastSpot = from.spotId
            outOfSight()
            return false
        }
        return true
    }

    /**
     * Leg by leg along [route] from [from]: a run of walks on one floor is one walk (he does not stop at perches he only
     * passes), a hop is a whole jump. Between beats the trip stops when [disturbed]; [recover] then decides where he is.
     */
    private suspend fun follow(from: MechaPlacement, route: List<MechaPlacement>, roaming: Boolean): Boolean {
        val s = stage.scale
        var a = from
        var i = 0
        var start = true
        lastBeat = null
        while (i < route.size) {
            var to = route[i]
            val move = MechaRoam.legMove(a, to)
            if (move == MechaMove.WALK) {
                while (i + 1 < route.size && MechaRoam.legMove(to, route[i + 1]) == MechaMove.WALK) to = route[++i]
            }
            val last = i == route.size - 1
            val p = a
            val q = to
            val was = mapOf(p.spotId to stage.elements[p.spotId]?.bounds, q.spotId to stage.elements[q.spotId]?.bounds)
            val beats = if (move == MechaMove.WALK) {
                stage.clip = p.clip
                MechaMotion.walk(p.x, q.x, p.floor, s, random, start = start, stop = last)
            } else {
                stage.clip = Int.MAX_VALUE
                MechaMotion.hop(
                    p.x,
                    p.floor,
                    q.x,
                    q.floor,
                    s,
                    random,
                    { t -> MechaRoam.hopX(p, q, s, t) },
                    { t -> MechaRoam.hopFloor(p, q, s, t) },
                    start = start
                )
            }
            if (!play(beats, roaming, hopFrom = p.takeIf { move == MechaMove.HOP }, hopTo = q)) {
                recover(p, q, was)
                return false
            }
            stage.clip = q.clip
            start = false
            a = q
            i++
        }
        return true
    }

    /**
     * Whether a trip must stop before its next beat. In the air ([airborne]: mid-jump, until the landing) only a layout
     * change, motion turned off or losing the stage stop it; on the ground also the surface turning quiet ([roaming] trips
     * only), the Hangar, rest, or an input during a vignette.
     */
    private fun disturbed(roaming: Boolean, airborne: Boolean): Boolean {
        if (!motion || stage.layoutChanged || stage.bounds == null || stage.carried) return true
        if (airborne) return false
        return resting || stage.hangarOpen || (roaming && stage.quiet) || (stage.busy && stage.interrupted)
    }

    /**
     * Shows [beats] in turn. Travel interpolates whole-pixel positions on display frames while authored poses hold their
     * cadence; stationary beats use timers, not a continuous frame loop. Interruptions are checked on every travel frame.
     * [guarded] false is used for a carried drop or an edge crossing; layout changes still stop it.
     */
    private suspend fun play(
        beats: List<MechaBeat>,
        roaming: Boolean,
        guarded: Boolean = true,
        hopFrom: MechaPlacement? = null,
        hopTo: MechaPlacement? = null
    ): Boolean {
        val flightCount = if (hopFrom == null) 0 else beats.count { it.airborne }
        var flightIndex = 0
        var travelEnd: Long? = null
        for (beat in beats) {
            if (!guarded) {
                if (stage.layoutChanged) return false
                if (!motion) {
                    show(beats.last())
                    return true
                }
            }
            if (guarded && disturbed(roaming, airborne = beat.airborne || lastBeat?.airborne == true)) return false
            val x0 = stage.x
            val floor0 = stage.floor
            if (beat.airborne || beat.x != x0 || beat.floor != floor0) {
                val started = travelEnd ?: withFrameMillis { it }
                var progress = 0f
                lastBeat = beat
                while (progress < 1f) {
                    if (stage.layoutChanged || (guarded && disturbed(roaming, airborne = beat.airborne))) return false
                    if (!motion) {
                        if (guarded) return false
                        show(beats.last())
                        return true
                    }
                    withFrameMillis { frame ->
                        progress = ((frame - started).toFloat() / beat.ms).coerceIn(0f, 1f)
                        if (beat.airborne && hopFrom != null && hopTo != null) {
                            val t = (flightIndex + progress) / flightCount
                            stage.x = MechaRoam.hopX(hopFrom, hopTo, stage.scale, t)
                            stage.floor = MechaRoam.hopFloor(hopFrom, hopTo, stage.scale, t)
                        } else {
                            stage.x = MechaMotion.position(x0, beat.x, stage.scale, progress)
                            stage.floor = MechaMotion.position(floor0, beat.floor, stage.scale, progress)
                        }
                        stage.rig = beat.rig
                        stage.bob = 0
                    }
                }
                travelEnd = started + beat.ms
            } else {
                travelEnd = null
                onFrame { show(beat) }
                delay(beat.ms)
            }
            if (beat.airborne) flightIndex++
        }
        return if (guarded) !disturbed(roaming, airborne = false) else !stage.layoutChanged
    }

    private fun show(beat: MechaBeat) {
        stage.x = beat.x
        stage.floor = beat.floor
        stage.rig = beat.rig
        stage.bob = 0
        lastBeat = beat
    }

    /**
     * A trip from [from] to [to] stopped. On the ground he stands where the ground under him is now: the element at either
     * end of the leg, re-placed by its own movement since the leg began ([was]) within [RECOVER_SNAP] of him, so a scroll
     * carries him with it as it would standing. In the air with the layout unchanged (motion turned off) he lands where he
     * was going at once, checked afresh. Otherwise out of sight at once while things move (sinking where he is when they did
     * not), back once the layout settles. Never onto a stale position.
     */
    private suspend fun recover(from: MechaPlacement, to: MechaPlacement, was: Map<String, MechaRect?>) {
        val b = stage.bounds
        val airborne = lastBeat?.airborne == true
        lastBeat = null
        if (b != null && state != null) {
            val s = stage.scale
            if (airborne) {
                val e = stage.elements[to.spotId]
                val p = if (stage.layoutChanged || e == null) null else MechaRoam.place(e, s, b, elements(), to.x, stage.minTap)
                if (p != null) {
                    place(p)
                    return
                }
            } else {
                val ids = if (abs(stage.x - to.x) < abs(stage.x - from.x)) listOf(to.spotId, from.spotId) else listOf(from.spotId, to.spotId)
                for (id in ids.distinct()) {
                    val e = stage.elements[id] ?: continue
                    val old = was[id] ?: continue
                    val x = stage.x + e.bounds.left - old.left
                    val floor = stage.floor + e.bounds.top - old.top
                    val p = MechaRoam.place(e, s, b, elements(), x, stage.minTap) ?: continue
                    if (p.floor != floor || abs(p.x - x) > RECOVER_SNAP * s) continue
                    place(p)
                    return
                }
            }
        }
        lastSpot = from.spotId
        stage.bubble = null
        stage.stand(null)
        if (!airborne && b != null && motion && !resting && !stage.layoutChanged) sink(min(stage.clip, stage.floor))
        outOfSight()
    }

    /** Off this screen by its nearest edge and onto [other]; stays here when the way to the edge is interrupted. */
    private suspend fun leave(other: MechaStage, b: MechaRect) {
        val cur = at
        val edges = MechaRoam.edges(elements(), stage.scale, b)
        val e = if (cur == null) edges.randomOrNull(random) else edges.minByOrNull { abs(it.x - cur.x) + abs(it.floor - cur.floor) }
        stage.bubble = null
        stage.stand(null)
        val side = e?.kind ?: if (cur != null && cur.x > b.left + b.width / 2) MechaSpotKind.EDGE_END else MechaSpotKind.EDGE_START
        if (e != null) {
            val out = if (cur == null) walkInFrom(e, b) else travel(cur, e, roaming = true)
            // stopped on the way to the edge: he stays on this screen (on fresh ground or out of sight until it settles)
            if (!out) {
                if (cur == null) outOfSight()
                return
            }
            walkTo(MechaRoam.offstageX(e.kind, b, stage.scale), start = true, stop = false)
        } else if (cur != null) {
            sink(line(cur))
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
            if (rise(next)) place(next) else outOfSight()
            return
        }
        if (!walkInFrom(e, b)) {
            outOfSight()
            return
        }
        place(e)
        if (next == null) {
            // nowhere to stand here (not even the home base): straight back out of sight
            stage.stand(null)
            walkTo(MechaRoam.offstageX(e.kind, b, stage.scale), start = true, stop = false)
            outOfSight()
            return
        }
        delay(400)
        moveTo(e, next)
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
                    cur != null && motion && !resting -> moveTo(cur, p)
                    cur == null && motion && !resting -> if (rise(p)) place(p) else outOfSight()
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

    // ---- motion: each step moves whole sprite pixels on a display frame and redraws only the layer ----

    /** The director runs under its stage's composition and shares that display's frame clock. */
    private suspend fun onFrame(block: () -> Unit) {
        withFrameMillis { block() }
    }

    private suspend fun celebrate() {
        val pose = MechaPose.CELEBRATE
        repeat(pose.steps * 2) { i ->
            onFrame {
                stage.rig = MechaRig(pose.frameAt(i))
                stage.bob = pose.bobAt(i)
            }
            delay(pose.stepMs)
        }
        stage.bob = 0
    }

    /**
     * Along his floor to box left [target] at walking pace, unguarded (edge entries and exits, where he only goes out of view
     * or into a checked edge spot); false when the layout changed on the way.
     */
    private suspend fun walkTo(target: Int, start: Boolean, stop: Boolean): Boolean {
        lastBeat = null
        val done = play(MechaMotion.walk(stage.x, target, stage.floor, stage.scale, random, start = start, stop = stop), roaming = false, guarded = false)
        if (done) stage.x = target
        still()
        return done
    }

    /**
     * From where he is to ([toX], [toFloor]) in a few quick steps, arcing [arc] sprite pixels up on the way (whole pixels),
     * then with [land] the landing: his weight compresses and recovers.
     */
    private suspend fun glide(toX: Int, toFloor: Int, arc: Int, land: Boolean) {
        val s = stage.scale
        val x0 = stage.x
        val f0 = stage.floor
        stage.clip = Int.MAX_VALUE
        for (k in 1..LAND_STEPS) {
            delay(LAND_STEP_MS)
            val t = k.toFloat() / LAND_STEPS
            onFrame {
                stage.x = x0 + ((toX - x0) * t / s).roundToInt() * s
                val up = (4 * t * (1 - t) * arc).roundToInt()
                stage.floor = f0 + ((toFloor - f0) * t / s).roundToInt() * s - up * s
            }
        }
        stage.x = toX
        stage.floor = toFloor
        if (land && motion) {
            lastBeat = null
            play(MechaMotion.land(toX, toFloor, 0, fell = (toFloor - f0) / s, random = random), roaming = false, guarded = false)
        }
        still()
    }

    /** The line he stands on or peeks over at [p]. */
    private fun line(p: MechaPlacement): Int = if (p.kind == MechaSpotKind.PEEK) p.clip else p.floor

    /**
     * Down out of sight through [line] (the line he stands on, or a peek's element top edge). Gone at once instead when the
     * layout changes or motion is turned off on the way: drawn over the interface, he never lingers on a stale spot.
     */
    private suspend fun sink(line: Int) {
        val s = stage.scale
        stage.clip = line
        still()
        while (MechaBody.frameTop(stage.floor, s) < line) {
            delay(SINK_STEP_MS)
            if (stage.layoutChanged || !motion) break
            onFrame { stage.floor += SINK_PX * s }
        }
        stage.shown = false
    }

    /**
     * Up into [to] from below its line. False (and out of sight again) when the layout changes or motion is turned off on
     * the way: [to] may be stale then.
     */
    private suspend fun rise(to: MechaPlacement): Boolean {
        val s = stage.scale
        val line = line(to)
        stage.x = to.x
        stage.clip = line
        stage.floor = line + (MechaBody.FRAME - 1) * s
        still()
        stage.shown = true
        while (stage.floor - SINK_PX * s > to.floor) {
            delay(SINK_STEP_MS)
            if (stage.layoutChanged || !motion) {
                stage.shown = false
                return false
            }
            onFrame { stage.floor -= SINK_PX * s }
        }
        delay(SINK_STEP_MS)
        if (stage.layoutChanged || !motion) {
            stage.shown = false
            return false
        }
        onFrame {
            stage.floor = to.floor
            stage.clip = to.clip
        }
        return true
    }

    /** From just out of sight past [e]'s edge to its spot; false when the layout changed on the way. */
    private suspend fun walkInFrom(e: MechaPlacement, b: MechaRect): Boolean {
        stage.x = MechaRoam.offstageX(e.kind, b, stage.scale)
        stage.floor = e.floor
        stage.clip = Int.MAX_VALUE
        still()
        stage.shown = true
        return walkTo(e.x, start = false, stop = true)
    }
}
