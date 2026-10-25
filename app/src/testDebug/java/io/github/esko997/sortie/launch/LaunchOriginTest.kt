package io.github.esko997.sortie.launch

import android.content.ComponentName
import android.content.pm.PackageInfo
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.testing.SortieCardRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A game started from a built-in app's window (the magazine reader's game page) belongs to that window: it resolves the "same"
 * screen against the built-in's display and never runs the navigation's after-launch hook, which may close Sortie's navigation
 * to make room on the other screen. A game started from the navigation keeps that hook.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchOriginTest {
    @get:Rule
    val card = SortieCardRule()

    private val app: SortieApp get() = ApplicationProvider.getApplicationContext()
    private val launcher get() = app.graph.launcher
    private val hooked = mutableListOf<Int?>()
    private lateinit var game: GameEntity

    /** An installed emulator with a plain explicit intent: a real start, not the Open with… chooser. */
    private val emulator = LaunchProfile("test-emu", "Test emulator", `package` = "example.emu", activity = "example.emu.Play")

    @Before
    fun navigationIsOnTheTopScreen() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "example.emu" })
        launcher.currentDisplayId = { 0 }
        launcher.afterLaunch = { hooked += it }
        game = runBlocking {
            app.graph.indexer.run(force = true)
            checkNotNull(app.graph.db.games().byId("genesis/Ace Sortie (World).md"))
        }
    }

    private fun startedDisplay(): Int = checkNotNull(shadowOf(app).nextStartedActivityForResult).options?.getInt("android.activity.launchDisplayId", -1) ?: -1

    @Test
    fun aLaunchFromABuiltInStaysOnItsScreenAndLeavesTheNavigationAlone() {
        val displays = app.getSystemService(DisplayManager::class.java)
        val panel = displays.createVirtualDisplay("Reading panel", 1240, 1080, 320, null, DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION)
        shadowOf(panel.display).setFlags(Display.FLAG_PRESENTATION)
        val bottom = panel.display.displayId
        assertTrue(displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { it.displayId == bottom })
        assertTrue(runBlocking { launcher.launch(game, emulator, from = bottom) })
        assertEquals(bottom, startedDisplay())
        assertEquals(emptyList<Int?>(), hooked)
        assertEquals("a game did start", game.id, app.graph.playLog.current.value?.gameId)
    }

    @Test
    fun aLaunchFromTheNavigationStillRunsItsHook() {
        assertTrue(runBlocking { launcher.launch(game, emulator) })
        assertEquals(0, startedDisplay())
        assertEquals(listOf<Int?>(0), hooked)
    }

    @Test
    fun anOpenWithPickFromABuiltInLeavesTheNavigationAlone() = runBlocking {
        val chosen = ComponentName("example.emu", "example.emu.Play")
        launcher.chooserSelected(game.id, "open-with", "same", 4, chosen, origin = null, scoped = true)
        assertEquals(emptyList<Int?>(), hooked)
        launcher.chooserSelected(game.id, "open-with", "same", 0, chosen, origin = null, scoped = false)
        assertEquals(listOf<Int?>(0), hooked)
    }
}
