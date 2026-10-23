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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** M0 probe: prove the app runs and that a secondary display can be driven with a Presentation. */
class MainActivity : ComponentActivity() {
    private var presentation: Presentation? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val displays = displayManager().getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProbeScreen(
                        displays = displayManager().displays.map { it.describe() },
                        presentationTarget = displays.firstOrNull()?.describe()
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val target = displayManager().getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        if (target != null && presentation == null) {
            presentation = ProbePresentation(this, target).also { it.show() }
        }
    }

    override fun onPause() {
        presentation?.dismiss()
        presentation = null
        super.onPause()
    }

    private fun displayManager() = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
}

private fun Display.describe(): String = "id=$displayId \"$name\" ${mode.physicalWidth}x${mode.physicalHeight}"

@Composable
private fun ProbeScreen(displays: List<String>, presentationTarget: String?) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as SortieApp
    var report by remember { mutableStateOf("indexing…") }
    LaunchedEffect(Unit) {
        val snap = app.graph.manifests.snapshot.value
        val r = app.graph.indexer.run()
        report = "sortie dir ${app.graph.sortieDir} · systems ${snap.systems.size} · guide ${snap.guide.size} · errors ${snap.errors}\n" +
            "indexed files=${r.files} updated=${r.updated} removed=${r.removed} in ${r.millis} ms · problems ${r.problems.take(3)}"
    }
    Column(modifier = Modifier.padding(24.dp)) {
        Text(report, style = MaterialTheme.typography.bodySmall)
        Text("Sortie", style = MaterialTheme.typography.displayMedium)
        Text("M0 probe · primary display", style = MaterialTheme.typography.titleMedium)
        displays.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            presentationTarget?.let { "Presentation on: $it" } ?: "No secondary display",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

private class ProbePresentation(context: Context, display: Display) : Presentation(context, display) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(context).apply {
                text = "Sortie · screen 2\n${this@ProbePresentation.display.describe()}"
                textSize = 28f
                gravity = Gravity.CENTER
                setBackgroundColor(0xFF101418.toInt())
                setTextColor(0xFFF2B134.toInt())
            }
        )
    }
}
