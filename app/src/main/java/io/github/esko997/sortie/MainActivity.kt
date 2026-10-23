package io.github.esko997.sortie

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.esko997.sortie.ui.SortieNav
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.SortieViewModel

class MainActivity : ComponentActivity() {
    private val vm: SortieViewModel by viewModels()
    private var presentation: Presentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.reindex()
        setContent { SortieTheme { Surface(Modifier.fillMaxSize()) { SortieNav(vm) } } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        val id = intent?.getStringExtra("launch") ?: return
        intent.removeExtra("launch")
        vm.launchById(id, intent.getStringExtra("profile"))
    }

    override fun onResume() {
        super.onResume()
        vm.onForeground() // closes an open play session when we come back from an emulator
        val target = displayManager().getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (target != null && presentation == null) presentation = SecondScreen(this, target).also { it.show() }
    }

    override fun onPause() {
        presentation?.dismiss(); presentation = null
        super.onPause()
    }

    private fun displayManager() = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
}

/** M1: a static companion panel on the second screen. M3 gives it a real role. */
private class SecondScreen(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(context).apply {
                text = "Sortie"
                textSize = 36f
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF101418.toInt())
                setTextColor(0xFFF2B134.toInt())
            },
        )
    }
}
