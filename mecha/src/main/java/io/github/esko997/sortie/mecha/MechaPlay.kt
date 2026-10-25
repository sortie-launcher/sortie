package io.github.esko997.sortie.mecha

import kotlin.math.max
import kotlin.random.Random

/**
 * What the companion may do with a declared element beyond standing by it. Only the host decides; a control is never used
 * whatever it is marked, and an element that is focused or being touched is left alone.
 */
enum class MechaProp {
    /** He pokes it from beside it (it must have a BESIDE spot he can stand at) and it jiggles. */
    POKE,

    /** Decorative: he stands under it and lifts it a few pixels, straining. It drops back the instant any input arrives. */
    LIFT
}

/** What a touch that went down on the mech has turned out to be so far ([MechaGesture]). */
internal enum class MechaTouch {
    /** Down on him, not moved past touch slop, not held long enough yet. */
    PENDING,

    /** Moved before the hold: a scroll or swipe that only started on him. Left to whatever is under him. */
    SWIPE,

    /** Held still for [MechaGesture.HOLD_MS]: he is picked up. Released now, it opens the Hangar. */
    HELD,

    /** Moved after the hold: he is carried. */
    CARRY,

    /** Released before the hold without moving: a pet. */
    PET,

    /** Released after holding still: the Hangar opens. */
    HANGAR,

    /** Released while carried: he is dropped. */
    DROP
}

/**
 * Classifies one finger that went down on the mech, from its positions and times alone (window pixels, uptime millis):
 * a quick tap is a pet, a still hold opens the Hangar on release, a hold then a move carries him, and a move before the
 * hold is a swipe that belongs to whatever is under him (so a scroll starting on him still scrolls).
 */
internal class MechaGesture(private val slop: Float, private val holdMs: Long = HOLD_MS) {
    var state = MechaTouch.PENDING
        private set
    private var x0 = 0f
    private var y0 = 0f
    private var t0 = 0L

    fun down(x: Float, y: Float, t: Long) {
        state = MechaTouch.PENDING
        x0 = x
        y0 = y
        t0 = t
    }

    /** Milliseconds until a still finger becomes a hold, from [t] (0: due). */
    fun holdIn(t: Long): Long = max(0L, t0 + holdMs - t)

    /** Time passed to [t] with the finger still: a hold once [holdMs] is up. */
    fun at(t: Long): MechaTouch {
        if (state == MechaTouch.PENDING && t - t0 >= holdMs) state = MechaTouch.HELD
        return state
    }

    fun move(x: Float, y: Float, t: Long): MechaTouch {
        at(t)
        val dx = x - x0
        val dy = y - y0
        val far = dx * dx + dy * dy > slop * slop
        if (far) {
            state = when (state) {
                MechaTouch.PENDING -> MechaTouch.SWIPE
                MechaTouch.HELD -> MechaTouch.CARRY
                else -> state
            }
        }
        return state
    }

    fun up(x: Float, y: Float, t: Long): MechaTouch {
        move(x, y, t)
        state = when (state) {
            MechaTouch.PENDING -> MechaTouch.PET
            MechaTouch.HELD -> MechaTouch.HANGAR
            MechaTouch.CARRY -> MechaTouch.DROP
            else -> state
        }
        return state
    }

    companion object {
        /** A still hold this long picks him up (released still: the Hangar). */
        const val HOLD_MS = 500L
    }
}

/** A window edge: the one facing the other display on a stacked dual-screen handheld. */
internal enum class MechaEdge { TOP, BOTTOM }

/** Where a carried mech ends up when let go. */
internal sealed interface MechaDrop {
    /** Across to the other display, entering at [fraction] of its width (where he was let go, proportionally). */
    data class Across(val fraction: Float) : MechaDrop

    /** Down to [at], the nearest place that qualifies here, with a little hop. */
    data class Land(val at: MechaPlacement) : MechaDrop

    /** Nowhere qualifies here: he drops out of sight until the layout offers a place. */
    data object Nowhere : MechaDrop
}

/** The short things he does now and then instead of a stroll. */
internal enum class MechaDo { NAP, SIT, POKE, LIFT }

/**
 * One vignette: he goes to [spot] (where he already stands, a qualifying spot, or for a lift the place under the element),
 * then does [kind] in [pose] (the seated placement for a sit). [prop] is the element poked or lifted; [toward] is the
 * side a poke reaches to (-1: start, 1: end).
 */
internal data class MechaVignette(val kind: MechaDo, val spot: MechaPlacement, val pose: MechaPlacement, val prop: String? = null, val toward: Int = 0)

/** Pure decisions for play: dropping a carried mech, and where each vignette fits. */
internal object MechaPlay {
    /** Frame rows a seated mech sinks below standing: the hips (the body's bottom rows) rest on the edge. */
    const val SIT_DROP = 16

    /** Frame row the raised hands reach in the arms-up frame: they touch the underside of a lifted element. */
    const val LIFT_REACH = 4

    /** Sprite pixels a lifted element rises at most. */
    const val LIFT_HEIGHT = 3

    /**
     * Where a mech let go with the finger at ([fingerX], [fingerY]) and his box left at [x], feet at [floor] lands: across
     * to the other display when the finger is within [edgeZone] of [toward] (the edge facing it) and [otherHasRoom]; else the
     * placement among [places] nearest his feet; else nowhere.
     */
    fun drop(
        fingerX: Float,
        fingerY: Float,
        x: Int,
        floor: Int,
        places: List<MechaPlacement>,
        stage: MechaRect,
        toward: MechaEdge?,
        edgeZone: Int,
        otherHasRoom: Boolean
    ): MechaDrop {
        if (toward != null && otherHasRoom && stage.width > 0) {
            val near = when (toward) {
                MechaEdge.TOP -> fingerY - stage.top <= edgeZone
                MechaEdge.BOTTOM -> stage.bottom - fingerY <= edgeZone
            }
            if (near) return MechaDrop.Across(((fingerX - stage.left) / stage.width).coerceIn(0f, 1f))
        }
        val nearest = places.minByOrNull { p ->
            val dx = (p.x - x).toLong()
            val dy = (p.floor - floor).toLong()
            dx * dx + dy * dy
        } ?: return MechaDrop.Nowhere
        return MechaDrop.Land(nearest)
    }

    /**
     * Seated on [anchor]'s top edge (a LEDGE or PEEK spot), as close to [preferX] as fits: hips on the edge, the upper body
     * above it inside [stage] and clear of every other element, the legs dangling over the anchor's own face (never over
     * anything else). Null when the anchor is not whole on screen or he does not fit.
     */
    fun sit(anchor: MechaElement, preferX: Int, scale: Int, stage: MechaRect, elements: Collection<MechaElement>): MechaPlacement? {
        val kind = anchor.kind ?: return null
        if (kind != MechaSpotKind.LEDGE && kind != MechaSpotKind.PEEK) return null
        if (!anchor.wholeOnScreen) return null
        val s = scale
        val a = anchor.bounds
        val edge = a.top
        val floor = edge + SIT_DROP * s
        // the whole art across lies over the anchor, so the dangling legs cover nothing else
        val minX = a.left - MechaBody.ART_LEFT * s
        val maxX = a.right - MechaBody.ART_RIGHT * s
        if (maxX < minX) return null
        val x = preferX.coerceIn(minX, maxX)
        val art = MechaBody.art(x, floor, s)
        if (!stage.contains(art)) return null
        if (elements.any { it !== anchor && it.solid && it.visible.intersects(art) }) return null
        if (!anchor.visible.contains(art.copy(top = edge))) return null
        return MechaPlacement(anchor.id, kind, x, floor, Int.MAX_VALUE, art)
    }

    /**
     * Whether [e] may be lifted now: the host marked it [MechaProp.LIFT], it is no control, whole on screen, not [focused]
     * and no finger in [touches] (window pixels) is on it.
     */
    fun canLift(e: MechaElement, focused: Set<String>, touches: Collection<Pair<Float, Float>>): Boolean =
        MechaProp.LIFT in e.props && !e.interactive && e.wholeOnScreen && e.id !in focused &&
            touches.none { (x, y) -> x >= e.visible.left && x < e.visible.right && y >= e.visible.top && y < e.visible.bottom }

    /**
     * Standing under [prop] with his raised hands ([LIFT_REACH]) at its bottom edge, centred under it or slid along it to
     * free space: the art inside [stage] and clear of every element but the prop (the hands tuck behind its bottom rows).
     */
    fun lift(prop: MechaElement, scale: Int, stage: MechaRect, elements: Collection<MechaElement>): MechaPlacement? {
        val s = scale
        val p = prop.bounds
        val floor = p.bottom + (MechaBody.FRAME - 1 - LIFT_REACH) * s
        val w = MechaBody.width(s)
        val centre = (p.left + p.right) / 2 - w / 2
        // the hands (the frame's middle half) stay under the element
        val minX = p.left - w / 4
        val maxX = p.right - w * 3 / 4
        if (maxX < minX) return null
        val step = 4 * s
        var offset = 0
        while (centre - offset >= minX || centre + offset <= maxX) {
            for (x in intArrayOf(centre - offset, centre + offset)) {
                if (x < minX || x > maxX) continue
                val art = MechaBody.art(x, floor, s)
                if (stage.contains(art) && elements.none { it !== prop && it.solid && it.visible.intersects(art) }) {
                    return MechaPlacement(prop.id, MechaSpotKind.ROOM, x, floor, Int.MAX_VALUE, art)
                }
            }
            offset += step
        }
        return null
    }

    /**
     * Every vignette that fits now. [at] is where he stands (null: nowhere, so none), [places] the places that qualify,
     * [focused] the ids of focused elements and [touches] the fingers down. A nap where he stands (not at a screen edge); a
     * sit on a LEDGE or PEEK anchor that is not focused; a poke from a BESIDE place at a [MechaProp.POKE] element that is no
     * control and not focused; a lift of any element [canLift] allows that he fits under.
     */
    fun vignettes(
        at: MechaPlacement?,
        places: List<MechaPlacement>,
        elements: Map<String, MechaElement>,
        scale: Int,
        stage: MechaRect,
        focused: Set<String>,
        touches: Collection<Pair<Float, Float>>
    ): List<MechaVignette> {
        if (at == null) return emptyList()
        val all = elements.values
        val out = ArrayList<MechaVignette>()
        if (!at.kind.edge) out += MechaVignette(MechaDo.NAP, at, at)
        val standing = listOf(at) + places.filter { it.spotId != at.spotId }
        for (p in standing) {
            val e = elements[p.spotId] ?: continue
            if (e.id in focused) continue
            when (p.kind) {
                MechaSpotKind.LEDGE, MechaSpotKind.PEEK -> sit(e, p.x, scale, stage, all)?.let { out += MechaVignette(MechaDo.SIT, p, it) }
                MechaSpotKind.BESIDE_END, MechaSpotKind.BESIDE_START -> if (MechaProp.POKE in e.props && !e.interactive) {
                    out += MechaVignette(MechaDo.POKE, p, p, e.id, if (p.kind == MechaSpotKind.BESIDE_END) -1 else 1)
                }
                else -> Unit
            }
        }
        for (e in all) {
            if (!canLift(e, focused, touches)) continue
            lift(e, scale, stage, all)?.let { out += MechaVignette(MechaDo.LIFT, it, it, e.id) }
        }
        return out
    }

    /** One of [options] at random, the props (poke, lift) a little likelier than sitting and napping; null when none. */
    fun choose(options: List<MechaVignette>, random: Random): MechaVignette? {
        if (options.isEmpty()) return null
        val weights = IntArray(options.size) { if (options[it].prop != null) 3 else 2 }
        var pick = random.nextInt(weights.sum())
        for (i in options.indices) {
            pick -= weights[i]
            if (pick < 0) return options[i]
        }
        return options.last()
    }
}
