package io.github.esko997.sortie.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.AppEntry
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutEntry
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.launch.IntentBuilder
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
    // Screen-level scope: a launch's controller hand-off must outlive the launcher list, which closes on the tap.
    val scope = rememberCoroutineScope()
    LaunchedEffect(switcher.open) { if (switcher.open) launcher = false }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        when {
            switcher.open -> TouchSwitcher(graph.switcher, switcher, displayId)
            launcher -> TouchLauncher(graph, displayId, scope, onClose = { launcher = false })
            else -> Box(Modifier.fillMaxSize()) {
                // Exactly one of these is composed: the hero ticker only runs while nothing is being played.
                val s = session
                if (s != null) NowPlaying(graph, s) else HeroPanel()
                CornerPlus(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { launcher = true }
            }
        }
    }
}

/** The selected game, else a hero that changes every 9 s ([Selection.panel]); composed only while no session is open. */
@Composable
private fun HeroPanel() {
    val panel = remember { Selection.panel() }
    val game by panel.collectAsState(null)
    val g = game
    Panel(title = g?.title ?: "Sortie", subtitle = g?.let(::gameLine).orEmpty()) {
        val art = g?.screenshot ?: g?.cover
        if (art != null) CoverImage(art, Modifier.fillMaxSize(), ContentScale.Fit)
    }
}

/** The open session: a game's art and hook, or an app's/shortcut's icon and label, with the minutes played so far. */
@Composable
private fun NowPlaying(graph: SortieApp.Graph, session: SessionEntity) {
    val id = session.gameId
    val status = "Playing · ${playingMinutes(session.startedAt)} min"
    if (LibraryItem.isGameId(id)) {
        val game by produceState<GameEntity?>(null, id) { value = graph.db.games().byId(id) }
        val g = game
        Panel(title = g?.title ?: id.substringAfterLast('/'), subtitle = g?.let(::gameLine).orEmpty(), status = status) {
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

/** Art area on top, then an optional status line, the title and a subtitle; text keeps clear of the corner button. */
@Composable
private fun Panel(title: String, subtitle: String, status: String? = null, art: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { art() }
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
    }
}

private fun gameLine(g: GameEntity): String = g.hook ?: listOfNotNull(g.year?.toString(), g.developer, g.genre).joinToString(" · ")

/** Small translucent round '+' in the corner of the idle panel; opens [TouchLauncher]. */
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
    val apps by graph.apps.apps.collectAsState()
    val allShortcuts by graph.shortcuts.shortcuts.collectAsState()
    val user by graph.user.data.collectAsState()
    LaunchedEffect(Unit) { graph.apps.refresh() }
    val entries = remember(apps, allShortcuts, user.shortcuts) {
        allShortcuts.filter { it.id in user.shortcuts }.map { LibraryItem.Shortcut(it) } + apps.map { LibraryItem.App(it) }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Open on this screen",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        if (entries.isEmpty()) Text("No apps", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(entries, key = { it.id }) { item ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Slate).clickable {
                        onClose()
                        scope.launch {
                            if (!launchHere(context, graph, item, displayId)) return@launch
                            delay(HAND_OFF_DELAY_MS)
                            if (!graph.switcher.anchoredRecently()) graph.switcher.handOff(displayId)
                        }
                    }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(56.dp)) { it.setImageDrawable(iconOf(graph, item)) }
                    Text(
                        item.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** Starts [item] on [displayId] through LaunchManager's side-launch path (logged; no play session, Sortie stays put). */
private suspend fun launchHere(context: Context, graph: SortieApp.Graph, item: LibraryItem, displayId: Int): Boolean = when (item) {
    is LibraryItem.App -> {
        val intent = graph.apps.launchIntent(item.app)
        graph.launcher.launchExternal(item.id, IntentBuilder.describe(intent), displayId) { opts -> context.startActivity(intent, opts) }
    }
    is LibraryItem.Shortcut -> {
        val s = item.shortcut
        graph.launcher.launchExternal(s.id, "startShortcut ${s.pkg}/${s.shortcutId}", displayId) { opts -> graph.shortcuts.launch(s, opts) }
    }
    is LibraryItem.Game -> false
}

private fun resolve(id: String, apps: List<AppEntry>, shortcuts: List<ShortcutEntry>): LibraryItem? = when {
    id.startsWith(LibraryItem.APP_PREFIX) -> apps.firstOrNull { it.id == id }?.let { LibraryItem.App(it) }
    id.startsWith(LibraryItem.SHORTCUT_PREFIX) -> shortcuts.firstOrNull { it.id == id }?.let { LibraryItem.Shortcut(it) }
    else -> null
}

private fun iconOf(graph: SortieApp.Graph, item: LibraryItem): Drawable? = when (item) {
    is LibraryItem.App -> graph.apps.icon(item.app.pkg)
    is LibraryItem.Shortcut -> graph.shortcuts.icon(item.shortcut)
    is LibraryItem.Game -> null
}

private const val MINUTE = 60_000L

/** Long enough for the launched app's first activity to be started, so the anchor is the last start and keeps the controller. */
private const val HAND_OFF_DELAY_MS = 500L

/** This display's running apps; tap raises one here, the button sends it to the other screen. Empty screen offers the others. */
@Composable
private fun TouchSwitcher(switcher: TaskSwitcher, state: TaskSwitcher.State, displayId: Int) {
    val here = state.on(displayId)
    val elsewhere = state.entries.filter { it.task.displayId != displayId }
    val bringing = here.isEmpty()
    val shown = if (bringing) elsewhere else here
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            when {
                !state.ready -> "Switcher needs Shizuku"
                state.entries.isEmpty() -> "Nothing running"
                bringing -> "This screen is free · tap an app to bring it here"
                else -> "On this screen"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.task.id }) { e ->
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(Slate).clickable(enabled = !state.busy) {
                        if (bringing) switcher.moveTo(e, displayId) else switcher.bringToFront(e)
                    }.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(64.dp)) { it.setImageDrawable(e.icon) }
                    Text(e.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    val other = switcher.other(displayId)
                    if (!bringing && other != null) {
                        OutlinedButton(onClick = { switcher.moveTo(e, other) }, enabled = !state.busy) {
                            Text("Other screen")
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { switcher.anchor(displayId) }, enabled = state.entries.isNotEmpty()) { Text("Controller here") }
            OutlinedButton(onClick = { switcher.close() }) { Text("Close") }
        }
    }
}
