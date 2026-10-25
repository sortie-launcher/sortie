package io.github.esko997.sortie.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.launch.IntentBuilder

/**
 * Installed apps and opted-in shortcuts as a filterable icon grid, for opening something on a chosen display: the companion
 * '+' launcher (touch), and the switcher on both screens. With [onBuiltin] Sortie's own apps (Magazines, Performance) lead
 * the grid. The filter field is touch/IME; the grid takes the D-pad.
 */
@Composable
fun AppPicker(
    graph: SortieApp.Graph,
    title: String,
    columns: Int = 4,
    onClose: () -> Unit,
    onBuiltin: ((BuiltinApp) -> Unit)? = null,
    onPick: (LibraryItem) -> Unit
) {
    val context = LocalContext.current
    val apps by graph.apps.apps.collectAsState()
    val allShortcuts by graph.shortcuts.shortcuts.collectAsState()
    val user by graph.user.data.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (apps.isEmpty()) graph.apps.refresh() }
    val entries = remember(apps, allShortcuts, user.shortcuts, query) {
        val all = allShortcuts.filter { it.id in user.shortcuts }.map { LibraryItem.Shortcut(it) } + apps.map { LibraryItem.App(it) }
        val q = query.trim()
        if (q.isEmpty()) all else all.filter { it.label.contains(q, ignoreCase = true) }
    }
    val offersBuiltins = onBuiltin != null
    val builtins = remember(offersBuiltins, query) {
        if (offersBuiltins) BuiltinApp.entries.filter { it.label.contains(query.trim(), ignoreCase = true) } else emptyList()
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            GlowButton("Close", onClick = onClose)
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        )
        if (entries.isEmpty() && builtins.isEmpty()) Text("No apps", color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(builtins, key = { "builtin:${it.name}" }) { app ->
                PickerCell(app.label, { app.icon(context) }) { onBuiltin?.invoke(app) }
            }
            items(entries, key = { it.id }) { item ->
                PickerCell(item.label, { iconOf(graph, item) }) { onPick(item) }
            }
        }
    }
}

@Composable
private fun PickerCell(label: String, icon: () -> Drawable?, onClick: () -> Unit) {
    FocusRow(onClick = onClick) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(56.dp)) { it.setImageDrawable(icon()) }
            Text(
                label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Starts [item] on [displayId] through LaunchManager's side-launch path (logged; no play session, Sortie stays put). */
suspend fun launchOn(context: Context, graph: SortieApp.Graph, item: LibraryItem, displayId: Int): Boolean = when (item) {
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

fun iconOf(graph: SortieApp.Graph, item: LibraryItem) = when (item) {
    is LibraryItem.App -> graph.apps.icon(item.app.pkg)
    is LibraryItem.Shortcut -> graph.shortcuts.icon(item.shortcut)
    is LibraryItem.Game -> null
}
