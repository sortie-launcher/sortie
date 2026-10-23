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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Sortie's app switcher, controller-driven: one carousel per screen with the apps running there. A raises the focused app on
 * its screen (or brings it to a free screen), Y sends it to the other screen, Select closes it. Reached from the Home button
 * while something is running, or from the home screen. The companion screen mirrors its own carousel for touch.
 */
@Composable
fun SwitcherScreen(keys: Flow<Hotkeys.Key> = Hotkeys.events, claimHotkeys: Boolean = true, owner: Boolean = true) {
    val switcher = (LocalContext.current.applicationContext as SortieApp).graph.switcher
    val state by switcher.state.collectAsState()
    var focused by remember { mutableStateOf<TaskSwitcher.Entry?>(null) }
    var focusedScreen by remember { mutableStateOf(TaskSwitcher.TOP) }
    val secondary = remember { switcher.secondaryDisplay() }
    // As a navigation route the screen opens and closes the switcher state itself; inside the overlay the host does.
    if (owner) {
        DisposableEffect(Unit) {
            switcher.open()
            onDispose { switcher.close() }
        }
    }
    // The focused card's entry is a snapshot from when it took focus; a move may have changed its display since, so act on
    // the entry with the same task id in the current state.
    val current = { focused?.let { f -> state.entries.firstOrNull { it.task.id == f.task.id } } }
    LaunchedEffect(Unit) {
        if (claimHotkeys) Hotkeys.handle(setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT))
        try {
            keys.collect { key ->
                val e = current() ?: return@collect
                when (key) {
                    Hotkeys.Key.FAVOURITE -> switcher.other(e.task.displayId)?.let { switcher.moveTo(e, it) }
                    Hotkeys.Key.CONTEXT -> switcher.closeApp(e)
                    else -> Unit
                }
            }
        } finally {
            if (claimHotkeys) Hotkeys.release(setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT))
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
            // The first card takes the controller focus when the list first has cards (coming back from a game leaves nothing
            // focused and Y / Select would act on nothing) and again on the freed row after a move. Leaving a card for the
            // buttons below must not pull it back. In the overlay the window gains focus only after the controller is anchored
            // to its display, a few hundred ms later; keep asking (and leave touch input mode) until a card reports focus.
            val requesters = remember { listOfNotNull(TaskSwitcher.TOP, secondary).associateWith { FocusRequester() } }
            val onFocus = { e: TaskSwitcher.Entry, d: Int ->
                focused = e
                focusedScreen = d
            }
            val onBlur = { e: TaskSwitcher.Entry, _: Int -> if (focused === e) focused = null }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(state.entries.isNotEmpty(), state.freed) {
                if (state.entries.isEmpty()) return@LaunchedEffect
                val target = requesters[state.freed ?: TaskSwitcher.TOP] ?: return@LaunchedEffect
                // after a move the card that was focused may have left its row (or stayed, same key: no focus event either way)
                if (state.freed != null && focusedScreen != state.freed) focused = null
                repeat(50) {
                    if (focused != null) return@LaunchedEffect
                    @OptIn(ExperimentalComposeUiApi::class)
                    inputMode.requestInputMode(InputMode.Keyboard)
                    runCatching { target.requestFocus() }
                    delay(40)
                }
            }
            ScreenRow("Top screen", TaskSwitcher.TOP, state, switcher, requesters[TaskSwitcher.TOP], onFocus, onBlur)
            if (secondary != null) ScreenRow("Bottom screen", secondary, state, switcher, requesters[secondary], onFocus, onBlur)
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
        val cur = current()
        HintBar(
            "A" to (if (cur != null && cur.task.displayId != focusedScreen) "bring here" else "to front"),
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
    val (shown, bringing) = state.offers(displayId)
    Text(
        when {
            displayId == state.freed -> "$title · freed · choose what goes here"
            bringing && shown.isNotEmpty() -> "$title · free · bring an app here"
            else -> title
        },
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
    )
    if (shown.isEmpty()) {
        Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
        itemsIndexed(shown, key = { _, e -> "$displayId/${e.task.id}" }) { i, e ->
            val elsewhere = e.task.displayId != displayId
            FocusRow(
                onClick = { if (elsewhere) switcher.moveTo(e, displayId) else switcher.bringToFront(e) },
                modifier = Modifier.width(150.dp)
                    .then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocus(e, displayId) else onBlur(e, displayId) }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(56.dp)) { it.setImageDrawable(e.icon) }
                    Spacer(Modifier.size(6.dp))
                    Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    val note = if (elsewhere) "other screen" else if (e !== state.on(displayId).firstOrNull()) "behind" else null
                    if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
