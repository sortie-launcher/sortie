package io.github.esko997.sortie.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.CompanionHost
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.manifest.DOCK_SLOTS
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.TaskSwitcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the other screen shows: the game being played (while a play session is open), else the selected game's art and hook or
 * a rotating hero; while the switcher is open, the apps running on this very display as a touch carousel. A corner '+' opens a
 * touch list of apps to start on this display. The host hands the controller back to the other screen on every touch, so every
 * control here is touch and nothing requests focus.
 */
@Composable
fun CompanionScreen(displayId: Int) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    val switcher by graph.switcher.state.collectAsState()
    val session by graph.playLog.current.collectAsState()
    var launcher by rememberSaveable { mutableStateOf(false) }
    var dock by rememberSaveable { mutableStateOf(false) }
    var dockSlot by rememberSaveable { mutableStateOf(-1) } // slot being filled from the picker, -1 = none
    // Screen-level scope: a launch's controller hand-off must outlive the launcher list, which closes on the tap.
    val scope = rememberCoroutineScope()
    LaunchedEffect(switcher.open) {
        if (switcher.open) {
            launcher = false
            dockSlot = -1
        }
    }
    // the dock folds away whenever what the panel shows changes, so it never sits over new text
    val selected by Selection.current.collectAsState()
    LaunchedEffect(selected?.id, session?.gameId) { dock = false }
    // the host hands the controller away on every tap; not while a text field here needs the IME
    val host = LocalContext.current as? CompanionHost
    // pickers and the open dock keep the controller here: their slots take the D-pad (A opens, hold A unpins), B closes
    val picking = launcher || dockSlot >= 0 || dock
    LaunchedEffect(picking) {
        if (picking) host?.keepController = true else if (host?.keepController == true) host.releaseController()
    }
    BackHandler(enabled = dock && !launcher && dockSlot < 0) { dock = false }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        when {
            switcher.open && !switcher.overlay -> TouchSwitcher(graph.switcher, switcher, displayId)
            launcher -> TouchLauncher(graph, displayId, scope, onClose = { launcher = false })
            dockSlot >= 0 -> AppPicker(graph, "Add to the dock", onClose = { dockSlot = -1 }) {
                graph.user.setDockSlot(dockSlot, it.id)
                dockSlot = -1
            }
            else -> Box(Modifier.fillMaxSize()) {
                // Exactly one of these is composed: the hero ticker only runs while nothing is being played.
                val s = session
                if (s != null) NowPlaying(graph, s) else HeroPanel(graph)
                CornerPlus(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { launcher = true }
                Dock(graph, displayId, scope, dock, Modifier.align(Alignment.BottomStart).padding(16.dp), onToggle = { dock = !dock }) { dockSlot = it }
                StatusStrip(graph, Modifier.align(Alignment.TopEnd).padding(12.dp))
            }
        }
    }
}

/**
 * The selected game (with its description, notes and play stats mirrored from the detail screen, so they can be read without
 * leaving the list), else a rotating hero ([Selection.panel]); composed only while no session is open.
 */
@Composable
private fun HeroPanel(graph: SortieApp.Graph) {
    val panel = remember { Selection.panel() }
    val game by panel.collectAsState(null)
    val selected by Selection.current.collectAsState()
    val g = game
    val palette = rememberPalette(g?.cover ?: g?.screenshot)
    LaunchedEffect(palette) { Selection.heroPalette.value = palette }
    val detail = if (g != null && g.id == selected?.id) {
        val stats by produceState<PlayStat?>(null, g.id) { value = graph.db.games().playStat(g.id) }
        listOfNotNull(
            stats?.let(::playedLine),
            g.controls?.let { "Controls: $it" },
            g.desc?.takeIf { it.isNotBlank() },
            g.tags.takeIf { it.isNotBlank() }?.let { "Tags: " + it.replace(",", " · ") }
        ).joinToString("\n\n")
    } else {
        null
    }
    Panel(title = g?.let(::displayTitle) ?: "Sortie", subtitle = g?.let(::gameLine).orEmpty(), detail = detail, onTap = g?.let { openOnOtherScreen(it) }) {
        val art = g?.screenshot ?: g?.cover
        if (art != null) CoverImage(art, Modifier.fillMaxSize(), ContentScale.Fit)
    }
}

/** Tapping the hero or now-playing card opens the game's entry in Sortie's navigation, which lives on the other screen. */
private fun openOnOtherScreen(g: GameEntity): () -> Unit = { Selection.openRoute.tryEmit("game/${Uri.encode(g.id)}") }

/** The open session: a game's art and hook, or an app's/shortcut's icon and label, with the minutes played so far. */
@Composable
private fun NowPlaying(graph: SortieApp.Graph, session: SessionEntity) {
    val id = session.gameId
    val status = "Playing · ${playingMinutes(session.startedAt)} min"
    if (LibraryItem.isGameId(id)) {
        val game by produceState<GameEntity?>(null, id) { value = graph.db.games().byId(id) }
        val g = game
        Panel(
            title = g?.let(::displayTitle) ?: id.substringAfterLast('/'),
            subtitle = g?.let(::gameLine).orEmpty(),
            status = status,
            detail = g?.controls?.let { "Controls: $it" },
            onTap = g?.let {
                openOnOtherScreen(it)
            }
        ) {
            val art = g?.screenshot ?: g?.cover
            if (art != null) CoverImage(art, Modifier.fillMaxSize(), ContentScale.Fit)
        }
    } else {
        val apps by graph.apps.apps.collectAsState()
        val shortcuts by graph.shortcuts.shortcuts.collectAsState()
        LaunchedEffect(id) { if (graph.apps.apps.value.isEmpty()) graph.apps.refresh() }
        val item = remember(id, apps, shortcuts) { resolve(id, apps, shortcuts) }
        Panel(title = item?.label ?: id.substringAfter(':'), subtitle = "", status = status) {
            if (item != null) {
                AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(160.dp)) { it.setImageDrawable(iconOf(graph, item)) }
            }
        }
    }
}

/**
 * Whole minutes since [startedAt], recomposed once a minute (aligned to the session's minute boundary) and never faster: the
 * only periodic work the companion does while something is being played.
 */
@Composable
private fun playingMinutes(startedAt: Long): Long {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(MINUTE - (now - startedAt).mod(MINUTE) + 100)
        }
    }
    return ((now - startedAt) / MINUTE).coerceAtLeast(0)
}

/**
 * Art area on top, then an optional status line, the title, a subtitle and an optional scrollable [detail] block (which takes
 * the lower half from the art); text keeps clear of the corner buttons.
 */
@Composable
private fun Panel(title: String, subtitle: String, status: String? = null, detail: String? = null, onTap: (() -> Unit)? = null, art: @Composable () -> Unit) {
    val tap = if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier
    Column(Modifier.fillMaxSize().then(tap).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(if (detail != null) 0.5f else 1f).fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 56.dp, top = if (status != null) 4.dp else 16.dp, end = 56.dp)
        )
        Text(
            subtitle,
            color = Amber,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 56.dp, top = 8.dp, end = 56.dp, bottom = 8.dp)
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.5f).fillMaxWidth().padding(horizontal = 72.dp).verticalScroll(rememberScrollState())
            )
        }
    }
}

private fun gameLine(g: GameEntity): String = g.hook ?: listOfNotNull(g.year?.toString(), g.developer, g.genre).joinToString(" · ")

/**
 * Bottom-left dot that unfurls a column of [DOCK_SLOTS] pinned apps/shortcuts (`user.dock`) up the left edge. Tap (or A on) a
 * slot to start it on this display (same hand-off as [TouchLauncher]); an empty slot picks what goes there; long-press or hold
 * A on a filled slot clears it. While open the slots hold the controller focus (the host keeps it here), starting at slot 0.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Dock(
    graph: SortieApp.Graph,
    displayId: Int,
    scope: CoroutineScope,
    open: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onFill: (Int) -> Unit
) {
    val context = LocalContext.current
    val user by graph.user.data.collectAsState()
    val apps by graph.apps.apps.collectAsState()
    val shortcuts by graph.shortcuts.shortcuts.collectAsState()
    LaunchedEffect(open) { if (open && graph.apps.apps.value.isEmpty()) graph.apps.refresh() }
    val first = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    // Opened by touch the window is in touch mode and the controller arrives a few frames later: ask for keyboard mode and
    // retry until slot 0 actually has the focus (same dance as ContextSheet and the switcher).
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        repeat(30) {
            if (firstFocused) return@LaunchedEffect
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
            delay(50)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (open) {
            // slots run upward: slot 0 sits just above the dot
            for (slot in DOCK_SLOTS - 1 downTo 0) {
                val item = user.dock.getOrNull(slot)?.takeIf { it.isNotEmpty() }?.let { resolve(it, apps, shortcuts) }
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                if (slot == 0) LaunchedEffect(focused) { firstFocused = focused }
                val unpin: (() -> Unit)? = if (item != null) ({ graph.user.setDockSlot(slot, null) }) else null
                Box(
                    Modifier
                        .then(if (slot == 0) Modifier.focusRequester(first) else Modifier)
                        .focusGlow(focused, CircleShape)
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Slate.copy(alpha = if (item == null) 0.35f else 0.8f))
                        // always attached: once the hold has emptied the slot, the handler must still swallow the A release
                        .keyLongPress(unpin ?: {})
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = item?.label ?: "Add to the dock",
                            onLongClick = unpin,
                            onClick = {
                                if (item == null) {
                                    onFill(slot)
                                } else {
                                    onToggle()
                                    scope.launch {
                                        if (!launchOn(context, graph, item, displayId)) return@launch
                                        delay(HAND_OFF_DELAY_MS)
                                        if (!graph.switcher.anchoredRecently()) graph.switcher.handOff(displayId)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (item == null) {
                        Text("+", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
                    } else {
                        AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(40.dp)) { it.setImageDrawable(iconOf(graph, item)) }
                    }
                }
            }
        }
        Box(
            Modifier
                .size(if (open) 20.dp else 14.dp)
                .clip(CircleShape)
                .background(if (open) Amber else Slate.copy(alpha = 0.8f))
                .clickable(onClickLabel = "Dock", onClick = onToggle)
        )
    }
}

/** Small translucent round '+' in the corner of the idle panel; opens [TouchLauncher]. */
/**
 * Battery and a bell, top-right of the idle panel. Battery comes from the sticky ACTION_BATTERY_CHANGED broadcast (no polling;
 * the receiver only lives while the panel is composed). The bell opens the system notification shade on this display over
 * Shizuku (`cmd statusbar expand-notifications`): Sortie does not read notifications itself — that needs a
 * NotificationListenerService, a permanent bound service, which is what kept Cocoon alive in the background.
 */
@Composable
private fun StatusStrip(graph: SortieApp.Graph, modifier: Modifier) {
    val context = LocalContext.current
    var level by remember { mutableStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val l = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                level = if (l < 0) -1 else l * 100 / scale.coerceAtLeast(1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    val shizuku by graph.screenMover.status.collectAsState()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (level >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(22.dp, 11.dp).clip(RoundedCornerShape(2.dp)).background(Slate)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(level / 100f).background(if (level <= 15 && !charging) Color(0xFFE05B4B) else Amber))
                }
                Text((if (charging) "⚡" else "") + "$level%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (shizuku == ScreenMover.Status.READY) {
            Text(
                "🔔",
                fontSize = 18.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClickLabel = "Notifications") { graph.screenMover.expandNotifications() }.padding(4.dp)
            )
        }
    }
}

@Composable
private fun CornerPlus(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Slate.copy(alpha = 0.6f))
            .clickable(onClickLabel = "Open an app here", onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("+", color = Amber, fontSize = 28.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Installed apps and opted-in shortcuts as a 4-column touch grid; a tap starts the entry on this display and closes the list.
 * Starting an activity moves the controller to this display and the companion pauses before the host's debounced touch
 * hand-off runs (onPause cancels it), so the launch hands the controller back to the other screen itself, in [scope] (the
 * screen's, which outlives this list).
 */
@Composable
private fun TouchLauncher(graph: SortieApp.Graph, displayId: Int, scope: CoroutineScope, onClose: () -> Unit) {
    val context = LocalContext.current
    AppPicker(graph, "Open on this screen", onClose = onClose) { item ->
        onClose()
        scope.launch {
            if (!launchOn(context, graph, item, displayId)) return@launch
            delay(HAND_OFF_DELAY_MS)
            if (!graph.switcher.anchoredRecently()) graph.switcher.handOff(displayId)
        }
    }
}

private fun resolve(id: String, apps: List<AppEntry>, shortcuts: List<ShortcutEntry>): LibraryItem? = when {
    id.startsWith(LibraryItem.APP_PREFIX) -> apps.firstOrNull { it.id == id }?.let { LibraryItem.App(it) }
    id.startsWith(LibraryItem.SHORTCUT_PREFIX) -> shortcuts.firstOrNull { it.id == id }?.let { LibraryItem.Shortcut(it) }
    else -> null
}

private const val MINUTE = 60_000L

/** Long enough for the launched app's first activity to be started, so the anchor is the last start and keeps the controller. */
private const val HAND_OFF_DELAY_MS = 500L

/**
 * This display's running apps: tap raises one here, the button sends it to the other screen. A freed or empty screen offers
 * the other windows instead; tapping one brings it here.
 */
@Composable
fun TouchSwitcher(switcher: TaskSwitcher, state: TaskSwitcher.State, displayId: Int) {
    if (state.picking == displayId) {
        val context = LocalContext.current
        val graph = (context.applicationContext as SortieApp).graph
        val scope = rememberCoroutineScope()
        AppPicker(graph, "Open on this screen", onClose = switcher::stopPicking) { item ->
            scope.launch { if (launchOn(context, graph, item, displayId)) switcher.picked(displayId) else switcher.stopPicking() }
        }
        return
    }
    val (shown, bringing) = state.offers(displayId)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            when {
                !state.ready -> "Switcher needs Shizuku"
                state.entries.isEmpty() -> "Nothing running"
                displayId == state.freed -> "This screen was freed · tap what goes here"
                bringing -> "This screen is free · tap an app to bring it here"
                else -> "On this screen"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.task.id }) { e ->
                val elsewhere = e.task.displayId != displayId
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(Slate).clickable(enabled = !state.busy) {
                        if (elsewhere) switcher.moveTo(e, displayId) else switcher.bringToFront(e)
                    }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(64.dp).align(Alignment.Center)) { it.setImageDrawable(e.icon) }
                        Text(
                            "✕",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.TopEnd).clip(CircleShape).clickable(enabled = !state.busy, onClickLabel = "Close app") {
                                switcher.closeApp(e)
                            }.padding(6.dp)
                        )
                    }
                    Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    val other = switcher.other(displayId)
                    if (elsewhere) {
                        Text("other screen", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (!bringing && other != null) {
                        OutlinedButton(onClick = { switcher.moveTo(e, other) }, enabled = !state.busy) {
                            Text("Other screen")
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { switcher.goHome(displayId) }, enabled = !state.busy) { Text("Sortie") }
            OutlinedButton(onClick = { switcher.startPicking(displayId) }, enabled = !state.busy) { Text("Open app…") }
            OutlinedButton(onClick = { switcher.anchor(displayId) }, enabled = state.entries.isNotEmpty()) { Text("Controller here") }
            OutlinedButton(onClick = { switcher.close() }) { Text("Close") }
        }
    }
}
