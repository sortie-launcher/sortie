package io.github.esko997.sortie.mecha

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaRoamTest {
    private val stage = MechaRect(0, 0, 1240, 1000)

    private fun el(id: String, kind: MechaSpotKind?, l: Int, t: Int, r: Int, b: Int, visible: MechaRect? = null, interactive: Boolean = false) =
        MechaElement(id, kind, MechaRect(l, t, r, b), visible ?: MechaRect(l, t, r, b), interactive)

    private fun at(id: String, kind: MechaSpotKind, x: Int, floor: Int, clip: Int = Int.MAX_VALUE, scale: Int = 1) =
        MechaPlacement(id, kind, x, floor, clip, MechaRoam.exposedArt(x, floor, clip, scale))

    @Test
    fun ledgeStandsOnTheTopEdgeWithTheArtAboveIt() {
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val p = MechaRoam.place(bar, 2, stage, listOf(bar))!!
        assertEquals(900, p.floor)
        assertEquals(900, p.exposed.bottom)
        assertEquals(900 - 63 * 2, p.exposed.top)
        assertEquals(Int.MAX_VALUE, p.clip)
    }

    @Test
    fun aSpotWhoseEdgeIsScrolledPartlyOutOfViewDoesNotCount() {
        val tile = el("tile", MechaSpotKind.PEEK, 100, 200, 300, 400, visible = MechaRect(100, 250, 300, 400))
        assertNull(MechaRoam.place(tile, 1, stage, listOf(tile)))
        val gone = el("gone", MechaSpotKind.LEDGE, 0, 900, 1240, 960, visible = MechaRect(0, 0, 0, 0))
        assertNull(MechaRoam.place(gone, 1, stage, listOf(gone)))
    }

    @Test
    fun ledgeWithoutHeadroomBelowTheScreenTopDoesNotFit() {
        val row = el("row", MechaSpotKind.LEDGE, 0, 40, 600, 80)
        assertNull(MechaRoam.place(row, 1, stage, listOf(row)))
        assertNotNull(MechaRoam.place(row.copyAt(top = 70), 1, stage, listOf(row.copyAt(top = 70))))
    }

    @Test
    fun ledgeSlidesAlongToFreeSpaceAndRejectsWhenNoneIsLeft() {
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 400, 960)
        val blockLeft = el("tile", null, 0, 800, 250, 900)
        val p = MechaRoam.place(bar, 1, stage, listOf(bar, blockLeft), preferX = 0)!!
        assertTrue(p.exposed.left >= 250)
        assertFalse(p.exposed.intersects(blockLeft.visible))
        val blockAll = el("all", null, 0, 800, 400, 900)
        assertNull(MechaRoam.place(bar, 1, stage, listOf(bar, blockAll)))
    }

    @Test
    fun peekShowsOnlyTheHeadAboveTheEdgeAndHidesTheBody() {
        val tile = el("tile", MechaSpotKind.PEEK, 100, 300, 400, 600)
        val p = MechaRoam.place(tile, 2, stage, listOf(tile))!!
        assertEquals(300, p.clip)
        assertEquals(300 + (63 - MechaBody.PEEK_ROWS) * 2, p.floor)
        assertEquals(300 - MechaBody.PEEK_ROWS * 2, p.exposed.top)
        assertEquals(300, p.exposed.bottom)
        // the whole body behind the tile
        assertTrue(p.x + MechaBody.ART_LEFT * 2 >= 100 && p.x + MechaBody.ART_RIGHT * 2 <= 400)
    }

    @Test
    fun peekNeedsAnAnchorWideEnoughToHideTheBodyAndFreeSpaceForTheHead() {
        val narrow = el("narrow", MechaSpotKind.PEEK, 100, 300, 140, 600)
        assertNull(MechaRoam.place(narrow, 1, stage, listOf(narrow)))
        val tile = el("tile", MechaSpotKind.PEEK, 100, 300, 400, 600)
        val chips = el("chips", null, 0, 270, 1240, 290)
        assertNull(MechaRoam.place(tile, 1, stage, listOf(tile, chips)))
        val shortChips = el("chips", null, 0, 270, 150, 290)
        assertNotNull(MechaRoam.place(tile, 1, stage, listOf(tile, shortChips)))
    }

    @Test
    fun besideEndStandsAfterTheElementOnItsBottomLine() {
        val clock = el("clock", MechaSpotKind.BESIDE_END, 20, 20, 300, 100)
        val p = MechaRoam.place(clock, 1, stage, listOf(clock))!!
        assertEquals(100, p.floor)
        assertTrue(p.exposed.left >= 300)
        val buttons = el("buttons", null, 310, 20, 900, 100, interactive = true)
        assertNull(MechaRoam.place(clock, 1, stage, listOf(clock, buttons)))
    }

    @Test
    fun edgeSpotRevealsPartOfTheMechAndBacksOffAnObstacle() {
        val bar = el("edge", MechaSpotKind.EDGE_END, 0, 900, 1240, 960)
        val p = MechaRoam.place(bar, 1, stage, listOf(bar))!!
        assertEquals(1240, p.exposed.right)
        assertEquals(MechaBody.EDGE_REVEAL, p.exposed.width)
        val tile = el("tile", null, 1222, 820, 1240, 900)
        val q = MechaRoam.place(bar, 1, stage, listOf(bar, tile))
        assertTrue(q == null || !q.exposed.intersects(tile.visible))
        val wall = el("wall", null, 1200, 800, 1240, 900)
        assertNull(MechaRoam.place(bar, 1, stage, listOf(bar, wall)))
    }

    private fun heightMm(scale: Int, ydpi: Float) = MechaBody.FRAME * scale / ydpi * 25.4f

    @Test
    fun scaleComesFromThePhysicalSizeOfEachDisplay() {
        // AYN Thor: both panels report density 369 and ~320 real dpi (dumpsys display); top 1920x1080, bottom 1240x1080
        assertEquals(2, MechaRoam.physicalScale(ydpi = 320.842f, densityDpi = 369))
        // AYANEO Pocket DMG 1240x1080: density 360, 321.387 dpi (read from the device)
        assertEquals(2, MechaRoam.physicalScale(ydpi = 321.387f, densityDpi = 360))
        // Retroid Nova 1280x960 at 320 dpi
        assertEquals(2, MechaRoam.physicalScale(ydpi = 320f, densityDpi = 320))
        for (dpi in listOf(320.842f, 321.387f, 320f)) assertTrue(heightMm(2, dpi) in 9f..11f)
        // a dense phone gets a bigger multiple so he stays the same size in the hand
        val phone = MechaRoam.physicalScale(ydpi = 460f, densityDpi = 440)
        assertEquals(3, phone)
        assertTrue(heightMm(phone, 460f) in 9f..11f)
        // a display that reports a nonsense dpi falls back to its density bucket
        assertEquals(MechaRoam.physicalScale(480f, 480), MechaRoam.physicalScale(ydpi = 0f, densityDpi = 480))
        assertEquals(MechaRoam.physicalScale(480f, 480), MechaRoam.physicalScale(ydpi = 96f, densityDpi = 480))
    }

    @Test
    fun aSpotQualifiesOnlyWhereAtLeastFortyDpOfHimIsShowingAndFreeOfControls() {
        val tap = 92 // 40 dp at the Thor's density 2.30625
        // a peek shows head and shoulders: enough rows for a tappable height, not just the antenna
        val tile = el("tile", MechaSpotKind.PEEK, 300, 500, 700, 800, interactive = true)
        val p = MechaRoam.place(tile, 2, stage, listOf(tile), minTap = tap)!!
        assertTrue(p.exposed.height >= tap)
        assertEquals(500, p.exposed.bottom)
        assertNotNull(MechaRoam.hitTarget(p, 2, tap, stage, listOf(tile)))
        // only 47 px between the chips above and the tile: a head-only peek fits, a tappable one does not
        val chips = el("chips", null, 0, 400, 1240, 453, interactive = true)
        assertNotNull(MechaRoam.place(tile, 2, stage, listOf(tile, chips)))
        assertNull(MechaRoam.place(tile, 2, stage, listOf(tile, chips), minTap = tap))
        // standing visible behind a transparent list row is not enough: the row would take the tap
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val row = MechaElement("row", null, MechaRect(0, 700, 1240, 900), MechaRect(0, 700, 1240, 900), interactive = true, solid = false)
        assertNotNull(MechaRoam.place(bar, 2, stage, listOf(bar, row)))
        assertNull(MechaRoam.place(bar, 2, stage, listOf(bar, row), minTap = tap))
        // at 1x his art is 54 px wide: never 92 px of him to tap, so nothing qualifies
        assertTrue(MechaRoam.fitting(listOf(bar), 1, stage, tap).isEmpty())
        assertEquals(1, MechaRoam.fitting(listOf(bar), 2, stage, tap).size)
    }

    @Test
    fun withNowhereTappableHeGoesToTheHomeBaseElseHides() {
        val tap = 90
        val s = 2
        // a Home-like bottom: the hint bar row holds the reserved base at its end, the tiles end right above that row
        val hints = el("hints", MechaSpotKind.LEDGE, 0, 900, 1112, 1000)
        val tiles = el("tiles", null, 0, 400, 1240, 1000 - MechaBody.height(s), interactive = true)
        val base = el("base", MechaSpotKind.BASE, 1112, 1000 - MechaBody.height(s), 1240, 1000)
        val p = MechaRoam.places(listOf(hints, tiles, base), s, stage, tap).single()
        assertEquals("base", p.spotId)
        assertTrue(base.bounds.contains(p.exposed))
        assertNotNull(MechaRoam.hitTarget(p, s, tap, stage, listOf(hints, tiles, base)))
        // the base is only a fallback: with a real spot qualifying, he goes there instead
        val roomy = el("roomy", MechaSpotKind.ROOM, 0, 100, 600, 380)
        assertEquals(listOf("roomy"), MechaRoam.places(listOf(hints, tiles, base, roomy), s, stage, tap).map { it.spotId })
        // a base too small for him, or covered, does not hold him: nowhere at all, so he hides
        val small = el("base", MechaSpotKind.BASE, 1180, 960, 1240, 1000)
        assertTrue(MechaRoam.places(listOf(hints, tiles, small), s, stage, tap).isEmpty())
        val covered = el("toast", null, 1100, 900, 1240, 960)
        assertTrue(MechaRoam.places(listOf(hints, tiles, base, covered), s, stage, tap).isEmpty())
        // no base declared (a hidden companion reserves none): hide too
        assertTrue(MechaRoam.places(listOf(hints, tiles), s, stage, tap).isEmpty())
    }

    @Test
    fun whenThingsMoveAroundAStandingMechHisTapTargetAndBubbleFollow() {
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val p = MechaRoam.place(bar, 2, stage, listOf(bar), preferX = 500)!!
        val (hit0, none) = MechaRoam.refresh(p, 2, 40, null, 4, stage, listOf(bar))
        assertNotNull(hit0)
        assertNull(none)
        // he stays put; a bubble is up beside him on the right and stays there while nothing moves
        val bubble = MechaRoam.bubble(p, 200, 40, 4, stage, listOf(bar))!!
        assertTrue(bubble.left >= p.exposed.right)
        assertEquals(bubble, MechaRoam.refresh(p, 2, 40, bubble, 4, stage, listOf(bar)).second)
        // a control slides into the frame margin beside him and under the bubble: the target shrinks to his art, the bubble
        // moves to the other side
        val button = el("button", null, p.exposed.right + 2, 800, 1240, 900, interactive = true)
        val (hit1, bubble1) = MechaRoam.refresh(p, 2, 40, bubble, 4, stage, listOf(bar, button))
        assertEquals(p.exposed, hit1)
        assertNotNull(bubble1)
        assertTrue(bubble1!!.right <= p.exposed.left)
        // a control over him and everything around: no target, no bubble
        val wall = el("wall", null, 0, 0, 1240, 900, interactive = true)
        val (hit2, bubble2) = MechaRoam.refresh(p, 2, 40, bubble1, 4, stage, listOf(bar, wall))
        assertNull(hit2)
        assertNull(bubble2)
    }

    @Test
    fun aTransparentRowLetsHimShowButKeepsTheTapForItself() {
        val bar = el("bar", MechaSpotKind.LEDGE, 0, 900, 1240, 960)
        val row = MechaElement("row", null, MechaRect(0, 800, 1240, 900), MechaRect(0, 800, 1240, 900), interactive = true, solid = false)
        val p = MechaRoam.place(bar, 1, stage, listOf(bar, row))
        assertNotNull(p)
        assertNull(MechaRoam.hitTarget(p!!, 1, 20, stage, listOf(bar, row)))
        assertNotNull(MechaRoam.bubble(p, 100, 30, 2, stage, listOf(bar, row)))
    }

    @Test
    fun scaleShrinksUntilTwoSpotsFitAndEdgesDoNotCount() {
        // 100 px between the header and the hint bar: a 2x mech (126 px of art) fits nowhere, a 1x one fits twice
        val header = el("header", MechaSpotKind.BESIDE_END, 0, 0, 300, 100)
        val hints = el("hints", MechaSpotKind.LEDGE, 0, 200, 1240, 260)
        val edge = el("edge", MechaSpotKind.EDGE_START, 0, 200, 1240, 260)
        val wall = el("wall", null, 0, 100, 1240, 110)
        val all = listOf(header, hints, edge, wall)
        assertEquals(1, MechaRoam.chooseScale(all, stage, 2))
        assertEquals(2, MechaRoam.fitting(all, 1, stage).size)
        val roomy = listOf(el("a", MechaSpotKind.LEDGE, 0, 500, 600, 520), el("b", MechaSpotKind.LEDGE, 700, 500, 1240, 520))
        assertEquals(2, MechaRoam.chooseScale(roomy, stage, 2))
    }

    @Test
    fun nextSpotNeverRepeatsAndAvoidsThePreviousOneWhileOthersFit() {
        val a = at("a", MechaSpotKind.LEDGE, 10, 500)
        val b = at("b", MechaSpotKind.LEDGE, 400, 500)
        val c = at("c", MechaSpotKind.LEDGE, 800, 300)
        val random = Random(7)
        repeat(200) {
            val next = MechaRoam.chooseNext(listOf(a, b, c), current = "a", previous = "b", from = a, stageWidth = 1240, random = random)
            assertEquals("c", next?.spotId)
        }
        assertEquals("b", MechaRoam.chooseNext(listOf(a, b), current = "a", previous = "b", from = a, stageWidth = 1240, random = random)?.spotId)
        assertNull(MechaRoam.chooseNext(listOf(a), current = "a", previous = null, from = a, stageWidth = 1240, random = random))
    }

    @Test
    fun nextSpotFavoursTheSameFloor() {
        val a = at("a", MechaSpotKind.LEDGE, 10, 500)
        val same = at("same", MechaSpotKind.LEDGE, 300, 500)
        val other = at("other", MechaSpotKind.LEDGE, 1000, 100)
        val random = Random(1)
        val picks = (1..1000).map { MechaRoam.chooseNext(listOf(a, same, other), "a", null, a, 1240, random)!!.spotId }
        assertTrue(picks.count { it == "same" } > picks.count { it == "other" } * 2)
        assertTrue(picks.contains("other"))
    }

    @Test
    fun movesWalkAlongAFloorHopNearbyAndSinkOtherwise() {
        val s = 1
        val a = at("a", MechaSpotKind.LEDGE, 100, 500)
        assertEquals(MechaMove.WALK, MechaRoam.planMove(a, at("b", MechaSpotKind.LEDGE, 400, 500), s, 1240))
        assertEquals(MechaMove.SINK, MechaRoam.planMove(a, at("far", MechaSpotKind.LEDGE, 1100, 500), s, 1240))
        assertEquals(MechaMove.HOP, MechaRoam.planMove(a, at("up", MechaSpotKind.BESIDE_END, 150, 470), s, 1240))
        assertEquals(MechaMove.SINK, MechaRoam.planMove(a, at("high", MechaSpotKind.LEDGE, 150, 100), s, 1240))
        // a peek never walks or hops out from behind its element
        assertEquals(MechaMove.SINK, MechaRoam.planMove(at("p", MechaSpotKind.PEEK, 100, 539, clip = 500), a, s, 1240))
        assertEquals(
            MechaMove.WALK,
            MechaRoam.planMove(at("p", MechaSpotKind.PEEK, 100, 539, clip = 500), at("q", MechaSpotKind.PEEK, 300, 539, clip = 500), s, 1240)
        )
    }

    @Test
    fun bubbleGoesBesideTheMechOnlyWhereThereIsEmptySpace() {
        val p = at("a", MechaSpotKind.LEDGE, 100, 500)
        val right = MechaRoam.bubble(p, 200, 40, 4, stage, emptyList())!!
        assertTrue(right.left >= p.exposed.right)
        val blockRight = el("r", null, p.exposed.right, 0, 1240, 1000)
        val left = MechaRoam.bubble(p, 50, 40, 4, stage, listOf(blockRight))!!
        assertTrue(left.right <= p.exposed.left)
        val blockLeft = el("l", null, 0, 0, p.exposed.left, 1000)
        val above = MechaRoam.bubble(p, 50, 40, 4, stage, listOf(blockRight, blockLeft))!!
        assertTrue(above.bottom <= p.exposed.top)
        val blockAbove = el("t", null, 0, 0, 1240, p.exposed.top)
        assertNull(MechaRoam.bubble(p, 50, 40, 4, stage, listOf(blockRight, blockLeft, blockAbove)))
    }

    @Test
    fun tapTargetCoversTheExposedMechButNeverAControl() {
        val p = at("a", MechaSpotKind.LEDGE, 100, 500, scale = 2).let { it.copy(exposed = MechaRoam.exposedArt(100, 500, Int.MAX_VALUE, 2)) }
        val wide = MechaRoam.hitTarget(p, 2, 40, stage, emptyList())!!
        assertEquals(100, wide.left)
        assertEquals(228, wide.right)
        // a button touching the frame's margin: only the art itself
        val button = el("button", null, 222, 400, 400, 500, interactive = true)
        val narrow = MechaRoam.hitTarget(p, 2, 40, stage, listOf(button))!!
        assertEquals(p.exposed, narrow)
        val over = el("over", null, 150, 400, 400, 500, interactive = true)
        assertNull(MechaRoam.hitTarget(p, 2, 40, stage, listOf(over)))
        // a peek's head is too short for a 60 px target at 1x
        val peek = at("p", MechaSpotKind.PEEK, 100, 539, clip = 500)
        assertNull(MechaRoam.hitTarget(peek, 1, 60, stage, emptyList()))
    }

    @Test
    fun theMechStaysOnHisDisplayAndOnlyOneWindowDrawsHim() {
        // nav on 0, companion on 4; he is on 0 and nav is paused for a game: nobody draws him, he stays on 0
        val paused = listOf(MechaSeat(0, false), MechaSeat(4, true))
        assertEquals(0 to -1, MechaRoam.host(paused, 0))
        // an X swap leaves two windows on display 0 for a moment: only the latest active one draws him
        val swap = listOf(MechaSeat(0, true), MechaSeat(4, true), MechaSeat(0, true))
        assertEquals(0 to 2, MechaRoam.host(swap, 0))
        // his display has no window left: the latest active one adopts him
        assertEquals(4 to 0, MechaRoam.host(listOf(MechaSeat(4, true)), 0))
        // first window ever
        assertEquals(0 to 0, MechaRoam.host(listOf(MechaSeat(0, true)), null))
        assertEquals(null to -1, MechaRoam.host(listOf(MechaSeat(0, false)), null))
    }

    @Test
    fun crossingOverNeedsRoomThereAndTimeHere() {
        val random = Random(3)
        assertFalse((1..100).any { MechaRoam.crossOver(5, otherHasRoom = false, random = random) })
        assertFalse((1..100).any { MechaRoam.crossOver(1, otherHasRoom = true, random = random) })
        val crossings = (1..300).count { MechaRoam.crossOver(2, otherHasRoom = true, random = random) }
        assertTrue(crossings in 60..140)
        assertNotEquals(0, crossings)
    }

    private fun MechaElement.copyAt(top: Int): MechaElement {
        val r = MechaRect(bounds.left, top, bounds.right, top + bounds.height)
        return MechaElement(id, kind, r, r, interactive)
    }
}
