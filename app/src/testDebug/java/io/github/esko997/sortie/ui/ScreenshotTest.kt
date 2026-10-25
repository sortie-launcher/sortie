package io.github.esko997.sortie.ui

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.ImageLoads
import io.github.esko997.sortie.testing.RP_NOVA
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_BOTTOM
import io.github.esko997.sortie.testing.THOR_TOP
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the screens whose layout depends most on the display: Home's custom-sorted grid, the magazine shelf,
 * an article at its portrait and at its wide figure, and the Hangar's Workshop. Each runs on the Thor's two screens, the DMG
 * and the Retroid Pocket Nova (subclasses below). Goldens live in app/src/testDebug/screenshots/<device>/; docs/HARDENING.md
 * says how to re-record them. The whole screen is captured (every window, so dialogs too) once data and pictures have arrived.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotTest(private val device: String) {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 2)
    val sortie = SortieActivityRule()

    private val pad = Controller()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(vararg texts: String) = compose.waitUntil(10_000) { texts.all(::shown) }

    private fun awaitHome() = await("6 games", "Shooters")

    private fun openArticle() {
        awaitHome()
        Selection.openRoute.tryEmit("magazines/fixture-special/long-read")
        await("Pictures of Every Shape")
    }

    /** Scrolls the article with the D-pad until [text] (a figure caption) is fully on screen. */
    private fun scrollToCaption(text: String) {
        pad.press(DOWN) // leaves touch mode; the reader holds the focus
        val height = sortie.activity.window.decorView.height
        repeat(80) {
            compose.waitForIdle()
            val node = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().firstOrNull()
            if (node != null && node.positionInRoot.y + node.size.height <= height * 0.8f) return
            pad.press(DOWN)
        }
        error("never scrolled to \"$text\"")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String) {
        val mechLoading = { compose.onAllNodesWithContentDescription("Loading mech").fetchSemanticsNodes().isNotEmpty() }
        try {
            compose.waitUntil(10_000) { ImageLoads.idle && !mechLoading() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("still loading: pictures ${ImageLoads.pending()}, mech sprite ${mechLoading()}", e)
        }
        // let time-bound effects settle: the focus glow's pulse (3 s) and the background's colour blend (2.5 s)
        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        captureScreenRoboImage("$device/$name.png", OPTIONS)
    }

    @Test
    fun homeCustomGrid() {
        awaitHome()
        capture("home-custom-grid")
    }

    @Test
    fun homeCompactBrowsing() {
        awaitHome()
        compose.onNodeWithContentDescription("Hide header").performClick()
        capture("home-compact")
    }

    @Test
    fun homeAlwaysExpanded() {
        awaitHome()
        compose.runOnIdle { ApplicationProvider.getApplicationContext<SortieApp>().graph.user.setBrowseChrome("expanded") }
        compose.waitForIdle()
        capture("home-expanded")
    }

    @Test
    fun systemCompactBrowsing() {
        awaitHome()
        Selection.openRoute.tryEmit("system/genesis")
        await("Cinder Vale")
        compose.onNodeWithContentDescription("Hide header").performClick()
        capture("system-compact")
    }

    @Test
    fun magazineShelf() {
        awaitHome()
        Selection.openRoute.tryEmit("magazines")
        await("The Long Read")
        capture("magazine-shelf")
    }

    @Test
    fun articlePortraitFigure() {
        openArticle()
        scrollToCaption("A tall portrait plate")
        capture("article-portrait-figure")
    }

    @Test
    fun articleWideFigure() {
        openArticle()
        scrollToCaption("A wide panorama")
        capture("article-wide-figure")
    }

    @Test
    fun hangarWorkshop() {
        awaitHome()
        compose.onNodeWithText("Hangar").performClick()
        await("Workshop", "Gallery")
        capture("hangar-workshop")
    }

    private companion object {
        // Half-size goldens keep the repository small; any layout shift still changes many pixels. No tolerance: Robolectric's
        // native graphics render identically on every Linux x86-64 host.
        val OPTIONS = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)
        )
    }
}

@Config(qualifiers = THOR_TOP)
class ThorTopScreenshotTest : ScreenshotTest("thor-top")

@Config(qualifiers = THOR_BOTTOM)
class ThorBottomScreenshotTest : ScreenshotTest("thor-bottom")

@Config(qualifiers = DMG)
class DmgScreenshotTest : ScreenshotTest("dmg")

@Config(qualifiers = RP_NOVA)
class RpNovaScreenshotTest : ScreenshotTest("rp-nova")
