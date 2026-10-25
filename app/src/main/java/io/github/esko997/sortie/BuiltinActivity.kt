package io.github.esko997.sortie

import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.ui.AmbientBackground
import io.github.esko997.sortie.ui.BuiltinContent
import io.github.esko997.sortie.ui.Hotkeys
import io.github.esko997.sortie.ui.LocalHotkeys
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.SortieViewModel

/**
 * Hosts one [BuiltinApp] as an ordinary app task (never HOME): its own singleTask task and affinity, so reopening resumes it
 * and the switcher can raise, move and close it like any app. Unlike Sortie's navigation it neither ends the play session on
 * resume nor puts a companion on the other screen: a game there keeps running undisturbed.
 *
 * Moving to the other screen is finish here, start there, with this instance's saved state handed to the new one
 * ([StateHandoff], as the navigation's X swap does): apps cannot move their own task between displays.
 */
abstract class BuiltinActivity(private val app: BuiltinApp) : ComponentActivity() {
    private val vm: SortieViewModel by viewModels()

    /** This window's hotkeys: the navigation (or another built-in) visible on the other screen has its own. */
    private val hotkeys = Hotkeys()

    /** The latest open request (cold start or reopen) the content has to act on; null after a plain recreation or move. */
    private var request by mutableStateOf<BuiltinRequest?>(null)

    /**
     * A display a reopen asked for that this instance may not be on (a task Android restored or kept where it was), with what
     * that reopen asked to show; settled on resume.
     */
    private var wanted: Pair<Int, ReaderLocation?>? = null

    private val origin: () -> Int = { displayId() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // a move: carry on with the state the instance on the other screen saved, as if this were a recreation
        val state = savedInstanceState ?: handoffs.getValue(app).take(intent)
        super.onCreate(state)
        // a system recreation already holds what its intent asked for; a fresh start or a move acts on its intent's request
        request = if (savedInstanceState == null) BuiltinApps.takeRequest(intent) else null
        BuiltinApps.takeDisplay(intent) // a fresh start was placed by its launch options; never chase that display from here
        live[app] = this
        vm.launchOrigin = origin
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            SortieTheme {
                AmbientBackground {
                    Surface(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        // recomputed when a recreation brings a new configuration (another display)
                        val other = remember(LocalConfiguration.current) { BuiltinApps.otherDisplay(this, displayId()) }
                        val onMove = other?.let { target ->
                            {
                                relocate(target, null)
                                Unit
                            }
                        }
                        CompositionLocalProvider(LocalHotkeys provides hotkeys) {
                            BuiltinContent(app, vm, request, onMove, onClose = ::finishAndRemoveTask)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val asked = BuiltinApps.takeRequest(intent)
        asked?.let { request = it }
        wanted = BuiltinApps.takeDisplay(intent)?.let { it to asked?.opened }
    }

    override fun onResume() {
        super.onResume()
        // Reopened for a display this task is not on: Android brought a task it restored (or kept) forward where it was.
        val (target, opened) = wanted ?: return
        wanted = null
        if (target != displayId() && BuiltinApps.exists(this, target)) relocate(target, opened)
    }

    override fun onPause() {
        // Closing (B at the top, Close, a move): the screen it leaves may show a companion, which must hand the controller on
        // even right after an anchor (see AnchorGuard), as it does when the navigation leaves.
        if (isFinishing) (application as SortieApp).graph.switcher.navigationShown()
        super.onPause()
    }

    override fun onDestroy() {
        if (live[app] === this) live.remove(app)
        if (vm.launchOrigin === origin) vm.launchOrigin = null
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = hotkeys.keyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = hotkeys.keyUp(keyCode) || super.onKeyUp(keyCode, event)

    internal fun displayId(): Int = display?.displayId ?: Display.DEFAULT_DISPLAY

    /**
     * Continues this app on [target]: saves this instance's state, closes its task and starts the app there with that state
     * and, when given, [opened] to act on. Only this task goes; HOME, the navigation and other tasks are untouched.
     */
    internal fun relocate(target: Int, opened: ReaderLocation?): Boolean {
        if (isFinishing || isDestroyed || !BuiltinApps.exists(this, target)) return false
        (application as SortieApp).graph.switcher.navigationShown() // as for the navigation's X swap
        val saved = Bundle()
        onSaveInstanceState(saved)
        val intent = handoffs.getValue(app).offer(BuiltinApps.intent(this, app, opened), saved)
        finishAndRemoveTask()
        return BuiltinApps.start(this, intent, target)
    }

    internal companion object {
        /** The live instance of each built-in app (one task each); main thread. */
        private val live = mutableMapOf<BuiltinApp, BuiltinActivity>()

        private val handoffs = BuiltinApp.entries.associateWith { StateHandoff() }

        fun running(app: BuiltinApp): BuiltinActivity? = live[app]?.takeUnless { it.isFinishing || it.isDestroyed }
    }
}

/** Sortie's magazine reader as an app of its own. */
class MagazinesActivity : BuiltinActivity(BuiltinApp.MAGAZINES)

/** The full performance panel as an app of its own. */
class PerformanceActivity : BuiltinActivity(BuiltinApp.PERFORMANCE)
