package io.github.esko997.sortie

import android.app.ActivityOptions
import android.app.Presentation
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
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.ui.Hotkeys
import io.github.esko997.sortie.ui.SortieNav
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.SortieViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: SortieViewModel by viewModels()
    private var presentation: Presentation? = null
    private var companionShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One navigation instance per process: Home can spawn a second one on the default display while another lives on
        // the secondary display (after a swap). The newest wins.
        instances.filter { it !== this }.forEach { it.finishAndRemoveTask() }
        instances.clear()
        instances += this
        enableEdgeToEdge()
        val graph = (application as SortieApp).graph
        graph.launcher.currentDisplayId = { myDisplayId() }
        graph.launcher.beforeLaunch = { target ->
            // never leave the companion panel over the screen an emulator is about to use
            if (target == "bottom" || (target == "same" && myDisplayId() != Display.DEFAULT_DISPLAY)) dismissPresentation()
        }
        graph.launcher.afterLaunch = { target ->
            // Running on the secondary screen while a game takes the primary one: get out of the way so dual-screen emulators
            // (DS bottom screen) can use this display too. Home brings Sortie back.
            if (myDisplayId() != Display.DEFAULT_DISPLAY && target != myDisplayId()) {
                io.github.esko997.sortie.ui.Selection.closeCompanion.tryEmit(Unit)
                companionShown = false
                finishAndRemoveTask() // a home-role task cannot be moved to the back; Home brings Sortie back on the top screen
            }
        }
        Hotkeys.handle(setOf(Hotkeys.Key.SWAP_SCREENS))
        lifecycleScope.launch { Hotkeys.events.collect { if (it == Hotkeys.Key.SWAP_SCREENS) swapScreens() } }
        setContent { SortieTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) { SortieNav(vm) } } }
        if (savedInstanceState == null) handleIntent(intent) // never re-fire a launch on activity recreation
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Debug builds accept `--es launch <id> [--es profile <id>]` from adb so launch profiles can be tested from the desktop. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null || !BuildConfig.DEBUG) return
        intent.getStringExtra("open")?.let { route ->
            intent.removeExtra("open")
            io.github.esko997.sortie.ui.Selection.openRoute.tryEmit(route)
        }
        val id = intent.getStringExtra("launch") ?: return
        intent.removeExtra("launch")
        vm.launchById(id, intent.getStringExtra("profile"))
    }

    override fun onStart() {
        super.onStart()
        vm.onStart(Environment.isExternalStorageManager())
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground() // closes an open play session when we come back from an emulator
        showPresentation()
    }

    override fun onPause() {
        dismissPresentation()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean = (event?.repeatCount == 0 && Hotkeys.onKeyDown(keyCode)) || super.onKeyDown(keyCode, event)

    private fun myDisplayId(): Int = display?.displayId ?: Display.DEFAULT_DISPLAY

    /** The display this activity is not on: the configured presentation display normally, the default one when we run on a secondary display. */
    private fun otherDisplay(): Display? {
        val dm = displayManager()
        if (myDisplayId() != Display.DEFAULT_DISPLAY) return dm.getDisplay(Display.DEFAULT_DISPLAY)
        val index = vm.manifests.value.device.screens.presentationIndex
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(index)
    }

    private fun showPresentation() {
        if (presentation != null) return
        val target = otherDisplay() ?: return
        if (target.displayId == Display.DEFAULT_DISPLAY) { // Presentations cannot target the default display: use an activity there
            if (!companionShown) {
                companionShown = true
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
                startActivity(Intent(this, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
            }
            return
        }
        presentation = runCatching {
            CompanionPresentation(this, target, this).also { p ->
                p.setOnDismissListener { if (presentation === p) presentation = null }
                p.show()
            }
        }.getOrNull()
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    /** X: move Sortie to the other screen. Apps cannot move a task between displays, so finish here and relaunch there. */
    private fun swapScreens() {
        val target = otherDisplay() ?: return
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
        dismissPresentation()
        io.github.esko997.sortie.ui.Selection.closeCompanion.tryEmit(Unit)
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
