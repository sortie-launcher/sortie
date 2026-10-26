package io.github.esko997.sortie.mecha

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * How a declared spot places the mech against the element that declares it (its anchor). Every declared element, spot or not,
 * is also an obstacle for the others: a spot counts only where the visible part of the mech stays clear of all of them. Only
 * the empty spaces ([ROOM], [FOOTER], [BASE]) are no obstacle: they are where he goes.
 */
enum class MechaSpotKind {
    /** Standing on the anchor's top edge, the whole mech above it. */
    LEDGE,

    /** Behind the anchor, only the head showing above its top edge. The anchor must be the element that hides the body. */
    PEEK,

    /** On the anchor's bottom line, just after its end (right in left-to-right layouts). */
    BESIDE_END,

    /** On the anchor's bottom line, just before its start. */
    BESIDE_START,

    /** Inside the anchor, which is empty space, standing on its bottom edge. */
    ROOM,

    /**
     * Inside the anchor, empty space along the screen's bottom edge (a footer between its labels): standing on its bottom
     * edge, or where it is shorter than he is, standing lower with his head just inside its top edge and his legs past the
     * bottom of the screen. Takes no more room than the footer has.
     */
    FOOTER,

    /** At the screen's start edge, partly off screen, standing on the anchor's top edge: the way in and out of the screen. */
    EDGE_START,

    /** Like [EDGE_START] at the end edge. */
    EDGE_END,

    /**
     * The host's reserved home base: empty space the size of the mech box ([io.github.esko997.sortie.mecha.ui.mechaBaseSize])
     * that nothing else uses. He stands inside it on its bottom edge; it is always one of his places, and the only one when no
     * other spot qualifies.
     */
    BASE;

    internal val edge: Boolean get() = this == EDGE_START || this == EDGE_END
}

/** A rectangle in window pixels, [right] and [bottom] exclusive. */
internal data class MechaRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun intersects(o: MechaRect): Boolean = !isEmpty && !o.isEmpty && left < o.right && o.left < right && top < o.bottom && o.top < bottom

    fun intersect(o: MechaRect): MechaRect = MechaRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

    fun contains(o: MechaRect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    /** The smallest rectangle holding both. */
    fun union(o: MechaRect): MechaRect = MechaRect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
}

/**
 * A declared element as last laid out: [bounds] unclipped, [visible] after its ancestors' clipping (a scrolled-away tile is
 * empty). [kind] null: only an obstacle. [interactive]: a control, which a tap target must never cover. [solid]: something
 * drawn over its whole bounds; a transparent row that only takes taps is not. [props]: what he may do with it besides
 * standing by it ([MechaPlay]).
 */
internal class MechaElement(
    val id: String,
    val kind: MechaSpotKind?,
    val bounds: MechaRect,
    val visible: MechaRect,
    val interactive: Boolean,
    val solid: Boolean = true,
    val props: Set<MechaProp> = emptySet()
) {
    val wholeOnScreen: Boolean get() = !visible.isEmpty && visible == bounds

    /**
     * What the mech, standing or moving, and his bubbles keep clear of: anything [solid] but a declared empty space (a room, a
     * footer, the base), which is where he goes. The drawing clips him against these too.
     */
    val blocks: Boolean get() = solid && kind != MechaSpotKind.ROOM && kind != MechaSpotKind.FOOTER && kind != MechaSpotKind.BASE
}

/**
 * Sprite geometry, in frame pixels. A frame is [MECHA_FRAME] square, drawn at a whole-number [scale]; the box adds
 * [MECHA_HEADROOM] rows above for hops. Feet are outlined on row 62, so the frame's bottom row hangs one pixel under the floor.
 * Art never uses the outer columns, so the box's art lies within columns [ART_LEFT] until [ART_RIGHT].
 */
internal object MechaBody {
    const val FRAME = 64
    const val HEADROOM = 3
    const val ART_LEFT = 5
    const val ART_RIGHT = 59

    /** Frame rows a peek shows above the edge at least: the head down to just under the eyes (eyes sit on rows 14-19). */
    const val PEEK_ROWS = 21

    /** Art pixels a mech waiting at the screen edge shows, at most and at least. */
    const val EDGE_REVEAL = 24
    const val EDGE_REVEAL_MIN = 12

    fun width(scale: Int): Int = FRAME * scale
    fun height(scale: Int): Int = (FRAME + HEADROOM) * scale

    /** Top of frame row 0 for a mech standing on [floor]. */
    fun frameTop(floor: Int, scale: Int): Int = floor - (FRAME - 1) * scale

    /** The opaque art's extent for box left [x] standing on [floor]. */
    fun art(x: Int, floor: Int, scale: Int): MechaRect = MechaRect(x + ART_LEFT * scale, frameTop(floor, scale), x + ART_RIGHT * scale, floor)

    /**
     * Frame rows a peek shows above its edge: at least [PEEK_ROWS], and enough that the showing part is [minTap] pixels tall
     * at [scale] (head and shoulders, so he can be tapped). Never the whole frame: that would be standing, not peeking.
     */
    fun peekRows(scale: Int, minTap: Int): Int = max(PEEK_ROWS, (minTap + scale - 1) / scale).coerceAtMost(FRAME - 2)

    /** The floor that leaves exactly [rows] frame rows above [edge]. */
    fun peekFloor(edge: Int, scale: Int, rows: Int = PEEK_ROWS): Int = edge + (FRAME - 1 - rows) * scale
}

/**
 * Where the mech stands: box left [x], feet on [floor], drawn only above [clip] (a peek hides everything under its anchor's
 * top edge). [exposed] is the art left showing, in window pixels.
 */
internal data class MechaPlacement(val spotId: String, val kind: MechaSpotKind, val x: Int, val floor: Int, val clip: Int, val exposed: MechaRect)

/** A window that can host the mech: its [display] and whether it is [active] (resumed, allowed, companion shown). */
internal data class MechaSeat(val display: Int, val active: Boolean)

/** How the mech gets from one stop of a [MechaRoam.route] to the next. */
internal enum class MechaMove { WALK, HOP }

/** Pure geometry and choices for the roaming companion; the stage keeps the elements, this decides what fits. */
internal object MechaRoam {
    /** Step, in frame pixels, between x positions tried along a spot. */
    private const val SEARCH_STEP = 4

    /** Frame pixels between the mech and the element it stands beside. */
    private const val BESIDE_GAP = 2

    /** The lowest height the mech should stand on any screen, in millimetres (the owner asked for about 9-10 mm). */
    const val MIN_HEIGHT_MM = 9f

    /**
     * The whole-number scale of the 64-pixel frame that makes him about the same physical size everywhere: the smallest
     * scale at least [MIN_HEIGHT_MM] tall on a display with [ydpi] vertical pixels per inch. Some displays report a made-up
     * dpi; one implausibly far from the density bucket [densityDpi] is ignored in favour of the bucket.
     */
    fun physicalScale(ydpi: Float, densityDpi: Int): Int {
        val dpi = if (ydpi >= 72f && ydpi in densityDpi * 0.6f..densityDpi * 1.6f) ydpi else densityDpi.toFloat()
        val px = MIN_HEIGHT_MM / 25.4f * dpi
        return max(1, kotlin.math.ceil(px / MechaBody.FRAME).toInt())
    }

    /**
     * The placement for [spot] at [scale] nearest to [preferX] (box left; null: the spot's natural position) where the exposed
     * art stays inside [stage] (a footer's legs past its bottom) and, with [MOTION_MARGIN] around it, clear of every other
     * element that [MechaElement.blocks], or null when it does not fit anywhere along the spot. With
     * [minTap] above 0 it also has to be tappable: a control-free exposed area at least [minTap] pixels each way
     * ([hitTarget]); a peek shows as many rows as that takes ([MechaBody.peekRows]).
     */
    fun place(spot: MechaElement, scale: Int, stage: MechaRect, elements: Collection<MechaElement>, preferX: Int? = null, minTap: Int = 0): MechaPlacement? {
        val kind = spot.kind ?: return null
        val a = spot.bounds
        val w = MechaBody.width(scale)
        val s = scale
        if (spot.visible.isEmpty) return null
        if (kind.edge) return placeAtEdge(spot, kind, scale, stage, elements)
        // the edge he stands on or behind must be on screen in full, or he would hang in the air
        if (!spot.wholeOnScreen) return null
        val floor: Int
        val clip: Int
        val minX: Int
        val maxX: Int
        when (kind) {
            MechaSpotKind.LEDGE -> {
                floor = a.top
                clip = Int.MAX_VALUE
                // feet (the frame's middle) at least 8 pixels inside the anchor
                minX = a.left + 8 * s - w / 2
                maxX = a.right - 8 * s - w / 2
            }
            MechaSpotKind.PEEK -> {
                floor = MechaBody.peekFloor(a.top, s, MechaBody.peekRows(s, minTap))
                clip = a.top
                // the whole body behind the anchor
                minX = a.left - MechaBody.ART_LEFT * s
                maxX = a.right - MechaBody.ART_RIGHT * s
            }
            MechaSpotKind.BESIDE_END -> {
                floor = a.bottom
                clip = Int.MAX_VALUE
                minX = a.right + (BESIDE_GAP - MechaBody.ART_LEFT) * s
                maxX = minX
            }
            MechaSpotKind.BESIDE_START -> {
                floor = a.bottom
                clip = Int.MAX_VALUE
                minX = a.left - (BESIDE_GAP + MechaBody.ART_RIGHT) * s
                maxX = minX
            }
            MechaSpotKind.ROOM, MechaSpotKind.BASE -> {
                floor = a.bottom
                clip = Int.MAX_VALUE
                minX = a.left - MechaBody.ART_LEFT * s
                maxX = a.right - MechaBody.ART_RIGHT * s
            }
            MechaSpotKind.FOOTER -> {
                floor = footerFloor(a, s)
                // standing lower than the footer only where the screen's own bottom edge cuts his legs off
                if (floor > a.bottom && floor < stage.bottom) return null
                clip = Int.MAX_VALUE
                minX = a.left - MechaBody.ART_LEFT * s
                maxX = a.right - MechaBody.ART_RIGHT * s
            }
            else -> return null
        }
        if (maxX < minX) return null
        val start = (preferX ?: ((minX + maxX) / 2)).coerceIn(minX, maxX)
        val step = SEARCH_STEP * s
        var offset = 0
        while (start - offset >= minX || start + offset <= maxX) {
            for (x in if (offset == 0) intArrayOf(start) else intArrayOf(start + offset, start - offset)) {
                if (x < minX || x > maxX) continue
                val exposed = exposedArt(x, floor, clip, s).let { if (kind == MechaSpotKind.FOOTER) it.intersect(stage) else it }
                if (!stage.contains(exposed) || !clear(exposed, spot, elements, s)) continue
                val p = MechaPlacement(spot.id, kind, x, floor, clip, exposed)
                if (minTap <= 0 || hitTarget(p, s, minTap, stage, elements) != null) return p
            }
            offset += step
        }
        return null
    }

    private fun placeAtEdge(spot: MechaElement, kind: MechaSpotKind, scale: Int, stage: MechaRect, elements: Collection<MechaElement>): MechaPlacement? {
        val s = scale
        val floor = spot.bounds.top
        // the floor line must be on screen; the anchor itself may be wider than what shows
        if (floor < spot.visible.top || floor >= spot.visible.bottom || floor - MechaBody.FRAME * s < stage.top) return null
        var reveal = MechaBody.EDGE_REVEAL
        while (reveal >= MechaBody.EDGE_REVEAL_MIN) {
            val x = edgeX(kind, stage, reveal, s)
            val exposed = MechaBody.art(x, floor, s).intersect(stage)
            if (!exposed.isEmpty && clear(exposed, spot, elements, s)) return MechaPlacement(spot.id, kind, x, floor, Int.MAX_VALUE, exposed)
            reveal -= 4
        }
        return null
    }

    /** The floor of a [MechaSpotKind.FOOTER] in [a]: its bottom, or lower so the head (and [MOTION_MARGIN]) stays inside it. */
    private fun footerFloor(a: MechaRect, scale: Int): Int = max(a.bottom, a.top + (MechaBody.FRAME - 1 + MOTION_MARGIN) * scale)

    /** Box left that shows [reveal] art pixels at [kind]'s edge. */
    fun edgeX(kind: MechaSpotKind, stage: MechaRect, reveal: Int, scale: Int): Int = if (kind == MechaSpotKind.EDGE_START) {
        stage.left + (reveal - MechaBody.ART_RIGHT) * scale
    } else {
        stage.right - (MechaBody.ART_LEFT + reveal) * scale
    }

    /** Box left just out of sight past [kind]'s edge. */
    fun offstageX(kind: MechaSpotKind, stage: MechaRect, scale: Int): Int = if (kind == MechaSpotKind.EDGE_START) {
        stage.left - MechaBody.ART_RIGHT * scale
    } else {
        stage.right - MechaBody.ART_LEFT * scale
    }

    fun exposedArt(x: Int, floor: Int, clip: Int, scale: Int): MechaRect {
        val art = MechaBody.art(x, floor, scale)
        return if (clip < art.bottom) art.copy(bottom = max(art.top, clip)) else art
    }

    /**
     * [r] (his exposed art) and [MOTION_MARGIN] above and beside it, where his idle rig shifts the head and breathes, touch
     * nothing that [MechaElement.blocks] but [anchor].
     */
    private fun clear(r: MechaRect, anchor: MechaElement, elements: Collection<MechaElement>, scale: Int): Boolean {
        val m = MOTION_MARGIN * scale
        val around = MechaRect(r.left - m, r.top - m, r.right + m, r.bottom)
        return elements.none { it !== anchor && it.blocks && it.visible.intersects(around) }
    }

    /** Every ordinary spot's placement at [scale] (each at its natural position, or [preferX] for spots that span a range). */
    fun fitting(
        elements: Collection<MechaElement>,
        scale: Int,
        stage: MechaRect,
        minTap: Int = 0,
        preferX: ((MechaElement) -> Int?)? = null
    ): List<MechaPlacement> = elements.mapNotNull { e ->
        if (e.kind == null || e.kind.edge || e.kind == MechaSpotKind.BASE) null else place(e, scale, stage, elements, preferX?.invoke(e), minTap)
    }

    /** The host's home base, when it is laid out and he fits there tappably. */
    fun base(elements: Collection<MechaElement>, scale: Int, stage: MechaRect, minTap: Int): MechaPlacement? =
        elements.firstNotNullOfOrNull { e -> if (e.kind == MechaSpotKind.BASE) place(e, scale, stage, elements, minTap = minTap) else null }

    /**
     * Where he may stand now: every spot that qualifies (fits and is tappable), and the home base where it holds him, else
     * nowhere (he hides; the Hangar header button stays the way in). Never somewhere he shows but cannot be tapped. The base
     * comes last, so a still companion (first place in declaration order) keeps standing at a spot where one qualifies.
     */
    fun places(elements: Collection<MechaElement>, scale: Int, stage: MechaRect, minTap: Int, preferX: ((MechaElement) -> Int?)? = null): List<MechaPlacement> {
        val spots = fitting(elements, scale, stage, minTap, preferX)
        val base = base(elements, scale, stage, minTap) ?: return spots
        return spots + base
    }

    /** Edge placements that fit, for walking onto and off the screen. */
    fun edges(elements: Collection<MechaElement>, scale: Int, stage: MechaRect): List<MechaPlacement> =
        elements.mapNotNull { e -> if (e.kind?.edge == true) place(e, scale, stage, elements) else null }

    /**
     * [preferred] (the physical size, [physicalScale]) when at least two spots qualify there; else the largest smaller scale
     * at which two do, else the largest at which one does, else [preferred] (he uses the home base or hides).
     */
    fun chooseScale(elements: Collection<MechaElement>, stage: MechaRect, preferred: Int, minTap: Int = 0): Int {
        var single = 0
        for (s in preferred downTo 1) {
            val n = fitting(elements, s, stage, minTap).size
            if (n >= 2) return s
            if (n == 1 && single == 0) single = s
        }
        return if (single > 0) single else preferred
    }

    /**
     * The next spot among [options]: never where he stands ([current] at [from]; another stretch of the same wide spot at least
     * his width away counts), and not [previous] while anything else fits. Spots on the same floor and nearby ones are likelier
     * (a walk reads better than a jump), but anything that fits can come up.
     */
    fun chooseNext(
        options: List<MechaPlacement>,
        current: String?,
        previous: String?,
        from: MechaPlacement?,
        stageWidth: Int,
        random: Random
    ): MechaPlacement? {
        val away = options.filter { it.spotId != current || from != null && abs(it.x - from.x) >= from.exposed.width }
        val fresh = away.filter { it.spotId != previous }.ifEmpty { away }
        if (fresh.isEmpty()) return null
        val weights = IntArray(fresh.size) { i ->
            val o = fresh[i]
            var w = 2
            if (from != null) {
                if (o.floor == from.floor) w += 4
                if (abs(o.x - from.x) <= stageWidth / 3) w += 2
            }
            w
        }
        var pick = random.nextInt(weights.sum())
        for (i in fresh.indices) {
            pick -= weights[i]
            if (pick < 0) return fresh[i]
        }
        return fresh.last()
    }

    /**
     * Frame pixels above and beside the art that his rigs may shift parts into (a head turn, a breath, a lean, the hop's
     * arms-up frame): kept clear wherever he stands or moves.
     */
    const val MOTION_MARGIN = 2

    /** A hop's arc rises this many frame pixels above the straight line between its ends, plus half the climb. */
    private const val HOP_LIFT = 10

    /** The farthest one hop goes, in frame pixels: up or down, and across. */
    private const val HOP_RISE = 40
    private const val HOP_RUN = 96

    /** What a hop costs a route beyond its distance, in frame pixels: a walk reads better than a jump. */
    private const val HOP_COST = 48

    /** A walk steps over a gap in its floor up to this wide, in frame pixels (between the tiles of a row). */
    private const val STEP_OVER = 16

    /** Stops a route search considers besides its two ends. */
    private const val ROUTE_STOPS = 48

    /** How he goes from one stop of a [route] to the next: a walk along a shared floor and clip line (x only), else a hop. */
    fun legMove(from: MechaPlacement, to: MechaPlacement): MechaMove = if (from.floor == to.floor && from.clip == to.clip) MechaMove.WALK else MechaMove.HOP

    /** How far a hop's arc rises above the straight line between its ends, in window pixels. */
    private fun hopHeight(from: MechaPlacement, to: MechaPlacement, scale: Int): Int = HOP_LIFT * scale + abs(to.floor - from.floor) / 2

    /**
     * Box left at [t] (0 to 1) of the hop from [from] to [to]: whole sprite pixels from [from], exactly [to] at the end. The
     * renderer draws these points and [route] checks the same ones, so what is drawn is what was found clear.
     */
    fun hopX(from: MechaPlacement, to: MechaPlacement, scale: Int, t: Float): Int = when {
        t <= 0f -> from.x
        t >= 1f -> to.x
        else -> (from.x + ((to.x - from.x) * t / scale).roundToInt() * scale).coerceIn(minOf(from.x, to.x), maxOf(from.x, to.x))
    }

    /** The floor at [t] of the hop from [from] to [to]: the straight line raised by a parabola, whole sprite pixels, unclipped. */
    fun hopFloor(from: MechaPlacement, to: MechaPlacement, scale: Int, t: Float): Int = when {
        t <= 0f -> from.floor
        t >= 1f -> to.floor
        else -> from.floor + (((to.floor - from.floor) * t - 4f * t * (1f - t) * hopHeight(from, to, scale)) / scale).roundToInt() * scale
    }

    /**
     * The stops from [from] to [to] at [scale], [to] last and [from] not included, through other spots that fit now (tappable
     * or not: moving, he has no tap target): walks along a declared floor and hops of a step or two, cheapest first, a walk
     * preferred over a hop. Every leg's whole path (the art and [MOTION_MARGIN] above and beside it, a hop's arc sampled
     * densely) stays inside [stage] (a leg to or from an edge spot may run out sideways, one to or from a footer out at the
     * bottom) and clear of everything that [MechaElement.blocks] but an anchor his resting art overlaps (a prop he lifts).
     * Empty when no such route is left, or either end no longer holds him (its element gone, moved or covered): then the
     * caller sinks and rises instead.
     */
    fun route(from: MechaPlacement, to: MechaPlacement, scale: Int, stage: MechaRect, elements: Collection<MechaElement>): List<MechaPlacement> {
        val fromAnchor = elements.firstOrNull { it.id == from.spotId } ?: return emptyList()
        val toAnchor = elements.firstOrNull { it.id == to.spotId } ?: return emptyList()
        if (!holds(from, fromAnchor, scale, stage, elements) || !holds(to, toAnchor, scale, stage, elements)) return emptyList()
        if (from.x == to.x && from.floor == to.floor && from.clip == to.clip) return listOf(to)
        val stops = arrayListOf(from, to)
        val anchors = arrayListOf(fromAnchor, toAnchor)
        val prefer = arrayOf(null, from.x, to.x)
        search@ for (e in elements) {
            if (e.kind == null || e.kind.edge || e.visible.isEmpty) continue
            for (x in prefer) {
                if (stops.size >= ROUTE_STOPS + 2) break@search
                // place searches the whole spot: nowhere at one preferred x is nowhere at all
                val p = place(e, scale, stage, elements, x) ?: break
                if (stops.none { it.spotId == p.spotId && it.x == p.x && it.floor == p.floor }) {
                    stops += p
                    anchors += e
                }
            }
        }
        // cheapest path from stop 0 to stop 1; legs are checked as they come up, each once
        val n = stops.size
        val cost = IntArray(n) { Int.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val done = BooleanArray(n)
        cost[0] = 0
        while (true) {
            var u = -1
            for (i in 0 until n) if (!done[i] && cost[i] != Int.MAX_VALUE && (u < 0 || cost[i] < cost[u])) u = i
            if (u < 0) return emptyList()
            if (u == 1) break
            done[u] = true
            for (v in 0 until n) {
                if (done[v]) continue
                val c = legCost(stops[u], anchors[u], stops[v], anchors[v], scale, stage, elements)
                if (c >= 0 && cost[u] + c < cost[v]) {
                    cost[v] = cost[u] + c
                    prev[v] = u
                }
            }
        }
        val path = ArrayList<MechaPlacement>()
        var i = 1
        while (i != 0) {
            path += stops[i]
            i = prev[i]
        }
        path.reverse()
        return path
    }

    /**
     * [p] still holds him against its [anchor] as laid out now: on the line its kind stands on (a vignette's pose against a
     * prop of another kind, a lift, only needs room), where its kind may stand ([area]) and clear of the rest.
     */
    private fun holds(p: MechaPlacement, anchor: MechaElement, scale: Int, stage: MechaRect, elements: Collection<MechaElement>): Boolean {
        if (anchor.visible.isEmpty) return false
        val a = anchor.bounds
        val line = when {
            anchor.kind != p.kind -> true
            p.kind == MechaSpotKind.PEEK -> p.clip == a.top
            p.kind == MechaSpotKind.LEDGE || p.kind.edge -> p.floor == a.top
            p.kind == MechaSpotKind.FOOTER -> p.floor == footerFloor(a, scale)
            else -> p.floor == a.bottom
        }
        if (!line) return false
        val art = exposedArt(p.x, p.floor, p.clip, scale)
        val shown = art.intersect(stage)
        if (shown.isEmpty || !area(p.kind, stage).contains(art)) return false
        return clear(shown, anchor, elements, scale)
    }

    /** Where [kind]'s art may be: the stage, open past the sides for an edge spot and past the bottom for a footer. */
    private fun area(kind: MechaSpotKind, stage: MechaRect): MechaRect = when {
        kind.edge -> stage.copy(left = Int.MIN_VALUE / 2, right = Int.MAX_VALUE / 2)
        kind == MechaSpotKind.FOOTER -> stage.copy(bottom = Int.MAX_VALUE / 2)
        else -> stage
    }

    /** The cost of the leg from [a] to [b] (frame distance, hops dearer), or -1 when its path is not clear all the way. */
    private fun legCost(
        a: MechaPlacement,
        anchorA: MechaElement,
        b: MechaPlacement,
        anchorB: MechaElement,
        scale: Int,
        stage: MechaRect,
        elements: Collection<MechaElement>
    ): Int {
        val s = scale
        val m = MOTION_MARGIN * s
        val dx = abs(b.x - a.x)
        // an anchor his resting art overlaps (the prop he lifts) is no obstacle on the way to or from it; any other anchor is
        val ownA = anchorA.takeIf { it.visible.intersects(exposedArt(a.x, a.floor, a.clip, s)) }
        val ownB = anchorB.takeIf { it.visible.intersects(exposedArt(b.x, b.floor, b.clip, s)) }
        val room = area(a.kind, stage).union(area(b.kind, stage))
        if (legMove(a, b) == MechaMove.WALK) {
            if (!supported(a, b, s, elements)) return -1
            val sweep = MechaRect(
                min(a.x, b.x) + MechaBody.ART_LEFT * s - m,
                MechaBody.frameTop(a.floor, s) - m,
                max(a.x, b.x) + MechaBody.ART_RIGHT * s + m,
                min(a.floor, a.clip)
            )
            return if (open(sweep, room, ownA, ownB, stage, elements)) dx else -1
        }
        // a hop shows the whole body: never out of or into a peek
        if (a.clip != Int.MAX_VALUE || b.clip != Int.MAX_VALUE) return -1
        val dy = abs(b.floor - a.floor)
        if (dy > HOP_RISE * s || dx > HOP_RUN * s) return -1
        val h = hopHeight(a, b, s)
        val steps = max(16, (dx + dy + 2 * h) / (2 * s))
        // the arc's top, where the floor turns from rising to falling, is checked exactly
        val apex = (4f * h - (b.floor - a.floor)) / (8f * h)
        var apexDone = apex <= 0f || apex >= 1f
        var last = hopArt(a, b, s, 0f)
        var k = 1
        while (k <= steps) {
            val tk = k.toFloat() / steps
            val t = if (!apexDone && apex < tk) {
                apexDone = true
                apex
            } else {
                k++
                tk
            }
            val r = hopArt(a, b, s, t)
            // both samples and everything between them (the art moves less than a frame pixel or two per sample)
            if (!open(last.union(r), room, ownA, ownB, stage, elements)) return -1
            last = r
        }
        return dx + dy + HOP_COST * s
    }

    /** His art at [t] of the hop from [a] to [b], with [MOTION_MARGIN] above and beside it for the flying rig. */
    private fun hopArt(a: MechaPlacement, b: MechaPlacement, scale: Int, t: Float): MechaRect {
        val art = MechaBody.art(hopX(a, b, scale, t), hopFloor(a, b, scale, t), scale)
        val m = MOTION_MARGIN * scale
        return MechaRect(art.left - m, art.top - m, art.right + m, art.bottom)
    }

    /**
     * Whether [r], part of a moving mech's path, is free: inside [area] (the stage, open where an end's kind runs past its
     * edge) and, where it shows, over nothing that [MechaElement.blocks] but [ownA] and [ownB].
     */
    private fun open(r: MechaRect, area: MechaRect, ownA: MechaElement?, ownB: MechaElement?, stage: MechaRect, elements: Collection<MechaElement>): Boolean {
        if (!area.contains(r)) return false
        val shown = r.intersect(stage)
        return elements.none { it !== ownA && it !== ownB && it.blocks && it.visible.intersects(shown) }
    }

    /**
     * Whether a walk from [a] to [b] has floor under its feet (the frame's middle) all the way: declared lines at its height
     * (ledge and edge tops, room and base bottoms, a footer's floor, the ground beside an element; for a peek, the top edges
     * it hides behind), with no gap wider than [STEP_OVER]. He never walks on air.
     */
    private fun supported(a: MechaPlacement, b: MechaPlacement, scale: Int, elements: Collection<MechaElement>): Boolean {
        val w = MechaBody.width(scale)
        val start = min(a.x, b.x) + w / 2
        val end = max(a.x, b.x) + w / 2
        val peek = a.clip != Int.MAX_VALUE
        val line = if (peek) a.clip else a.floor
        val spans = ArrayList<IntRange>()
        for (e in elements) {
            val k = e.kind ?: continue
            val v = e.visible
            if (v.isEmpty) continue
            val span = when {
                peek -> if (k == MechaSpotKind.PEEK && e.bounds.top == line) v.left..v.right else null
                k.edge && e.bounds.top == line -> v.left - w..v.right + w // and on past the screen's edge
                k == MechaSpotKind.LEDGE && e.bounds.top == line -> v.left..v.right
                (k == MechaSpotKind.ROOM || k == MechaSpotKind.BASE) && e.bounds.bottom == line -> v.left..v.right
                k == MechaSpotKind.FOOTER && footerFloor(e.bounds, scale) == line -> v.left..v.right
                k == MechaSpotKind.BESIDE_END && e.bounds.bottom == line -> e.bounds.right..e.bounds.right + w
                k == MechaSpotKind.BESIDE_START && e.bounds.bottom == line -> e.bounds.left - w..e.bounds.left
                else -> null
            }
            if (span != null) spans += span
        }
        spans.sortBy { it.first }
        val gap = STEP_OVER * scale
        var reach = start
        for (span in spans) {
            if (reach >= end || span.first > reach + gap) break
            if (span.last > reach) reach = span.last
        }
        return reach >= end
    }

    /**
     * A speech bubble of [width]×[height] beside [at]'s exposed art, at head height: after it, else before it, else above it,
     * wherever it stays inside [stage] and clear of every element (bubbles are drawn over the UI, so only on empty space).
     */
    fun bubble(at: MechaPlacement, width: Int, height: Int, gap: Int, stage: MechaRect, elements: Collection<MechaElement>): MechaRect? {
        val art = at.exposed
        val top = (art.top).coerceAtMost(stage.bottom - height)
        val candidates = arrayOf(
            MechaRect(art.right + gap, top, art.right + gap + width, top + height),
            MechaRect(art.left - gap - width, top, art.left - gap, top + height),
            MechaRect((art.left + art.right - width) / 2, art.top - gap - height, (art.left + art.right + width) / 2, art.top - gap)
        )
        return candidates.firstOrNull { r -> stage.contains(r) && elements.none { it.blocks && it.visible.intersects(r) } }
    }

    /**
     * The tap target over the mech: the exposed art widened to the frame where that touches no control, inside [stage]. Null
     * when it would cover a control or be smaller than [minSize] either way: then the Hangar header button is the way in.
     */
    fun hitTarget(at: MechaPlacement, scale: Int, minSize: Int, stage: MechaRect, elements: Collection<MechaElement>): MechaRect? {
        val e = at.exposed
        val wide = MechaRect(at.x, e.top, at.x + MechaBody.width(scale), e.bottom).intersect(stage)
        for (r in arrayOf(wide, e.intersect(stage))) {
            if (r.width < minSize || r.height < minSize) continue
            if (elements.none { it.interactive && it.visible.intersects(r) }) return r
        }
        return null
    }

    /**
     * After any layout change while he stands at [at] (his placement may be unchanged while things moved around him): the tap
     * target, and the [bubble] if one is up, recomputed against the elements as they are now. A control that moved into the
     * target shrinks or removes it; a bubble stays where it still is on free space, else moves to a free side, else is dropped.
     */
    fun refresh(
        at: MechaPlacement,
        scale: Int,
        minTap: Int,
        bubble: MechaRect?,
        gap: Int,
        stage: MechaRect,
        elements: Collection<MechaElement>
    ): Pair<MechaRect?, MechaRect?> {
        val hit = hitTarget(at, scale, minTap, stage, elements)
        val moved = when {
            bubble == null -> null
            stage.contains(bubble) && elements.none { it.blocks && it.visible.intersects(bubble) } -> bubble
            else -> bubble(at, bubble.width, bubble.height, gap, stage, elements)
        }
        return hit to moved
    }

    /**
     * Whether a stroll takes him to the other screen: only when it is up and has room, after he spent at least two strolls
     * here, then one time in three, so both screens get visits without ping-ponging.
     */
    fun crossOver(strollsHere: Int, otherHasRoom: Boolean, random: Random): Boolean = otherHasRoom && strollsHere >= 2 && random.nextInt(3) == 0

    /**
     * Which display has him and which of [seats] (in activation order, latest last) draws him. He stays on [current] while any
     * window is there, active or not (paused while a game runs, or between the two halves of an X swap); with none left there
     * the latest active window adopts him. Only an active seat on his display draws him, the latest one, so never two at once.
     * Returns the display (null before any window was active) and the drawing seat's index (-1: nobody draws him now).
     */
    fun host(seats: List<MechaSeat>, current: Int?): Pair<Int?, Int> {
        val display = if (current != null && seats.any { it.display == current }) current else seats.lastOrNull { it.active }?.display ?: current
        return display to seats.indexOfLast { it.active && it.display == display }
    }
}
