package io.github.esko997.sortie.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = DMG)
class BrowseHintsTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    @Test
    fun compactHintsKeepSecondaryCommandsAccessibleAndReturnFocusAfterDismissal() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Shooters", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Controls").performTouchInput { click() }
        compose.onNodeWithText("move / remove").assertIsDisplayed()
        compose.onNodeWithText("random").assertIsDisplayed()
        compose.onNodeWithText("systems").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val pad = Controller()
        pad.press(DOWN)
        compose.waitForIdle()
        assertEquals("Close", pad.focusedLabel())
        pad.press(B)
        // Robolectric removes the dialog but does not notify the activity that its window regained focus.
        compose.runOnIdle { sortie.activity.window.decorView.dispatchWindowFocusChanged(true) }
        compose.waitForIdle()
        assertEquals("Controls", pad.focusedLabel())
        compose.onNodeWithText("Shooters", substring = true).assertIsDisplayed()
    }
}
