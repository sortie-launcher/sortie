package io.github.esko997.sortie.ui

import android.view.View
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.testing.BuiltinActivityRule
import io.github.esko997.sortie.testing.Controller
import io.github.esko997.sortie.testing.Controller.Companion.DOWN
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.ImageLoads
import io.github.esko997.sortie.testing.RP_NOVA
import io.github.esko997.sortie.testing.SortieActivityRule
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_BOTTOM
import io.github.esko997.sortie.testing.THOR_TOP
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the screens whose layout depends most on the display: Home's custom-sorted grid, the Magazines app's
 * shelf, an article at its portrait and at its wide figure, and the Hangar's Workshop. Each runs on the Thor's two screens, the
 * DMG and the Retroid Pocket Nova (subclasses below). Goldens live in app/src/testDebug/screenshots/<device>/; docs/HARDENING.md
 * Launcher captures include dialogs. Reader captures target its separate activity window, not the launcher behind it.
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

    @get:Rule(order = 3)
    val magazines = BuiltinActivityRule(BuiltinApp.MAGAZINES)

    private val pad = Controller()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun await(vararg texts: String) = compose.waitUntil(10_000) { texts.all(::shown) }

    private fun awaitHome() = await("6 games", "Shooters")

    /** The article in the Magazines app, opened as a game page's "Featured in" opens it, over the indexed navigation. */
    private fun openArticle() {
        awaitHome()
        magazines.open(ReaderLocation("fixture-special", "long-read"))
        await("Pictures of Every Shape")
    }

    /** Scrolls the article with the D-pad until [text] (a figure caption) is fully on screen. */
    private fun scrollToCaption(text: String) {
        pad.press(DOWN) // leaves touch mode; the reader holds the focus
        val height = magazines.activity.window.decorView.height
        repeat(80) {
            compose.waitForIdle()
            val node = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().firstOrNull()
            if (node != null && node.positionInRoot.y + node.size.height <= height * 0.8f) return
            pad.press(DOWN)
        }
        error("never scrolled to \"$text\"")
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(name: String, window: View? = null) {
        val mechLoading = { compose.onAllNodesWithContentDescription("Loading mech").fetchSemanticsNodes().isNotEmpty() }
        try {
            compose.waitUntil(10_000) { ImageLoads.idle && !mechLoading() }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("still loading: pictures ${ImageLoads.pending()}, mech sprite ${mechLoading()}", e)
        }
        // Let finite focus and artwork-colour transitions settle before capturing the static layout.
        compose.mainClock.advanceTimeBy(3_500)
        compose.waitForIdle()
        if (window == null) captureScreenRoboImage("$device/$name.png", OPTIONS) else window.captureRoboImage("$device/$name.png", OPTIONS)
    }

    @Test
    fun homeCustomGrid() {
        awaitHome()
        capture("home-custom-grid")
    }

    @Test
    fun homeCompactBrowsing() {
        awaitHome()
        val toggle = compose.onNodeWithContentDescription(HIDE_HEADER)
        // Compact windows keep header actions on a horizontal rail; bring the toggle into view before tapping it.
        if (!toggle.isDisplayed()) toggle.performScrollTo()
        toggle.performClick()
        compose.onNodeWithContentDescription(SHOW_HEADER).assertIsDisplayed()
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
        magazines.open()
        await("The Long Read")
        capture("magazine-shelf", magazines.activity.window.decorView)
    }

    @Test
    fun articlePortraitFigure() {
        openArticle()
        scrollToCaption("A tall portrait plate")
        capture("article-portrait-figure", magazines.activity.window.decorView)
    }

    @Test
    fun articleWideFigure() {
        openArticle()
        scrollToCaption("A wide panorama")
        capture("article-wide-figure", magazines.activity.window.decorView)
    }

    @Test
    fun hangarWorkshop() {
        awaitHome()
        val store = (sortie.activity.application as SortieApp).graph.mecha
        compose.waitUntil(5_000) { store.view.value.state != null && !store.view.value.busy }
        runBlocking { store.restore(store.view.value.state!!.copy(hangarIntroSeen = true), emptyList()) }
        compose.onNodeWithText("Extras").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Hangar").fetchSemanticsNodes().isNotEmpty() }
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
