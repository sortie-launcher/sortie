package io.github.esko997.sortie

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.shell.TaskSwitcher
import io.github.esko997.sortie.ui.AmbientBackground
import io.github.esko997.sortie.ui.Hotkeys
import io.github.esko997.sortie.ui.Selection
import io.github.esko997.sortie.ui.SortieNav
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.SortieViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: SortieViewModel by viewModels()
    private var companionShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One navigation instance per process: Home can spawn a second one on the default display while another lives on
        // the secondary display (after a swap). The newest wins.
        instances.filter { it !== this }.forEach { it.finishAndRemoveTask() }
        instances.clear()
        instances += this
        enableEdgeToEdge()
        // No status bar over the launcher; a swipe from the top still shows it transiently.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val graph = (application as SortieApp).graph
        graph.launcher.currentDisplayId = { myDisplayId() }
        graph.launcher.afterLaunch = { target ->
            // Other launchers' leftover tasks would be what the system resumes when the game exits: clear them now.
            graph.screenMover.sweepAsync()
            // Running on the secondary screen while a game takes the primary one: get out of the way so dual-screen emulators
            // (DS bottom screen) can use this display too. The secondary home (companion panel) takes over this screen; Home
            // brings Sortie back on the top screen.
            if (myDisplayId() != Display.DEFAULT_DISPLAY && target != myDisplayId()) {
                Selection.closeCompanion.tryEmit(Unit)
                companionShown = false
                finishAndRemoveTask() // a home-role task cannot be moved to the back
            }
        }
        Hotkeys.handle(setOf(Hotkeys.Key.SWAP_SCREENS))
        lifecycleScope.launch { Hotkeys.events.collect { if (it == Hotkeys.Key.SWAP_SCREENS) swapScreens() } }
        setContent {
            SortieTheme {
                AmbientBackground {
                    Surface(
                        Modifier.fillMaxSize().safeDrawingPadding(),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) { SortieNav(vm) }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent) // never re-fire a launch on activity recreation
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The Home button lands on the switcher when something is running: Sortie is HOME, so the system delivers the HOME intent
     * here when the top screen had the controller; the secondary home summons us with [TaskSwitcher.EXTRA_SWITCHER] when the
     * bottom screen had it. Debug builds also accept `--es launch <id> [--es profile <id>]` / `--es open <route>` from adb.
     */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val summoned = intent.hasExtra(TaskSwitcher.EXTRA_SWITCHER)
        if (summoned || (intent.hasCategory(Intent.CATEGORY_HOME) && !intent.hasExtra("open") && !intent.hasExtra("launch"))) {
            intent.removeExtra(TaskSwitcher.EXTRA_SWITCHER)
            val switcher = (application as SortieApp).graph.switcher
            if (summoned) {
                // the secondary home already decided: show the switcher route here (no overlay grant)
                switcher.open()
                Selection.openRoute.tryEmit("switcher")
            } else {
                // Home on the top screen: overlay when granted, else the switcher route in this activity
                lifecycleScope.launch {
                    if (!switcher.anyRunning()) return@launch
                    if (switcher.presenter?.invoke(TaskSwitcher.TOP) == true) return@launch
                    switcher.open()
                    Selection.openRoute.tryEmit("switcher")
                }
            }
            return
        }
        if (!BuildConfig.DEBUG) return
        if (intent.getBooleanExtra("restart", false)) { // exercises the first-run restart path (RestartActivity is not exported)
            intent.removeExtra("restart")
            SortieApp.restart(this)
            return
        }
        intent.getStringExtra("open")?.let { route ->
            intent.removeExtra("open")
            Selection.openRoute.tryEmit(route)
        }
        val id = intent.getStringExtra("launch") ?: return
        intent.removeExtra("launch")
        vm.launchById(id, intent.getStringExtra("profile"))
    }

    override fun onStart() {
        super.onStart()
        val granted = Environment.isExternalStorageManager()
        if (granted && SortieApp.relocateIfNeeded(this, (application as SortieApp).graph)) return
        vm.onStart(granted)
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground() // closes an open play session when we come back from an emulator
        showCompanion()
    }

    /**
     * Hotkeys fire on the first key-down, except L1/R1 which fire on key-up: a held R1 is "favourite" on the focused tile
     * (`Modifier.keyLongPress` swallows the hold and its release), and must not also page sorts/systems.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            // a repeat means the button is being held: whatever is focused may act on it, and the release must not page
            if ((event?.repeatCount ?: 0) > 0) shoulderHeld = true
            return true
        }
        return (event?.repeatCount == 0 && Hotkeys.onKeyDown(keyCode)) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            val held = shoulderHeld
            shoulderHeld = false
            return held || Hotkeys.onKeyDown(keyCode) || super.onKeyUp(keyCode, event)
        }
        return super.onKeyUp(keyCode, event)
    }

    private var shoulderHeld = false

    private fun myDisplayId(): Int = display?.displayId ?: Display.DEFAULT_DISPLAY

    /** The display this activity is not on: the configured presentation display normally, the default one when we run on a secondary display. */
    private fun otherDisplay(): Display? {
        val dm = displayManager()
        if (myDisplayId() != Display.DEFAULT_DISPLAY) return dm.getDisplay(Display.DEFAULT_DISPLAY)
        val index = vm.manifests.value.device.screens.presentationIndex
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(index)
    }

    /**
     * Puts the companion panel on the other screen, once per instance. On the second screen that is Sortie's SECONDARY_HOME,
     * which the system keeps there anyway; starting it explicitly covers a stale Launcher3 home task left from before Sortie
     * became HOME. The default display has no secondary home, so an ordinary activity serves there.
     */
    private fun showCompanion() {
        if (companionShown) return
        val target = otherDisplay() ?: return
        companionShown = true
        startCompanion(target.displayId)
    }

    private fun startCompanion(displayId: Int) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val intent = if (displayId == Display.DEFAULT_DISPLAY) {
            Intent(this, CompanionActivity::class.java)
        } else {
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME).setClass(this, SecondaryHomeActivity::class.java)
        }
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle()) }
    }

    /**
     * X: move Sortie to the other screen. Apps cannot move a task between displays, so finish here and relaunch there.
     * Leaving the default display: cover it with the companion first. An empty default display makes the system start HOME,
     * and HOME is this singleTask activity, which would be pulled straight back to the default display.
     */
    private fun swapScreens() {
        val target = otherDisplay() ?: return
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
        if (target.displayId == Display.DEFAULT_DISPLAY) {
            Selection.closeCompanion.tryEmit(Unit)
        } else {
            startCompanion(Display.DEFAULT_DISPLAY)
        }
        finishAndRemoveTask()
        startActivity(intent, options.toBundle())
    }

    override fun onDestroy() {
        instances.remove(this)
        super.onDestroy()
    }

    private fun displayManager() = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private companion object {
        val instances = mutableListOf<MainActivity>()
    }
}
