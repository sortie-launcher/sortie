package io.github.esko997.sortie.mecha

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * How a declared spot places the mech against the element that declares it (its anchor). Every declared element, spot or not,
 * is also an obstacle for the others: a spot counts only where the visible part of the mech stays clear of all of them.
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

    /** At the screen's start edge, partly off screen, standing on the anchor's top edge: the way in and out of the screen. */
    EDGE_START,

    /** Like [EDGE_START] at the end edge. */
    EDGE_END,

    /**
     * The host's reserved home base: empty space the size of the mech box ([io.github.esko997.sortie.mecha.ui.mechaBaseSize])
     * that nothing else uses. He stands inside it on its bottom edge, only when no other spot qualifies.
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
}

/**
 * A declared element as last laid out: [bounds] unclipped, [visible] after its ancestors' clipping (a scrolled-away tile is
 * empty). [kind] null: only an obstacle. [interactive]: a control, which a tap target must never cover. [solid]: something
 * drawn over its whole bounds, which the mech and his bubbles keep clear of; a transparent row that only takes taps is not.
 * [props]: what he may do with it besides standing by it ([MechaPlay]).
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

/** How the mech gets from one spot to another. */
internal enum class MechaMove { WALK, HOP, SINK }

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
     * art stays inside [stage] and clear of every other element, or null when it does not fit anywhere along the spot. With
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
            else -> return null
        }
        if (maxX < minX) return null
        val start = (preferX ?: ((minX + maxX) / 2)).coerceIn(minX, maxX)
        val step = SEARCH_STEP * s
        var offset = 0
        while (start - offset >= minX || start + offset <= maxX) {
            for (x in if (offset == 0) intArrayOf(start) else intArrayOf(start + offset, start - offset)) {
                if (x < minX || x > maxX) continue
                val exposed = exposedArt(x, floor, clip, s)
                if (!stage.contains(exposed) || !clear(exposed, spot, elements)) continue
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
            if (!exposed.isEmpty && clear(exposed, spot, elements)) return MechaPlacement(spot.id, kind, x, floor, Int.MAX_VALUE, exposed)
            reveal -= 4
        }
        return null
    }

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

    private fun clear(r: MechaRect, anchor: MechaElement, elements: Collection<MechaElement>): Boolean =
        elements.none { it !== anchor && it.solid && it.visible.intersects(r) }

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
     * Where he may stand now: every spot that qualifies (fits and is tappable), else the home base alone, else nowhere (he
     * hides; the Hangar header button stays the way in). Never somewhere he shows but cannot be tapped.
     */
    fun places(elements: Collection<MechaElement>, scale: Int, stage: MechaRect, minTap: Int, preferX: ((MechaElement) -> Int?)? = null): List<MechaPlacement> =
        fitting(elements, scale, stage, minTap, preferX).ifEmpty { listOfNotNull(base(elements, scale, stage, minTap)) }

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
     * The next spot among [options]: never [current], and not [previous] while anything else fits. Spots on the same floor and
     * nearby ones are likelier (a walk reads better than a jump), but anything that fits can come up.
     */
    fun chooseNext(
        options: List<MechaPlacement>,
        current: String?,
        previous: String?,
        from: MechaPlacement?,
        stageWidth: Int,
        random: Random
    ): MechaPlacement? {
        val away = options.filter { it.spotId != current }
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
     * Walk along a shared floor that is not too far (half the screen); a hop for a small step up or down nearby; otherwise he
     * sinks out of sight at one spot and rises at the other. A peek only walks to another peek on the same edge, so the body
     * stays hidden all the way.
     */
    fun planMove(from: MechaPlacement, to: MechaPlacement, scale: Int, stageWidth: Int): MechaMove {
        val dx = abs(to.x - from.x)
        val dy = abs(to.floor - from.floor)
        val peeks = (from.kind == MechaSpotKind.PEEK) to (to.kind == MechaSpotKind.PEEK)
        if (dy == 0 && dx <= stageWidth / 2 && peeks.first == peeks.second && from.clip == to.clip) return MechaMove.WALK
        if (!peeks.first && !peeks.second && dy <= 40 * scale && dx <= 96 * scale) return MechaMove.HOP
        return MechaMove.SINK
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
        return candidates.firstOrNull { r -> stage.contains(r) && elements.none { it.solid && it.visible.intersects(r) } }
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
            stage.contains(bubble) && elements.none { it.solid && it.visible.intersects(bubble) } -> bubble
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
