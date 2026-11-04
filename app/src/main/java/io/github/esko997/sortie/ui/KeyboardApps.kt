package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.KeyboardWindow
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.flow.MutableStateFlow

/** A touch keyboard: the other app keeps controller focus and its selected Android keyboard. */
@Composable
internal fun QwertyKeyboardApp(window: KeyboardWindow) {
    val status by window.status.collectAsState()
    val target = window.target
    val output = window.output
    val screen = if (target == TaskSwitcher.TOP) "top screen" else "bottom screen"
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            Text("Keyboard", style = MaterialTheme.typography.titleLarge)
            Text(
                if (target == null) "Types into the app on the other screen" else "Touch to type on the $screen; the controller stays there.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val error by remember(output) { output?.error ?: MutableStateFlow<String?>(null) }.collectAsState()
            error?.let { Note(it, error = true) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                target == null -> KeyboardNotice(
                    "No other screen to type into",
                    "This touch keyboard needs two screens. Your normal Android keyboard is unchanged."
                )
                output == null || status != ScreenMover.Status.READY -> ShizukuNotice(window, status)
                else -> {
                    val send = remember(output) {
                        { sent: QwertySend ->
                            when (sent) {
                                is QwertySend.Text -> output.text(sent.text)
                                is QwertySend.Key -> output.key(sent.code)
                            }
                        }
                    }
                    QwertyKeyboard(send, Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        HintBar()
    }
}

@Composable
private fun ShizukuNotice(window: KeyboardWindow, status: ScreenMover.Status) {
    val text = when (status) {
        ScreenMover.Status.PERMISSION_NEEDED -> "Shizuku is running, but Sortie isn't allowed to use it yet. Allow it, then type."
        ScreenMover.Status.NOT_RUNNING -> "Shizuku isn't running. Start it in the Shizuku app, then check again."
        ScreenMover.Status.UNAVAILABLE -> "Typing into another app needs Shizuku. Install and start it, then allow Sortie to use it."
        ScreenMover.Status.READY -> "Connecting to the other screen."
    }
    KeyboardNotice("Typing needs Shizuku", text) {
        if (status == ScreenMover.Status.PERMISSION_NEEDED) {
            GlowButton("Allow Sortie in Shizuku", primary = true) { window.mover.requestPermission() }
        }
        GlowButton("Check again") { window.mover.refresh() }
    }
}

@Composable
private fun KeyboardNotice(title: String, text: String, actions: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}
