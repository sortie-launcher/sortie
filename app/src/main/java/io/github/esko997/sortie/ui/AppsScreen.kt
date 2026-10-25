package io.github.esko997.sortie.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.apps.ShortcutCreator

/**
 * Installed apps plus the launcher shortcuts the user opted in to (`user.shortcuts`): ones pinned via ShortcutPinActivity or
 * pulled here with "Add shortcut from…" (an app's `ACTION_CREATE_SHORTCUT` activity) are opted in automatically; "Browse all
 * shortcuts…" lists every pinned, dynamic and manifest shortcut with a toggle. Select switches list↔grid (`user.browseLayout`),
 * hold A opens Favourite · Add to home.
 */
@Composable
fun AppsScreen(vm: SortieViewModel) {
    val apps by vm.apps.collectAsState()
    val allShortcuts by vm.shortcuts.collectAsState()
    val user by vm.user.collectAsState()
    val stats by vm.playStats.collectAsState()
    val context = LocalContext.current
    val shortcuts = remember(allShortcuts, user.shortcuts) { allShortcuts.filter { it.id in user.shortcuts } }
    val appLabels = remember(apps) { apps.associate { it.pkg to it.label } }
    val appLabel = { pkg: String -> appLabels[pkg] ?: pkg }
    var showCreators by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val grid = user.browseLayout == "grid"
    BrowseLayoutHotkey(vm, grid)
    val creators by produceState(emptyList<ShortcutCreator>(), showCreators) { if (showCreators) value = vm.shortcutCreators() }
    val createShortcut = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val label = vm.acceptShortcut(r.data)
        val message = label?.let { "Added to Sortie: $it" } ?: "That app returned an old-style shortcut; Sortie can't use it"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (label != null) showCreators = false
    }
    val gridState = rememberLazyGridState()
    val memory = rememberGridMemory()
    // every grid item's key, in order, for restoring the focus and position by key
    val gridKeys = buildList<Any> {
        add("title")
        add("add-shortcut")
        if (showCreators) creators.forEach { add("create:" + it.component.flattenToShortString()) }
        add("browse-shortcuts")
        if (showAll) {
            if (allShortcuts.isEmpty()) add("no-shortcuts")
            allShortcuts.forEach { add("all:" + it.id) }
        }
        if (shortcuts.isNotEmpty()) {
            add("h-shortcuts")
            shortcuts.forEach { add(it.id) }
            add("h-apps")
        }
        apps.forEach { add(it.id) }
    }
    RememberGridPosition(memory, gridState, gridKeys)
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(if (grid) user.appColumns.coerceAtLeast(1) else 1),
            Modifier.weight(1f),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            fullItem("title") { Text("Apps · ${apps.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            fullItem("add-shortcut") {
                RememberedItem(memory, "add-shortcut") { Expander("Add shortcut from…", showCreators) { showCreators = !showCreators } }
            }
            if (showCreators) {
                items(creators, key = { "create:" + it.component.flattenToShortString() }, span = { GridItemSpan(maxLineSpan) }) { c ->
                    LauncherRow(c.label, c.component.packageName, favourite = false, onClick = {
                        runCatching { createShortcut.launch(vm.createShortcutIntent(c)) }
                            .onFailure { Toast.makeText(context, "Can't open ${c.label}: ${it.message}", Toast.LENGTH_SHORT).show() }
                    }) { vm.appIcon(c.component.packageName) }
                }
            }
            fullItem("browse-shortcuts") {
                RememberedItem(memory, "browse-shortcuts") { Expander("Browse all shortcuts… (${allShortcuts.size})", showAll) { showAll = !showAll } }
            }
            if (showAll) {
                if (allShortcuts.isEmpty()) {
                    fullItem("no-shortcuts") {
                        Text(
                            "No launcher shortcuts (Sortie must be the default home app to read them).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                items(allShortcuts, key = { "all:" + it.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                    val shown = s.id in user.shortcuts
                    LauncherRow(
                        (if (shown) "☑ " else "☐ ") + s.label,
                        (if (s.manifestOnly) "app action" else "shortcut") + " · ${appLabel(s.pkg)}" + if (shown) " · in Apps" else "",
                        favourite = false,
                        onClick = { vm.showShortcut(s.id, !shown) }
                    ) { vm.shortcutIcon(s) }
                }
            }
            if (shortcuts.isNotEmpty()) {
                fullItem("h-shortcuts") { SectionTitle("Shortcuts · ${shortcuts.size}") }
                items(shortcuts, key = { it.id }) { s ->
                    val item = LibraryItem.Shortcut(s)
                    RememberedItem(memory, s.id) {
                        ItemCell(
                            item,
                            grid,
                            vm,
                            favourite = s.id in user.favourites,
                            minutes = stats[s.id]?.minutes,
                            onClick = { vm.launchShortcut(s) },
                            onLongClick = { sheet = item },
                            appLabel = appLabel
                        )
                    }
                }
                fullItem("h-apps") { SectionTitle("Apps") }
            }
            items(apps, key = { it.id }) { app ->
                val item = LibraryItem.App(app)
                RememberedItem(memory, app.id) {
                    ItemCell(
                        item,
                        grid,
                        vm,
                        favourite = app.id in user.favourites,
                        minutes = stats[app.id]?.minutes,
                        onClick = { vm.launchApp(app) },
                        onLongClick = { sheet = item }
                    )
                }
            }
        }
        // Apps has no fixed header to fold: its title and shortcut expanders scroll away with the list
        BrowseHintBar(
            "A" to "open / toggle",
            "hold A" to "favourite / add to home",
            "Select" to if (grid) "list view" else "grid view",
            "B" to "back",
            compact = browseCompact(user.browseChrome)
        )
    }
    sheet?.let { ItemSheet(it, vm) { sheet = null } }
}

@Composable
private fun Expander(label: String, open: Boolean, onClick: () -> Unit) {
    FocusRow(onClick = onClick) {
        Text((if (open) "▾ " else "▸ ") + label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/**
 * Row for an app, shortcut or shortcut source: icon, label (★ when favourited), subtitle. [tall] puts the icon in a cover-sized
 * slot so the row lines up with GameRow in mixed folder lists.
 */
@Composable
fun LauncherRow(
    label: String,
    subtitle: String,
    favourite: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    minutes: Int? = null,
    tall: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    icon: () -> Drawable?
) {
    FocusRow(onClick = onClick, onLongClick = onLongClick, onFavourite = onFavourite) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (tall) {
                Box(Modifier.size(56.dp, 72.dp).clip(RoundedCornerShape(6.dp)).background(Slate), contentAlignment = Alignment.Center) {
                    IconView(icon, 44)
                }
            } else {
                IconView(icon, 40)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text((if (favourite) "★ " else "") + label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if ((minutes ?: 0) > 0) Row { Badge("${minutes}m played", Color(0xFF14532D)) }
            }
        }
    }
}

@Composable
internal fun IconView(icon: () -> Drawable?, size: Int) {
    AndroidView(factory = { ImageView(it) }, modifier = Modifier.size(size.dp)) { it.setImageDrawable(icon()) }
}
