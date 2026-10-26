package io.github.esko997.sortie.ui

import android.widget.ImageView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sortie's app switcher, controller-driven: one carousel per screen with the apps running there. A raises the focused app on
 * its screen (or brings it to a free screen), Y sends it to the other screen, Select closes it. Reached from the Home button
 * while something is running, or from the home screen. The companion screen mirrors its own carousel for touch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SwitcherScreen(
    onHome: (Int) -> Unit,
    keys: Flow<Hotkeys.Key> = LocalHotkeys.current.events,
    claimHotkeys: Boolean = true,
    owner: Boolean = true,
    /** Shown at the end of the "Running" title line (the overlay's Perf chip and device stats). */
    header: @Composable () -> Unit = {},
    /** More controller hints, before B (the overlay's R1 → Perf). */
    extraHints: List<Pair<String, String>> = emptyList(),
    /** The card to focus first instead of the first one (coming back from the overlay's Perf page). */
    restoreFocus: CardSpot? = null,
    /** Every card that takes the focus, so the overlay can bring it back after its Perf page. */
    onCardFocused: (CardSpot) -> Unit = {}
) {
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
    // a navigation route claims Y / Select in its own window; the overlay passes its own key flow and claims nothing
    val hotkeys = if (claimHotkeys) LocalHotkeys.current else null
    LaunchedEffect(Unit) {
        val claimed = setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT)
        hotkeys?.handle(claimed)
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
            hotkeys?.release(claimed)
        }
    }
    val picking = state.picking
    if (picking != null) {
        val context = LocalContext.current
        val graph = (context.applicationContext as SortieApp).graph
        val scope = rememberCoroutineScope()
        AppPicker(
            graph,
            if (secondary == null) "Open app" else "Open on the ${if (picking == TaskSwitcher.TOP) "top" else "bottom"} screen",
            columns = 6,
            onClose = switcher::stopPicking,
            onBuiltin = { app -> if (openBuiltin(context, app, picking)) switcher.picked(picking) else switcher.stopPicking() }
        ) { item ->
            scope.launch { if (launchOn(context, graph, item, picking)) switcher.picked(picking) else switcher.stopPicking() }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        // narrow outer padding: the carousels run nearly edge to edge so a focused card's glow is not clipped; text keeps 16dp
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Running", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                header()
            }
            Text(
                when {
                    !state.ready -> "The switcher needs Shizuku running and granted (Diagnostics)."
                    state.entries.isEmpty() -> "Nothing is running."
                    else -> "${state.entries.size} running"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            )
            // The first card takes the controller focus when the list first has cards (coming back from a game leaves nothing
            // focused and Y / Select would act on nothing) and again on the freed row after a move. Leaving a card for the
            // buttons below must not pull it back. In the overlay the window gains focus only after the controller is anchored
            // to its display, a few hundred ms later; keep asking (and leave touch input mode) until a card reports focus.
            // Back from the overlay's Perf page, the card that had the focus before takes it again (if it is still listed).
            val requesters = remember { listOfNotNull(TaskSwitcher.TOP, secondary).associateWith { FocusRequester() } }
            val returning = remember { FocusRequester() }
            var restore by remember { mutableStateOf(restoreFocus) }
            val onFocus = { e: TaskSwitcher.Entry, d: Int ->
                focused = e
                focusedScreen = d
                onCardFocused(CardSpot(d, e.task.id))
            }
            val onBlur = { e: TaskSwitcher.Entry, _: Int -> if (focused === e) focused = null }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(state.entries.isNotEmpty(), state.freed) {
                if (state.entries.isEmpty()) return@LaunchedEffect
                val first = requesters[state.freed ?: TaskSwitcher.TOP] ?: return@LaunchedEffect
                val back = restore?.takeIf { r -> state.freed == null && state.offers(r.row).first.any { it.task.id == r.task } }
                // after a move the card that was focused may have left its row (or stayed, same key: no focus event either way)
                if (state.freed != null && focusedScreen != state.freed) focused = null
                repeat(50) { attempt ->
                    if (focused != null) {
                        restore = null
                        return@LaunchedEffect
                    }
                    @OptIn(ExperimentalComposeUiApi::class)
                    inputMode.requestInputMode(InputMode.Keyboard)
                    // half the tries for the returning card (its row may not have laid it out), then the first one
                    runCatching { (if (back != null && attempt < 25) returning else first).requestFocus() }
                    delay(40)
                }
            }
            val spot = restore
            val row = focusedScreen.takeIf { focused != null }
            ScreenRow(
                if (secondary == null) "Running apps" else "Top screen",
                TaskSwitcher.TOP, row == TaskSwitcher.TOP, state, switcher, requesters[TaskSwitcher.TOP], spot, returning, onFocus, onBlur
            )
            if (secondary != null) {
                ScreenRow("Bottom screen", secondary, row == secondary, state, switcher, requesters[secondary], spot, returning, onFocus, onBlur)
            }
            Box(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp).fillMaxWidth().height(1.dp).background(Hairline))
            // wraps instead of running off the right edge of a narrow screen
            FlowRow(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlowButton("Sortie", enabled = !state.busy) { onHome(focusedScreen) }
                GlowButton("Open app…", enabled = !state.busy) { switcher.startPicking(state.freed ?: focusedScreen) }
                if (secondary != null) {
                    GlowButton("Quick swap", enabled = !state.busy && state.entries.isNotEmpty()) { switcher.quickSwap(TaskSwitcher.TOP, secondary) }
                    GlowButton("Controller → bottom", enabled = state.on(secondary).isNotEmpty()) { switcher.anchor(secondary) }
                    GlowButton("Controller → top", enabled = state.on(TaskSwitcher.TOP).isNotEmpty()) { switcher.anchor(TaskSwitcher.TOP) }
                }
                GlowButton("Refresh", enabled = !state.busy) { switcher.refresh() }
            }
        }
        val cur = current()
        val hints = listOfNotNull(
            "A" to (if (cur != null && cur.task.displayId != focusedScreen) "bring here" else "to front"),
            if (secondary != null) "Y" to "other screen" else null,
            "Select" to "close app"
        ) + extraHints + ("B" to "back")
        HintBar(*hints.toTypedArray())
    }
}

/**
 * One screen's carousel. A free screen lists the apps from the other screen so one can be brought over. [first] is attached
 * to the first card so the screen can give it the controller focus; [returning] to the card at [restore], which the row
 * starts scrolled to so that card is laid out. [active]: the controller focus is on one of this row's cards, marked beside
 * the title so the two rows are told apart at a glance.
 */
@Composable
private fun ScreenRow(
    title: String,
    displayId: Int,
    active: Boolean,
    state: TaskSwitcher.State,
    switcher: TaskSwitcher,
    first: FocusRequester?,
    restore: CardSpot?,
    returning: FocusRequester,
    onFocus: (TaskSwitcher.Entry, Int) -> Unit,
    onBlur: (TaskSwitcher.Entry, Int) -> Unit
) {
    val (shown, bringing) = state.offers(displayId)
    val freed = displayId == state.freed
    RowTitle(
        title,
        active,
        when {
            freed -> "Freed · choose what goes here"
            bringing && shown.isNotEmpty() -> "Free · bring an app here"
            else -> null
        },
        urgent = freed
    )
    if (shown.isEmpty()) {
        Text(
            if (state.ready) "Nothing running on this screen" else "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).border(1.dp, Hairline, CardShape).padding(horizontal = 16.dp, vertical = 14.dp)
        )
        return
    }
    val returnTask = restore?.takeIf { it.row == displayId }?.task
    val list = rememberLazyListState()
    // the returning card may lie past the right edge, where the row has not composed it: scroll it in so it can take the
    // focus (a card the row has composed, even partly shown, scrolls itself into view when focused)
    LaunchedEffect(returnTask) {
        val index = shown.indexOfFirst { it.task.id == returnTask }
        if (index < 0) return@LaunchedEffect
        val laidOut = snapshotFlow { list.layoutInfo }.first { it.totalItemsCount > 0 }
        if (laidOut.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
    }
    val front = state.on(displayId).firstOrNull()
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
        itemsIndexed(shown, key = { _, e -> "$displayId/${e.task.id}" }) { i, e ->
            val elsewhere = e.task.displayId != displayId
            AppCard(
                e,
                note = if (elsewhere) "other screen" else if (e !== front) "behind" else null,
                onClick = { if (elsewhere) switcher.moveTo(e, displayId) else switcher.bringToFront(e) },
                onClose = { switcher.closeApp(e) },
                // the remaining cards slide into a closed or moved card's place; nothing fades, so a card never lingers
                // focusable after it has gone
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    .then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                    .then(if (e.task.id == returnTask) Modifier.focusRequester(returning) else Modifier)
                    .onFocusChanged { if (it.isFocused) onFocus(e, displayId) else onBlur(e, displayId) }
            )
        }
    }
}

/** A row's title; an amber tick grows beside it while the row holds the focus, and [status] follows ([urgent] in amber). */
@Composable
private fun RowTitle(title: String, active: Boolean, status: String?, urgent: Boolean) {
    val mark by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "rowMark")
    Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 3.dp, height = 18.dp)
                .graphicsLayer {
                    alpha = mark
                    scaleY = 0.3f + 0.7f * mark
                }
                .background(Amber, RoundedCornerShape(2.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = if (urgent) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

private val CardShape = RoundedCornerShape(14.dp)

/** Cards are objects on the dark scrim: Slate lit faintly from the top left, as Sortie's app tiles are. */
private val CardLight = Brush.linearGradient(listOf(Color(0x14FFFFFF), Color.Transparent))

/** The focused card's surface: a step above Slate, so the lift reads even where the glow is faint. */
private val CardLifted = Color(0xFF252D37)

/**
 * One running app: its icon and name, [note] when it is not the one in front on this row's screen. The controller focus lifts
 * the card (holding A sinks it); ✕ closes the app by touch, while the controller uses Select, so the mark never takes focus.
 * Every card has the same height, with or without a note, so a row of them lines up.
 */
@Composable
private fun AppCard(entry: TaskSwitcher.Entry, note: String?, onClick: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .width(150.dp)
            .focusGlow(focused, CardShape, focusedScale = 1.06f, pressed = held)
            .clip(CardShape)
            .background(if (focused) CardLifted else Slate)
            .background(CardLight)
            .confirmHeld { held = it }
            .clickable(interactionSource = interaction, indication = PressVeil, role = Role.Button, onClick = onClick)
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 14.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(56.dp)) { it.setImageDrawable(entry.icon) }
            Spacer(Modifier.size(8.dp))
            Text(
                entry.label,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(note.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Box(
            Modifier.align(Alignment.TopEnd)
                .padding(4.dp)
                .focusProperties { canFocus = false }
                .size(28.dp)
                .clip(CircleShape)
                .background(Ink.copy(alpha = 0.55f))
                .clickable(onClickLabel = "Close app", role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Text("✕", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
