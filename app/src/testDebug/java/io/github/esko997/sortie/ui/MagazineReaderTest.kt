package io.github.esko997.sortie.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.testing.BuiltinActivityRule
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.Controller.Companion.L2
import io.github.esko997.sortie.testing.Controller.Companion.R2
import io.github.esko997.sortie.testing.Controller.Companion.RIGHT
import io.github.esko997.sortie.testing.Controller.Companion.SELECT
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Magazines app on the Thor's top screen, read with the controller: the fixture's long article ("Pictures of Every
 * Shape": a lead image, sixteen numbered paragraphs, a portrait and a wide figure), opened the way a game page's "Featured
 * in" opens it, with the navigation running underneath. Where the reader is is measured as which numbered paragraph is the
 * first one fully on screen, and at what height.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP)
class MagazineReaderTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    @get:Rule(order = 3)
    val reader = BuiltinActivityRule(BuiltinApp.MAGAZINES)

    private val pad = Controller()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(text: String) = compose.waitUntil(10_000) { shown(text) }

    /** Opens the article the way a game page's "Featured in" does, once the navigation has indexed the card, and waits for it. */
    private fun openArticle() {
        await("6 games")
        reader.open(ReaderLocation("fixture-special", "long-read"))
        await("Pictures of Every Shape")
    }

    /** The first numbered paragraph whose top is on screen and its top edge in px; paragraph 0 while none is. */
    private fun position(): Pair<Int, Float> {
        compose.waitForIdle()
        val height = reader.activity.window.decorView.height
        return (1..16).firstNotNullOfOrNull { n ->
            compose.onAllNodesWithText("Paragraph $n.", substring = true).fetchSemanticsNodes()
                .firstOrNull()?.positionInRoot?.y?.takeIf { it >= 0f && it < height }?.let { n to it }
        } ?: (0 to 0f)
    }

    /** Scrolls down with the D-pad until paragraph [n] is the first paragraph on screen and [until] holds. */
    private fun scrollTo(n: Int, until: () -> Boolean = { true }) {
        pad.press(DOWN) // leaves touch mode; the reader already holds the focus
        repeat(60) {
            if (position().first >= n && until()) return
            pad.press(DOWN)
        }
        error("D-pad never scrolled to paragraph $n; at ${position()}")
    }

    @Test
    fun readingPositionSurvivesActivityRecreation() {
        openArticle()
        scrollTo(6)
        val before = position()
        reader.recreate()
        await("Paragraph ${before.first}.")
        assertEquals("first paragraph on screen and its top (px) after recreation", before, position())
    }

    @Test
    fun r2OpensThePictureInViewAndBClosesItWithTheArticleWhereItWas() {
        openArticle()
        scrollTo(3) { shown("view picture") } // the portrait figure follows paragraph 4
        val before = position()
        pad.press(R2)
        compose.waitUntil(5_000) { shown("of 3") }
        assertTrue("the viewer shows the portrait figure, second of the three pictures", shown("2 of 3") && shown("Hide caption"))
        pad.press(B)
        compose.waitUntil(5_000) { !shown("of 3") }
        assertEquals("article position after closing the viewer", before, position())
        pad.press(DOWN)
        assertNotEquals("the D-pad scrolls the article again", before, position())
    }

    private fun described(text: String) = compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * The fixture's short note holds a three-panel picture sequence. L2 browses it, → chooses the second panel, R2 opens that
     * panel (not the first) in the viewer, B closes the viewer and then leaves browsing without leaving the article; the chosen
     * panel survives recreation, browsing does not.
     */
    @Test
    fun l2BrowsesASequenceAndR2OpensTheChosenPanel() {
        await("6 games")
        reader.open(ReaderLocation("fixture-special", "short-note"))
        await("Panel one")
        pad.press(DOWN) // the controller in use, as on the device
        compose.waitUntil(5_000) { shown("browse images") }
        assertTrue("the sequence starts on its first panel", shown("1 of 3"))
        pad.press(L2)
        compose.waitUntil(5_000) { shown("done browsing") }
        pad.press(RIGHT)
        compose.waitUntil(5_000) { shown("2 of 3") }
        pad.press(R2)
        compose.waitUntil(5_000) { described("Panel two") }
        assertFalse("the viewer opened the chosen panel", described("Panel one"))
        pad.press(B)
        compose.waitUntil(5_000) { !described("Panel two") }
        assertTrue("closing the viewer keeps browsing", shown("done browsing"))
        pad.press(B)
        compose.waitUntil(5_000) { !shown("done browsing") }
        assertFalse("B left browsing, not the article", reader.activity.isFinishing)
        assertTrue("the sequence is offered again", shown("browse images") && shown("2 of 3"))
        reader.recreate()
        await("2 of 3")
        assertFalse("browsing is never restored by itself", shown("done browsing"))
    }

    /**
     * Drags the short note's sequence row left by [fraction] of its width (right to left) and lets go without a fling: the
     * pointer rests before it is lifted, so only the row's own settling moves it on.
     */
    private fun dragSequence(fraction: Float, rightToLeft: Boolean = true) {
        val row = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
            hasAnyDescendant(hasContentDescription("Picture 1 of 3"))
        compose.onNode(row).performTouchInput {
            val from = if (rightToLeft) 0.5f + fraction / 2 else 0.5f - fraction / 2
            val dx = (if (rightToLeft) -fraction else fraction) * width
            down(Offset(width * from, height / 2f))
            repeat(12) { moveBy(Offset(dx / 12, 0f), delayMillis = 16) }
            advanceEventTime(150) // resting: no fling velocity
            up()
        }
    }

    /**
     * Touch swipes on a sequence row keep settling on whole panels however they overlap: a second swipe that interrupts the
     * first one's settling (and the centring of the newly chosen panel) still settles and chooses, so does a swipe back.
     * Then R2 opens the panel the swipes chose, and that panel is still the chosen one after recreation.
     */
    @Test
    fun repeatedAndInterruptedSwipesEachSettleOnAPanel() {
        await("6 games")
        reader.open(ReaderLocation("fixture-special", "short-note"))
        await("1 of 3")
        compose.mainClock.autoAdvance = false
        dragSequence(0.7f)
        repeat(3) { compose.mainClock.advanceTimeByFrame() } // settling has begun
        dragSequence(0.7f) // interrupts it
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5_000) { shown("3 of 3") }
        dragSequence(0.7f, rightToLeft = false)
        compose.waitUntil(5_000) { shown("2 of 3") }
        dragSequence(0.7f)
        compose.waitUntil(5_000) { shown("3 of 3") }
        pad.press(DOWN) // the controller in use
        pad.press(R2)
        compose.waitUntil(5_000) { described("Panel three") }
        assertFalse("the viewer opened the swiped-to panel", described("Panel one") || described("Panel two"))
        pad.press(B)
        compose.waitUntil(5_000) { !described("Panel three") }
        reader.recreate()
        await("3 of 3")
    }

    @Test
    fun selectMenuOpensTheHangarAndBReturnsToTheArticle() {
        openArticle()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        runBlocking { store.restore(store.view.value.state!!.copy(hangarIntroSeen = true), emptyList()) }
        scrollTo(3)
        val before = position()
        pad.press(SELECT)
        compose.waitUntil(5_000) { shown("Reader tools") }
        repeat(6) { if (pad.focusedLabel()?.startsWith("Visit the hangar") != true) pad.press(DOWN) }
        assertTrue("focus on the menu's hangar entry: ${pad.focusedLabel()}", pad.focusedLabel()!!.startsWith("Visit the hangar"))
        pad.press(A)
        compose.waitUntil(5_000) { shown("Workshop") && shown("Gallery") }
        assertTrue("the reader menu closed behind the Hangar", !shown("Reader tools"))
        pad.press(B)
        compose.waitUntil(5_000) { !shown("Workshop") }
        assertEquals("article position after the Hangar", before, position())
        pad.press(DOWN)
        assertNotEquals("the D-pad scrolls the article again", before, position())
    }

    @Test
    fun bFromAnArticleOpenedFromAGamePageClosesOnlyTheReader() {
        openArticle()
        pad.press(DOWN) // the controller in use, as on the device
        pad.press(B)
        assertTrue("B from the requested article closes the reader", reader.activity.isFinishing)
        assertFalse("the navigation underneath stays", sortie.activity.isFinishing)
    }

    @Test
    fun aStoppedReaderLeavesTheOtherScreenAndTakesItBackWhenItReturns() {
        openArticle()
        compose.waitUntil(5_000) { Selection.magazine.value?.articleId == "long-read" }
        // a game (or another app) comes to the front over the reader
        reader.controller.pause().stop()
        assertNull("a stopped reader's contents never stay on the companion", Selection.magazine.value)
        reader.controller.restart().resume()
        compose.waitUntil(5_000) { Selection.magazine.value?.articleId == "long-read" }
    }
}
