package io.github.esko997.sortie.mecha

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaPlayTest {
    private val stage = MechaRect(0, 0, 1240, 1000)
    private val slop = 16f

    private fun el(
        id: String,
        kind: MechaSpotKind?,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        interactive: Boolean = false,
        props: Set<MechaProp> = emptySet(),
        visible: MechaRect? = null
    ) = MechaElement(id, kind, MechaRect(l, t, r, b), visible ?: MechaRect(l, t, r, b), interactive, props = props)

    private fun at(id: String, kind: MechaSpotKind, x: Int, floor: Int, scale: Int = 2) =
        MechaPlacement(id, kind, x, floor, Int.MAX_VALUE, MechaRoam.exposedArt(x, floor, Int.MAX_VALUE, scale))

    // ---- gestures ----

    @Test
    fun aQuickStillTapIsAPet() {
        val g = MechaGesture(slop)
        g.down(100f, 100f, 0)
        assertEquals(MechaTouch.PENDING, g.move(105f, 103f, 120))
        assertEquals(MechaTouch.PET, g.up(106f, 104f, 200))
    }

    @Test
    fun aStillHoldPicksHimUpAndReleasedStillOpensTheHangar() {
        val g = MechaGesture(slop)
        g.down(100f, 100f, 1_000)
        assertEquals(MechaGesture.HOLD_MS, g.holdIn(1_000))
        assertEquals(MechaTouch.PENDING, g.at(1_000 + MechaGesture.HOLD_MS - 1))
        assertEquals(MechaTouch.HELD, g.at(1_000 + MechaGesture.HOLD_MS))
        assertEquals(0, g.holdIn(1_000 + MechaGesture.HOLD_MS + 50))
        // wobbling inside the slop keeps it a hold
        assertEquals(MechaTouch.HELD, g.move(108f, 95f, 1_700))
        assertEquals(MechaTouch.HANGAR, g.up(108f, 95f, 1_900))
    }

    @Test
    fun aHoldWithoutATimerTickStillCountsByEventTime() {
        val g = MechaGesture(slop)
        g.down(100f, 100f, 0)
        // the release arrives after the hold time although no timer fired in between
        assertEquals(MechaTouch.HANGAR, g.up(101f, 101f, MechaGesture.HOLD_MS + 10))
    }

    @Test
    fun aHoldThenAMoveCarriesHimAndReleaseDropsHim() {
        val g = MechaGesture(slop)
        g.down(100f, 100f, 0)
        assertEquals(MechaTouch.HELD, g.at(MechaGesture.HOLD_MS))
        assertEquals(MechaTouch.CARRY, g.move(160f, 220f, MechaGesture.HOLD_MS + 100))
        // a carry stays a carry wherever the finger goes, even back to where it started
        assertEquals(MechaTouch.CARRY, g.move(100f, 100f, MechaGesture.HOLD_MS + 200))
        assertEquals(MechaTouch.DROP, g.up(100f, 100f, MechaGesture.HOLD_MS + 300))
    }

    @Test
    fun aMoveBeforeTheHoldIsASwipeForWhateverIsUnderHim() {
        val g = MechaGesture(slop)
        g.down(100f, 100f, 0)
        assertEquals(MechaTouch.SWIPE, g.move(100f, 100f + slop + 1, 80))
        // once a swipe, never a hold or a carry, however long the finger stays down
        assertEquals(MechaTouch.SWIPE, g.at(MechaGesture.HOLD_MS * 4))
        assertEquals(MechaTouch.SWIPE, g.move(300f, 300f, MechaGesture.HOLD_MS * 4))
        assertEquals(MechaTouch.SWIPE, g.up(300f, 300f, MechaGesture.HOLD_MS * 5))
    }

    @Test
    fun theSlopIsARadius() {
        val g = MechaGesture(slop)
        g.down(0f, 0f, 0)
        // 11 across and 11 down is about 15.6 from the start: still inside a 16 px slop
        assertEquals(MechaTouch.PENDING, g.move(11f, 11f, 10))
        assertEquals(MechaTouch.SWIPE, g.move(12f, 12f, 20))
    }

    // ---- drop ----

    private val ledge = at("bar", MechaSpotKind.LEDGE, 400, 900)
    private val peek = at("tile", MechaSpotKind.PEEK, 100, 300)

    @Test
    fun aDropLandsAtTheNearestQualifyingPlace() {
        val d = MechaPlay.drop(500f, 820f, 380, 880, listOf(peek, ledge), stage, null, 96, otherHasRoom = false)
        assertEquals(MechaDrop.Land(ledge), d)
        val up = MechaPlay.drop(140f, 200f, 120, 260, listOf(peek, ledge), stage, null, 96, otherHasRoom = false)
        assertEquals(MechaDrop.Land(peek), up)
        assertEquals(MechaDrop.Nowhere, MechaPlay.drop(1f, 1f, 0, 0, emptyList(), stage, null, 96, otherHasRoom = false))
    }

    @Test
    fun aDropNearTheEdgeFacingTheOtherDisplayGoesAcross() {
        val places = listOf(peek, ledge)
        // the lower screen: its top edge faces the upper one
        val across = MechaPlay.drop(620f, 40f, 590, 150, places, stage, MechaEdge.TOP, 96, otherHasRoom = true)
        assertTrue(across is MechaDrop.Across)
        assertEquals(0.5f, (across as MechaDrop.Across).fraction, 0.001f)
        // at the edge zone's limit still across, just past it a landing
        assertTrue(MechaPlay.drop(620f, 96f, 590, 150, places, stage, MechaEdge.TOP, 96, true) is MechaDrop.Across)
        assertTrue(MechaPlay.drop(620f, 97f, 590, 150, places, stage, MechaEdge.TOP, 96, true) is MechaDrop.Land)
        // the upper screen: its bottom edge faces down
        assertTrue(MechaPlay.drop(10f, 990f, 0, 990, places, stage, MechaEdge.BOTTOM, 96, true) is MechaDrop.Across)
        // the far edge never hands him over
        assertTrue(MechaPlay.drop(620f, 990f, 590, 990, places, stage, MechaEdge.TOP, 96, true) is MechaDrop.Land)
        // no other display up (or no room there): he lands here
        assertTrue(MechaPlay.drop(620f, 40f, 590, 150, places, stage, MechaEdge.TOP, 96, otherHasRoom = false) is MechaDrop.Land)
        assertTrue(MechaPlay.drop(620f, 40f, 590, 150, places, stage, null, 96, otherHasRoom = true) is MechaDrop.Land)
        // the fraction stays inside the screen when the finger left it
        assertEquals(1f, (MechaPlay.drop(2000f, 0f, 0, 0, places, stage, MechaEdge.TOP, 96, true) as MechaDrop.Across).fraction, 0f)
    }

    // ---- vignettes ----

    @Test
    fun aSitPutsTheHipsOnTheEdgeAndTheLegsOverTheAnchorOnly() {
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val p = MechaPlay.sit(bar, 400, 2, stage, listOf(bar))!!
        assertEquals(900 + MechaPlay.SIT_DROP * 2, p.floor)
        assertEquals(400, p.x)
        assertTrue(p.exposed.top < 900 && p.exposed.bottom > 900)
        // something solid right above the edge where he would sit: no room
        val sign = el("sign", null, 300, 840, 600, 899)
        assertNull(MechaPlay.sit(bar, 400, 2, stage, listOf(bar, sign)))
        // too thin to take the dangling legs
        val thin = el("thin", MechaSpotKind.LEDGE, 0, 900, 1240, 910)
        assertNull(MechaPlay.sit(thin, 400, 2, stage, listOf(thin)))
        // beside spots and obstacles are no seats
        assertNull(MechaPlay.sit(el("b", MechaSpotKind.BESIDE_END, 0, 900, 1240, 960), 400, 2, stage, emptyList()))
        // a tile partly scrolled away is no seat either
        val cut = el("tile", MechaSpotKind.PEEK, 100, 300, 400, 600, visible = MechaRect(100, 350, 400, 600))
        assertNull(MechaPlay.sit(cut, 120, 2, stage, listOf(cut)))
    }

    @Test
    fun onlyAnUnfocusedUntouchedDecorativePropCanBeLifted() {
        val battery = el("battery", MechaSpotKind.BESIDE_END, 40, 40, 160, 100, props = setOf(MechaProp.LIFT))
        assertTrue(MechaPlay.canLift(battery, emptySet(), emptyList()))
        // never a control, whatever the host marked
        assertFalse(MechaPlay.canLift(el("bell", null, 40, 40, 160, 100, interactive = true, props = setOf(MechaProp.LIFT)), emptySet(), emptyList()))
        // not marked: never
        assertFalse(MechaPlay.canLift(el("plain", null, 40, 40, 160, 100), emptySet(), emptyList()))
        assertFalse(MechaPlay.canLift(el("poke", null, 40, 40, 160, 100, props = setOf(MechaProp.POKE)), emptySet(), emptyList()))
        // focused (it or something in it), or a finger on it
        assertFalse(MechaPlay.canLift(battery, setOf("battery"), emptyList()))
        assertFalse(MechaPlay.canLift(battery, emptySet(), listOf(100f to 70f)))
        assertTrue(MechaPlay.canLift(battery, emptySet(), listOf(500f to 70f)))
        // partly off screen
        val cut = el("cut", null, 40, 40, 160, 100, props = setOf(MechaProp.LIFT), visible = MechaRect(40, 60, 160, 100))
        assertFalse(MechaPlay.canLift(cut, emptySet(), emptyList()))
    }

    @Test
    fun aLiftStandsUnderThePropWithTheHandsAtItsBottomEdge() {
        val battery = el("battery", MechaSpotKind.BESIDE_END, 40, 40, 160, 100, props = setOf(MechaProp.LIFT))
        val p = MechaPlay.lift(battery, 2, stage, listOf(battery))!!
        assertEquals(100, MechaBody.frameTop(p.floor, 2) + MechaPlay.LIFT_REACH * 2)
        assertTrue(p.exposed.left < 160 && p.exposed.right > 40)
        // something solid underneath: no room
        val panel = el("panel", null, 0, 150, 400, 400)
        assertNull(MechaPlay.lift(battery, 2, stage, listOf(battery, panel)))
        // too close to the bottom of the screen
        val low = el("low", null, 40, 900, 160, 960, props = setOf(MechaProp.LIFT))
        assertNull(MechaPlay.lift(low, 2, stage, listOf(low)))
    }

    @Test
    fun vignettesOfferOnlyWhatTheHostAllowsAndNothingFocused() {
        val battery = el("battery", MechaSpotKind.BESIDE_END, 40, 40, 160, 100, props = setOf(MechaProp.POKE, MechaProp.LIFT))
        val bell = el("bell", MechaSpotKind.BESIDE_START, 1080, 40, 1200, 100, interactive = true, props = setOf(MechaProp.POKE))
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val elements = linkedMapOf("battery" to battery, "bell" to bell, "bar" to bar)
        val beside = at("battery", MechaSpotKind.BESIDE_END, 160, 100)
        val bellSpot = at("bell", MechaSpotKind.BESIDE_START, 960, 100)
        val onBar = at("bar", MechaSpotKind.LEDGE, 400, 900)
        val all = MechaPlay.vignettes(beside, listOf(beside, bellSpot, onBar), elements, 2, stage, emptySet(), emptyList())
        val kinds = all.map { it.kind to it.prop }.toSet()
        assertTrue(MechaDo.NAP to null in kinds)
        assertTrue(MechaDo.POKE to "battery" in kinds)
        assertTrue(MechaDo.LIFT to "battery" in kinds)
        assertTrue(MechaDo.SIT to null in kinds)
        // the bell is a control: never poked
        assertFalse(all.any { it.prop == "bell" })
        // a poke from after the element reaches back towards its start
        assertEquals(-1, all.first { it.kind == MechaDo.POKE }.toward)
        // focused: no poke, no lift, no sit on it
        val focused = MechaPlay.vignettes(beside, listOf(beside, onBar), elements, 2, stage, setOf("battery", "bar"), emptyList())
        assertEquals(listOf(MechaDo.NAP), focused.map { it.kind })
        // a finger on the battery: no lift
        assertFalse(MechaPlay.vignettes(beside, listOf(beside), elements, 2, stage, emptySet(), listOf(80f to 70f)).any { it.kind == MechaDo.LIFT })
        // nowhere to stand: nothing
        assertTrue(MechaPlay.vignettes(null, listOf(onBar), elements, 2, stage, emptySet(), emptyList()).isEmpty())
        assertNotNull(MechaPlay.choose(all, Random(1)))
        assertNull(MechaPlay.choose(emptyList(), Random(1)))
    }
}
