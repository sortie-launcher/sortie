package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.folders.FolderView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** Select toggles list↔grid on a browse screen; one persisted setting (`user.browseLayout`) for all of them, separate from home. */
@Composable
fun BrowseLayoutHotkey(vm: SortieViewModel, grid: Boolean) {
    val current by rememberUpdatedState(grid)
    DisposableEffect(Unit) {
        Hotkeys.handle(setOf(Hotkeys.Key.CONTEXT))
        onDispose { Hotkeys.release(setOf(Hotkeys.Key.CONTEXT)) }
    }
    LaunchedEffect(Unit) { Hotkeys.events.collect { if (it == Hotkeys.Key.CONTEXT) vm.setBrowseLayout(if (current) "list" else "grid") } }
}

/** Browse grid (or, with one column, list) with the shared spacing; [grid] picks `user.gridColumns` square tiles. */
@Composable
private fun BrowseGrid(grid: Boolean, columns: Int, modifier: Modifier, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        GridCells.Fixed(if (grid) columns.coerceAtLeast(1) else 1),
        modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content
    )
}

/**
 * Hold-A / long-press sheet for a game, app or shortcut: Favourite · Add to (Remove from) home, plus for shortcuts whether the
 * Apps screen lists them (`user.shortcuts`).
 */
@Composable
fun ItemSheet(item: LibraryItem, vm: SortieViewModel, onDismiss: () -> Unit) {
    val user by vm.user.collectAsState()
    val context = LocalContext.current
    val actions = buildList {
        add(SheetAction(if (item.id in user.favourites) "Remove from favourites" else "Favourite") { vm.toggleFavourite(item.id) })
        add(homeAction(vm, item.id, item.id in user.layout, context))
        if (item is LibraryItem.Shortcut) {
            val shown = item.id in user.shortcuts
            add(SheetAction(if (shown) "Hide from Apps" else "Show in Apps") { vm.showShortcut(item.id, !shown) })
        }
    }
    ContextSheet(item.label, actions, onDismiss)
}

/** One game, app or shortcut on a browse screen: a square tile in grid view, a row in list view. */
@Composable
fun ItemCell(
    item: LibraryItem,
    grid: Boolean,
    vm: SortieViewModel,
    favourite: Boolean,
    minutes: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    appLabel: (String) -> String = { it },
    hasSave: Boolean = false
) {
    when (item) {
        is LibraryItem.Game -> {
            val g = item.game
            if (grid) {
                SquareTile(g.title, onClick, cover = g.cover ?: g.screenshot, paletteGlow = true, favourite = favourite, onLongClick = onLongClick)
            } else {
                GameRow(g, minutes, onClick = onClick, onLongClick = onLongClick, hasSave = hasSave)
            }
        }
        is LibraryItem.App -> if (grid) {
            SquareTile(item.label, onClick, favourite = favourite, icon = { vm.appIcon(item.app.pkg) }, onLongClick = onLongClick)
        } else {
            LauncherRow(item.label, "app · ${item.app.pkg}", favourite, onClick, onLongClick, minutes, tall = true) { vm.appIcon(item.app.pkg) }
        }
        is LibraryItem.Shortcut -> if (grid) {
            SquareTile(item.label, onClick, favourite = favourite, icon = { vm.shortcutIcon(item.shortcut) }, onLongClick = onLongClick)
        } else {
            LauncherRow(item.label, "shortcut · ${appLabel(item.shortcut.pkg)}", favourite, onClick, onLongClick, minutes, tall = true) {
                vm.shortcutIcon(item.shortcut)
            }
        }
    }
}

@Composable
fun GameListScreen(title: String, games: List<GameEntity>, vm: SortieViewModel, onGame: (String) -> Unit, subtitle: String? = null) {
    val items = remember(games) { games.map { LibraryItem.Game(it) } }
    ItemListScreen(title, items, vm, onGame, subtitle)
}

/** A smart folder: games open their detail screen, apps and shortcuts launch directly; hold A for favourite / add to home. */
@Composable
fun FolderScreen(view: FolderView, vm: SortieViewModel, onGame: (String) -> Unit) {
    ItemListScreen(view.def.label, view.items, vm, onGame, view.problems.takeIf { it.isNotEmpty() }?.joinToString("; "))
}

@Composable
private fun ItemListScreen(title: String, items: List<LibraryItem>, vm: SortieViewModel, onGame: (String) -> Unit, subtitle: String?) {
    val stats by vm.playStats.collectAsState()
    val saves by vm.saveStems.collectAsState()
    val user by vm.user.collectAsState()
    val apps by vm.apps.collectAsState()
    val appLabels = remember(apps) { apps.associate { it.pkg to it.label } }
    var minFit by rememberSaveable { mutableStateOf(0) }
    var onDeviceOnly by rememberSaveable { mutableStateOf(true) }
    var notesOnly by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val grid = user.browseLayout == "grid"
    BrowseLayoutHotkey(vm, grid)
    val shown = remember(items, minFit, onDeviceOnly, notesOnly) {
        items.filter { item ->
            when (item) {
                is LibraryItem.Game -> item.game.let { (!onDeviceOnly || !it.placeholder) && it.fit >= minFit && (!notesOnly || it.handWritten) }
                else -> minFit == 0 && !notesOnly // fit and notes are game properties
            }
        }
    }
    val open: (LibraryItem) -> Unit = { if (!vm.launchItem(it)) onGame(it.id) }
    Column(Modifier.fillMaxSize()) {
        BrowseGrid(grid, user.gridColumns, Modifier.weight(1f)) {
            fullItem("header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Column {
                        Text("$title · ${shown.size}", style = MaterialTheme.typography.titleLarge)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { shown.randomOrNull()?.let(open) }, enabled = shown.isNotEmpty()) { Text("🎲 Pick one") }
                    FilterChip(selected = minFit >= 4, onClick = { minFit = if (minFit >= 4) 0 else 4 }, label = { Text("★★★★+") })
                    FilterChip(selected = notesOnly, onClick = { notesOnly = !notesOnly }, label = { Text("✍ notes") })
                    FilterChip(selected = onDeviceOnly, onClick = { onDeviceOnly = !onDeviceOnly }, label = { Text("on device") })
                }
            }
            items(shown, key = { it.id }) { item ->
                ItemCell(
                    item,
                    grid,
                    vm,
                    favourite = item.id in user.favourites,
                    minutes = stats[item.id]?.minutes,
                    onClick = { open(item) },
                    onLongClick = { sheet = item },
                    appLabel = { appLabels[it] ?: it },
                    hasSave = item is LibraryItem.Game && item.game.fileName.substringBeforeLast('.').lowercase() in saves
                )
            }
        }
        HintBar("A" to "open", "hold A" to "favourite / add to home", "Select" to if (grid) "list view" else "grid view", "L1/R1" to "systems", "B" to "back")
    }
    sheet?.let { ItemSheet(it, vm) { sheet = null } }
}

@Composable
fun SearchScreen(vm: SortieViewModel, onGame: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val results by remember(q) {
        if (q.trim().length >= 2) {
            vm.search(q.trim()).let { f ->
                flow {
                    delay(200)
                    f.collect { emit(it) }
                }
            }
        } else {
            flowOf(emptyList())
        }
    }.collectAsState(emptyList())
    val stats by vm.playStats.collectAsState()
    val user by vm.user.collectAsState()
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val grid = user.browseLayout == "grid"
    BrowseLayoutHotkey(vm, grid)
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            q,
            { q = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("title, genre, developer, description…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        BrowseGrid(grid, user.gridColumns, Modifier.weight(1f).padding(top = 8.dp)) {
            items(results, key = { it.id }) { g ->
                val item = LibraryItem.Game(g)
                ItemCell(
                    item,
                    grid,
                    vm,
                    favourite = g.id in user.favourites,
                    minutes = stats[g.id]?.minutes,
                    onClick = { onGame(g.id) },
                    onLongClick = { sheet = item }
                )
            }
        }
        HintBar("A" to "open", "hold A" to "favourite / add to home", "Select" to if (grid) "list view" else "grid view", "B" to "back")
    }
    sheet?.let { ItemSheet(it, vm) { sheet = null } }
}
