package io.github.esko997.sortie.ui

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.Controller.Companion.R2
import io.github.esko997.sortie.testing.Controller.Companion.SELECT
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The magazine reader on the Thor's top screen, read with the controller: the fixture's long article ("Pictures of Every
 * Shape": a lead image, sixteen numbered paragraphs, a portrait and a wide figure). Where the reader is is measured as which
 * numbered paragraph is the first one fully on screen, and at what height.
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

    private val pad = Controller()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(text: String) = compose.waitUntil(10_000) { shown(text) }

    /** Opens the article the way a game page's "Featured in" does, and waits for its text. */
    private fun openArticle() {
        await("6 games")
        Selection.openRoute.tryEmit("magazines/fixture-special/long-read")
        await("Pictures of Every Shape")
    }

    /** The first numbered paragraph whose top is on screen and its top edge in px; paragraph 0 while none is. */
    private fun position(): Pair<Int, Float> {
        compose.waitForIdle()
        val height = sortie.activity.window.decorView.height
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
        sortie.recreate()
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

    @Test
    fun selectMenuOpensTheHangarAndBReturnsToTheArticle() {
        openArticle()
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
}
