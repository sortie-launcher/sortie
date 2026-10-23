package io.github.esko997.sortie

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Environment
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.esko997.sortie.ui.Hotkeys
import io.github.esko997.sortie.ui.SortieNav
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.SortieViewModel

class MainActivity : ComponentActivity() {
    private val vm: SortieViewModel by viewModels()
    private var presentation: Presentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        (application as SortieApp).graph.launcher.beforeLaunch = { display -> if (display == "bottom") dismissPresentation() }
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
        val id = intent?.getStringExtra("launch") ?: return
        intent.removeExtra("launch")
        if (!BuildConfig.DEBUG) return
        vm.launchById(id, intent.getStringExtra("profile"))
    }

    override fun onStart() {
        super.onStart()
        vm.onStart(storageGranted())
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

    private fun storageGranted(): Boolean = Environment.isExternalStorageManager()

    private fun showPresentation() {
        if (presentation != null) return
        val index = vm.manifests.value.device.screens.presentationIndex
        val target = displayManager().getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(index) ?: return
        presentation = runCatching {
            SecondScreen(this, target).also { p ->
                p.setOnDismissListener { if (presentation === p) presentation = null }
                p.show()
            }
        }.getOrNull()
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    private fun displayManager() = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
}

/** M1: a static companion panel on the second screen. M3 gives it a real role. */
private class SecondScreen(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(context).apply {
                text = context.getString(R.string.app_name)
                textSize = 36f
                gravity = Gravity.CENTER
                setBackgroundColor(context.getColor(R.color.ink))
                setTextColor(context.getColor(R.color.amber))
            }
        )
    }
}
