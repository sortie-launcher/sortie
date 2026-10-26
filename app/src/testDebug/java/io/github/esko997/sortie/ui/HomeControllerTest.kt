package io.github.esko997.sortie.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.Controller.Companion.LEFT
import io.github.esko997.sortie.testing.Controller.Companion.RIGHT
import io.github.esko997.sortie.testing.Controller.Companion.UP
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home driven with the controller on the Thor's top screen, over the fixture card (custom sort, five-column grid). The screen is
 * under 600 dp tall, so with the default `browseChrome` (Auto) the header folds while browsing the tiles.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP)
class HomeControllerTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    private val pad = Controller()

    /** Home with the whole library: the index (6 games) and the smart folders, which resolve after it. */
    private fun awaitHome() = compose.waitUntil(10_000) {
        listOf("6 games", "Shooters").all { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Presses [keys] one by one and returns what has the focus after each. */
    private fun walk(vararg keys: Int): List<String?> = keys.map { key ->
        pad.press(key)
        compose.waitForIdle()
        pad.focusedLabel()
    }

    /** Presses [key] until [label] has the focus, at most [max] times; fails when it never gets there. */
    private fun pressUntil(key: Int, label: String, max: Int = 8) {
        repeat(max) {
            if (pad.focusedLabel() == label) return
            pad.press(key)
            compose.waitForIdle()
        }
        assertEquals(label, pad.focusedLabel())
    }

    private fun shown(description: String) = compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()

    private fun sortRowShown() = compose.onAllNodesWithText("Sort").fetchSemanticsNodes().isNotEmpty()

    /** The first tile of the fixture's custom layout (Continue is absent: nothing has been played). */
    private val firstTile = "PlayStation 1 · 32-bit"

    /**
     * Regression for "Home, Home, B sent Sortie away" (docs/HARDENING.md): where Sortie runs in an ordinary task, Back on Home
     * moved the task to the back and left Recents or a stale app on screen. Home is the root, so Back must do nothing there.
     */
    @Test
    fun backOnHomeAfterHomePressesKeepsSortieOnHome() {
        awaitHome()
        sortie.pressHome()
        sortie.pressHome()
        pad.press(B)
        compose.waitForIdle()
        val activity = sortie.activity
        assertFalse("Back finished Sortie", activity.isFinishing)
        assertFalse("Back moved Sortie's task behind other apps", shadowOf(activity).isTaskMovedToBack)
        compose.onNodeWithText("Sort").assertIsDisplayed()
        // Home still answers the controller: the library remains reachable.
        pressUntil(DOWN, firstTile)
    }

    /**
     * The header opens with Home and its actions remain reachable from the controller. Walking the header does not fold it;
     * Down reaches the tiles, which folds it to the strip. Up from the first row opens it again with the focus on the chosen
     * sort (the row just above the grid), never on the strip, and Down goes back into the tiles, folding it again.
     */
    @Test
    fun enteringTheTilesFoldsTheHeaderAndUpFromTheFirstRowOpensItOnTheSort() {
        awaitHome()
        focusHeader()
        assertEquals(listOf("Search", "Apps"), walk(RIGHT, LEFT))
        assertTrue("choosing a header action folded the header", sortRowShown())
        repeat(4) { if (!shown(SHOW_HEADER)) walk(DOWN) }
        assertFalse("the sort row is still shown while browsing the tiles", sortRowShown())
        assertTrue("the folded strip has no chevron", shown(SHOW_HEADER))

        assertEquals(listOf("Custom"), walk(UP))
        assertTrue("Up did not open the header", sortRowShown())
        assertTrue(shown(HIDE_HEADER))
        assertFalse(shown(SHOW_HEADER))

        val back = walk(DOWN).single()
        assertNotNull("the focus was lost on the way back into the tiles", back)
        assertFalse("the header did not fold again", sortRowShown())
    }

    /** The chevron folds and opens the header in place from the controller: the focus stays on the chevron both ways. */
    @Test
    fun chevronFoldsAndOpensTheHeaderKeepingTheFocus() {
        awaitHome()
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(RIGHT, HIDE_HEADER)
        assertEquals(listOf(SHOW_HEADER), walk(A))
        assertFalse(sortRowShown())
        assertEquals(listOf(HIDE_HEADER), walk(A))
        assertTrue(sortRowShown())
    }

    /** A tap on the folded strip opens the header, and so does a pull down that starts on the strip. */
    @Test
    fun tapOrPullDownOnTheStripOpensTheHeader() {
        awaitHome()
        compose.onNodeWithContentDescription(HIDE_HEADER).performClick()
        compose.waitForIdle()
        assertTrue(shown(SHOW_HEADER))
        compose.onNodeWithText("games · Custom", substring = true).performTouchInput { click() }
        compose.waitForIdle()
        assertTrue("a tap on the strip did not open the header", sortRowShown())

        compose.onNodeWithContentDescription(HIDE_HEADER).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("games · Custom", substring = true).performTouchInput { swipe(center, center + Offset(0f, 200f), 200) }
        compose.waitForIdle()
        assertTrue("a pull down from the strip did not open the header", sortRowShown())
    }

    /** With `browseChrome` Expanded the header never folds, however far into the tiles the controller goes. */
    @Test
    fun expandedPreferenceNeverFolds() {
        awaitHome()
        (sortie.activity.application as SortieApp).graph.user.setBrowseChrome("expanded")
        compose.waitUntil(5_000) { !shown(HIDE_HEADER) }
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(DOWN, firstTile)
        walk(RIGHT, DOWN)
        assertTrue("the Expanded header folded", sortRowShown())
        assertFalse(shown(SHOW_HEADER))
    }

    /** A on a focused tile opens it, and B returns to Home with the same tile focused and the header still folded. */
    @Test
    fun aOpensTheFocusedSystemAndBReturnsToTheSameTile() {
        awaitHome()
        pressUntil(DOWN, firstTile)
        assertEquals(listOf("Ace Sortie Mega Drive ★", "Mega Drive 3 · 16-bit"), walk(RIGHT, RIGHT))
        pad.press(A)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cinder Vale").fetchSemanticsNodes().isNotEmpty() }
        pad.press(B)
        awaitHome()
        assertEquals("Mega Drive 3 · 16-bit", pad.focusedLabel())
        assertTrue("Back from the system opened the folded header", shown(SHOW_HEADER))
    }

    /** Reach the header regardless of which row spatial focus chooses first in this layout. */
    private fun focusHeader() {
        pad.press(DOWN)
        compose.waitForIdle()
        pressUntil(UP, "Apps")
    }

    /** Walks the header to Extras and opens its menu with A: the focus lands on the menu's first entry. */
    private fun openExtras() {
        focusHeader()
        assertEquals(listOf("Search", "Extras"), walk(RIGHT, RIGHT))
        pad.press(A)
        compose.waitForIdle()
        compose.waitUntil(5_000) { pad.focusedLabel() == "Tonight" }
    }

    /** B closes the Extras menu and the focus is back on the Extras button, where the header walk carries on. */
    @Test
    fun extrasMenuClosesWithBAndReturnsToTheButton() {
        awaitHome()
        openExtras()
        assertEquals(listOf("Hangar", "Magazines"), walk(DOWN, DOWN))
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Tonight").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals("Extras", pad.focusedLabel())
        assertEquals(listOf("Settings"), walk(RIGHT))
    }

    /** Extras → Hangar opens the Hangar from the controller; B closes it and the focus is back on Extras. */
    @Test
    fun hangarFromExtrasOpensTheHangarAndBReturnsToExtras() {
        awaitHome()
        openExtras()
        assertEquals(listOf("Hangar"), walk(DOWN))
        pad.press(A)
        compose.waitUntil(5_000) { listOf("Workshop", "Gallery").all { compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } }
        pad.press(B)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Workshop").fetchSemanticsNodes().isEmpty() }
        compose.mainClock.advanceTimeBy(500) // the button takes the focus back once the Hangar's window is gone
        compose.waitForIdle()
        assertEquals("Extras", pad.focusedLabel())
    }
}
