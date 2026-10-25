package io.github.esko997.sortie

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.os.bundleOf
import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.testing.SortieCardRule
import io.github.esko997.sortie.testing.THOR_TOP
import io.github.esko997.sortie.ui.Selection
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowDisplayManager

/**
 * The built-in apps' host ([BuiltinActivity]) at its platform boundary: an ordinary, named task of its own that never acts as
 * HOME or as Sortie's navigation, reopens instead of duplicating, and moves to the other screen with its saved state.
 * Robolectric does not run Android's task or display placement, so a move is checked as what Sortie asks of the system (which
 * activity, which display, which state) and the instance that request starts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = THOR_TOP)
class BuiltinActivityTest {
    @get:Rule(order = 0)
    val card = SortieCardRule()

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val app: SortieApp get() = ApplicationProvider.getApplicationContext()

    private val controllers = mutableListOf<ActivityController<*>>()

    private fun <T : BuiltinActivity> open(type: Class<T>, intent: Intent): ActivityController<T> = Robolectric.buildActivity(type, intent).setup().also {
        controllers += it
        compose.waitForIdle()
    }

    private fun magazines(opened: ReaderLocation? = null) = open(MagazinesActivity::class.java, BuiltinApps.intent(app, BuiltinApp.MAGAZINES, opened))

    private fun started(): ShadowActivity.IntentForResult? = shadowOf(app).nextStartedActivityForResult

    private fun Bundle?.launchDisplay(): Int = this?.getInt(LAUNCH_DISPLAY, -1) ?: -1

    @After
    fun closeActivities() {
        for (c in controllers) if (!c.get().isDestroyed) c.pause().stop().destroy()
    }

    @Test
    fun eachBuiltInIsANamedAppTaskOfItsOwnNeverAHome() {
        val pm = app.packageManager
        val affinities = BuiltinApp.entries.map { b ->
            val info = pm.getActivityInfo(ComponentName(app, b.activity), 0)
            assertEquals("the task's name is the switcher's", b.label, info.loadLabel(pm).toString())
            assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
            assertFalse("only Sortie starts it", info.exported)
            info.taskAffinity
        }
        // apart from each other and from the navigation's task (the package's default affinity)
        assertEquals(affinities.size, (affinities + app.packageName).toSet().size - 1)
        val hosts = BuiltinApp.entries.map { it.activity.name }.toSet()
        for (category in listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME, Intent.CATEGORY_LAUNCHER)) {
            val found = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL)
            assertTrue(category, found.none { it.activityInfo.name in hosts })
        }
    }

    @Test
    fun openingOrReturningToABuiltInLeavesThePlaySessionAndTheNavigationAlone() {
        val graph = app.graph
        val afterLaunch: (Int?) -> Unit = {}
        val navigationDisplay: () -> Int = { 0 }
        graph.launcher.afterLaunch = afterLaunch
        graph.launcher.currentDisplayId = navigationDisplay
        runBlocking { graph.playLog.start("genesis/Ace Sortie (World).md", "open-with", "same") }
        val session = checkNotNull(graph.playLog.current.value)
        val navigationVisible = Selection.navigationVisible.value

        val reader = magazines()
        reader.pause().resume() // back from the game on the other screen
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()

        // the navigation's resume ends the session when the game is gone; a built-in must not, or a game still running on
        // the other screen would lose its timer
        assertEquals(session, graph.playLog.current.value)
        assertSame(afterLaunch, graph.launcher.afterLaunch)
        assertSame(navigationDisplay, graph.launcher.currentDisplayId)
        assertEquals("a built-in is not the navigation", navigationVisible, Selection.navigationVisible.value)
        assertNull("no companion or anchor started for the other screen", started())
    }

    @Test
    fun aMoveCarriesTheSavedStateToTheOtherScreen() {
        val bottom = ShadowDisplayManager.addDisplay("w538dp-h444dp")
        val reader = magazines()
        reader.get().savedStateRegistry.registerSavedStateProvider("probe") { bundleOf("page" to 12) }

        assertTrue(reader.get().relocate(bottom, null))
        assertTrue("its task closes here", reader.get().isFinishing)
        val next = checkNotNull(started())
        assertEquals(MagazinesActivity::class.java.name, next.intent.component?.className)
        assertEquals(bottom, next.options.launchDisplay())
        assertNull("a plain move asks for no place", BuiltinApps.takeRequest(Intent(next.intent)))
        reader.pause().stop().destroy()

        val moved = open(MagazinesActivity::class.java, next.intent)
        assertEquals(12, moved.get().savedStateRegistry.consumeRestoredStateForKey("probe")?.getInt("page"))
    }

    @Test
    fun openingWhereItRunsBringsTheSameInstanceForward() {
        val reader = magazines()
        assertTrue(BuiltinApps.open(app, BuiltinApp.MAGAZINES, 0, ReaderLocation("fixture-special", "short-note")))
        val again = checkNotNull(started())
        assertEquals(0, again.options.launchDisplay())
        assertFalse(reader.get().isFinishing)

        // Android hands a singleTask activity's reopen to the running instance
        reader.pause()
        reader.newIntent(again.intent)
        reader.resume()
        assertFalse(reader.get().isFinishing)
        assertNull("nothing chases another display", started())
    }

    @Test
    fun openingOnTheOtherScreenMovesTheRunningInstanceThere() {
        val bottom = ShadowDisplayManager.addDisplay("w538dp-h444dp")
        val reader = magazines()
        val place = ReaderLocation("fixture-special", "long-read")
        assertTrue(BuiltinApps.open(app, BuiltinApp.MAGAZINES, bottom, place))

        assertTrue("one instance: the running one moves, it is not duplicated", reader.get().isFinishing)
        val next = checkNotNull(started())
        assertEquals(bottom, next.options.launchDisplay())
        assertTrue("its state goes along", next.intent.hasExtra(StateHandoff.EXTRA))
        assertEquals(place, BuiltinApps.takeRequest(Intent(next.intent))?.opened)
    }

    @Test
    fun aReopenThatAndroidBroughtForwardOnTheWrongScreenMovesItOnResume() {
        // a task Android restored (process gone) or kept: the reopen reaches it on its old display
        val bottom = ShadowDisplayManager.addDisplay("w538dp-h444dp")
        val reader = magazines()
        val place = ReaderLocation("fixture-special", "long-read")
        reader.pause()
        reader.newIntent(BuiltinApps.intent(app, BuiltinApp.MAGAZINES, place, bottom))
        reader.resume()

        assertTrue(reader.get().isFinishing)
        val next = checkNotNull(started())
        assertEquals(bottom, next.options.launchDisplay())
        assertEquals(place, BuiltinApps.takeRequest(Intent(next.intent))?.opened)
    }

    @Test
    fun aFreshStartNeverChasesItsDisplayAndNoDisplayMeansNoStart() {
        // placed by its launch options; were the system to put it elsewhere, chasing it would only repeat that
        val reader = open(MagazinesActivity::class.java, BuiltinApps.intent(app, BuiltinApp.MAGAZINES, null, 0))
        assertFalse(reader.get().isFinishing)
        assertNull(started())
        assertFalse(BuiltinApps.open(app, BuiltinApp.PERFORMANCE, 97))
        assertNull(started())
        // one screen configured (the fixture card): no "other screen" to offer
        assertNull(BuiltinApps.otherDisplay(app, 0))
    }

    @Test
    fun backOnThePerformancePanelClosesOnlyThatTask() {
        val navigation = Robolectric.buildActivity(MainActivity::class.java, Intent(app, MainActivity::class.java)).setup()
        controllers += navigation
        val perf = open(PerformanceActivity::class.java, BuiltinApps.intent(app, BuiltinApp.PERFORMANCE))

        perf.get().onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()

        assertTrue(perf.get().isFinishing)
        assertFalse(navigation.get().isFinishing)
        assertFalse("B closed the panel, not into the switcher", app.graph.switcher.state.value.open)
    }

    private companion object {
        /** ActivityOptions' key for setLaunchDisplayId. */
        const val LAUNCH_DISPLAY = "android.activity.launchDisplayId"
    }
}
