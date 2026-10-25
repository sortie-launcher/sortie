package io.github.esko997.sortie.ui

import android.app.Application
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.MagazinesActivity
import io.github.esko997.sortie.testing.BuiltinActivityRule
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.A
import io.github.esko997.sortie.testing.Controller.Companion.B
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Sortie's built-in apps on a one-screen device (the DMG), over the navigation: how they are opened from Apps, and how B and
 * the window's own Close leave only that app's window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = DMG)
class BuiltinWindowTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    @get:Rule(order = 3)
    val magazines = BuiltinActivityRule(BuiltinApp.MAGAZINES)

    @get:Rule(order = 4)
    val performance = BuiltinActivityRule(BuiltinApp.PERFORMANCE)

    private val pad = Controller()

    private fun shown(text: String, substring: Boolean = true) = compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun awaitHome() = compose.waitUntil(10_000) { shown("6 games") }

    @Test
    fun appsOpensABuiltinStraightAwayWhenThereIsNoOtherScreen() {
        awaitHome()
        Selection.openRoute.tryEmit("apps")
        // a list row shows the name; a small grid tile only describes itself with it
        val entry = hasText(BuiltinApp.MAGAZINES.label) or hasContentDescription(BuiltinApp.MAGAZINES.label)
        compose.waitUntil(5_000) { shown("Sortie", substring = false) && compose.onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
        val starts = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        generateSequence { starts.nextStartedActivity }.count() // drops what the navigation started on its own before the tap
        compose.onNode(entry).performClick()
        compose.waitForIdle()
        assertFalse("one screen: no here-or-there question", shown("Open here"))
        assertEquals(MagazinesActivity::class.java.name, starts.nextStartedActivity?.component?.className)
    }

    @Test
    fun performanceOffersCloseButNoOtherScreenAndBClosesOnlyItsWindow() {
        awaitHome()
        performance.open()
        compose.waitUntil(5_000) { shown("Performance", substring = false) && shown("Close", substring = false) }
        assertFalse("one screen: nothing to move to", shown("Other screen"))
        pad.press(DOWN) // the controller in use: the page holds the focus and scrolls
        pad.press(B)
        assertTrue("B closes the Performance window", performance.activity.isFinishing)
        assertFalse("the navigation stays", sortie.activity.isFinishing)
    }

    @Test
    fun touchCloseClosesTheWindow() {
        awaitHome()
        performance.open()
        compose.waitUntil(5_000) { shown("Close", substring = false) }
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertTrue(performance.activity.isFinishing)
    }

    /** B climbs article → contents → shelf, and only the shelf's B closes the reader. */
    @Test
    fun bClimbsTheMagazinesBeforeClosingThem() {
        awaitHome()
        magazines.open()
        compose.waitUntil(5_000) { shown("The Long Read") }
        pad.press(DOWN) // leaves touch mode; the shelf focuses its first entry
        pad.press(A)
        // inside an issue: its contents ("B shelf"), or the article a "Continue reading" entry opened ("B contents")
        compose.waitUntil(5_000) { shown("shelf", substring = false) || shown("contents", substring = false) }
        repeat(2) { if (!shown("scrapbook", substring = false)) pad.press(B) }
        assertTrue("back on the shelf", shown("scrapbook", substring = false))
        assertFalse("climbing does not close the reader", magazines.activity.isFinishing)
        pad.press(B)
        assertTrue("B on the shelf closes the reader", magazines.activity.isFinishing)
        assertFalse("the navigation stays", sortie.activity.isFinishing)
    }
}
