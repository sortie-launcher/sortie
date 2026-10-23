package io.github.esko997.sortie.ui

import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.TaskSwitcher

/**
 * Sortie's app switcher, controller-driven: one carousel per screen with the apps running there. A raises the focused app on
 * its screen (or brings it to a free screen), Y sends it to the other screen, Select closes it. Reached from the Home button
 * while something is running, or from the home screen. The companion screen mirrors its own carousel for touch.
 */
@Composable
fun SwitcherScreen() {
    val switcher = (LocalContext.current.applicationContext as SortieApp).graph.switcher
    val state by switcher.state.collectAsState()
    var focused by remember { mutableStateOf<TaskSwitcher.Entry?>(null) }
    var focusedScreen by remember { mutableStateOf(TaskSwitcher.TOP) }
    val secondary = remember { switcher.secondaryDisplay() }
    DisposableEffect(Unit) {
        switcher.open()
        onDispose { switcher.close() }
    }
    LaunchedEffect(Unit) {
        Hotkeys.handle(setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT))
        try {
            Hotkeys.events.collect { key ->
                val e = focused ?: return@collect
                when (key) {
                    Hotkeys.Key.FAVOURITE -> switcher.other(e.task.displayId)?.let { switcher.moveTo(e, it) }
                    Hotkeys.Key.CONTEXT -> switcher.closeApp(e)
                    else -> Unit
                }
            }
        } finally {
            Hotkeys.release(setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Running", style = MaterialTheme.typography.titleLarge)
            Text(
                when {
                    !state.ready -> "The switcher needs Shizuku running and granted (Diagnostics)."
                    state.entries.isEmpty() -> "Nothing is running."
                    else -> "${state.entries.size} running"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            // The first card takes the controller focus once, when the list first has cards (coming back from a game leaves
            // nothing focused and Y / Select would act on nothing). Leaving a card for the buttons below must not pull it back.
            val first = remember { FocusRequester() }
            val onFocus = { e: TaskSwitcher.Entry, d: Int ->
                focused = e
                focusedScreen = d
            }
            val onBlur = { e: TaskSwitcher.Entry, _: Int -> if (focused === e) focused = null }
            LaunchedEffect(state.entries.isNotEmpty()) { if (state.entries.isNotEmpty()) runCatching { first.requestFocus() } }
            ScreenRow("Top screen", TaskSwitcher.TOP, state, switcher, first, onFocus, onBlur)
            if (secondary != null) ScreenRow("Bottom screen", secondary, state, switcher, null, onFocus, onBlur)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (secondary != null) {
                    OutlinedButton(onClick = { switcher.quickSwap(TaskSwitcher.TOP, secondary) }, enabled = !state.busy && state.entries.isNotEmpty()) {
                        Text("Quick swap")
                    }
                    OutlinedButton(onClick = { switcher.anchor(secondary) }, enabled = state.on(secondary).isNotEmpty()) { Text("Controller → bottom") }
                }
                OutlinedButton(onClick = { switcher.anchor(TaskSwitcher.TOP) }, enabled = state.on(TaskSwitcher.TOP).isNotEmpty()) { Text("Controller → top") }
                OutlinedButton(onClick = { switcher.refresh() }, enabled = !state.busy) { Text("Refresh") }
            }
        }
        HintBar(
            "A" to (if (focused != null && focused!!.task.displayId != focusedScreen) "bring here" else "to front"),
            "Y" to "other screen",
            "Select" to "close app",
            "B" to "back"
        )
    }
}

/**
 * One screen's carousel. A free screen lists the apps from the other screen so one can be brought over. [first] is attached
 * to the first card so the screen can give it the controller focus.
 */
@Composable
private fun ScreenRow(
    title: String,
    displayId: Int,
    state: TaskSwitcher.State,
    switcher: TaskSwitcher,
    first: FocusRequester?,
    onFocus: (TaskSwitcher.Entry, Int) -> Unit,
    onBlur: (TaskSwitcher.Entry, Int) -> Unit
) {
    val here = state.on(displayId)
    val bringing = here.isEmpty()
    val shown = if (bringing) state.entries.filter { it.task.displayId != displayId } else here
    Text(
        if (bringing && shown.isNotEmpty()) "$title · free · bring an app here" else title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
    if (shown.isEmpty()) {
        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        itemsIndexed(shown, key = { _, e -> "$displayId/${e.task.id}" }) { i, e ->
            FocusRow(
                onClick = { if (bringing) switcher.moveTo(e, displayId) else switcher.bringToFront(e) },
                modifier = Modifier.width(150.dp)
                    .then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocus(e, displayId) else onBlur(e, displayId) }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(56.dp)) { it.setImageDrawable(e.icon) }
                    Spacer(Modifier.size(6.dp))
                    Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    if (!bringing && i > 0) Text("behind", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
