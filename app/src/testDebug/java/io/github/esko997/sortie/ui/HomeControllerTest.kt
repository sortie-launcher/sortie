package io.github.esko997.sortie.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Home driven with the controller on the Thor's top screen, over the fixture card (custom sort, five-column grid). */
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
        // and Home still answers the controller afterwards
        assertEquals(listOf("Apps"), walk(DOWN))
    }

    /**
     * The first D-pad press lands on the header's first action; Right walks the actions in reading order; Down reaches the sort
     * chips, then the first tile; the grid follows the user's custom order (Continue is absent: nothing has been played), and
     * Up from the first row returns to the sort chips rather than skipping them.
     */
    @Test
    fun dpadWalksHeaderSortChipsAndCustomGridInOrder() {
        awaitHome()
        assertEquals(
            listOf("Apps", "Library setup", "Search", "Magazines", "Journal", "Manual", "Settings"),
            walk(DOWN, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT, RIGHT)
        )
        assertEquals(listOf("Manual", "Journal", "Magazines", "Search", "Library setup", "Apps"), walk(LEFT, LEFT, LEFT, LEFT, LEFT, LEFT))
        assertEquals(listOf("Alphabetical", "PlayStation 1 · 32-bit"), walk(DOWN, DOWN))
        assertEquals(
            listOf("Ace Sortie Mega Drive ★", "Mega Drive 3 · 16-bit", "Shooters 2", "Super NES 2 · 16-bit"),
            walk(RIGHT, RIGHT, RIGHT, RIGHT)
        )
        assertEquals(listOf("Alphabetical"), walk(LEFT, LEFT, LEFT, LEFT, UP).drop(4))
    }

    /** A on a focused tile opens it, and B returns to Home with the same tile focused. */
    @Test
    fun aOpensTheFocusedSystemAndBReturnsToTheSameTile() {
        awaitHome()
        walk(DOWN, DOWN, DOWN, RIGHT, RIGHT)
        assertEquals("Mega Drive 3 · 16-bit", pad.focusedLabel())
        pad.press(A)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Cinder Vale").fetchSemanticsNodes().isNotEmpty() }
        pad.press(B)
        awaitHome()
        assertEquals("Mega Drive 3 · 16-bit", pad.focusedLabel())
    }
}
