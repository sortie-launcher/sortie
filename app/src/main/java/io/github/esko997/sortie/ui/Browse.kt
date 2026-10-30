package io.github.esko997.sortie.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.folders.FolderView
import io.github.esko997.sortie.data.manifest.GameAnnotation

/** Select toggles list↔grid on a browse screen; one persisted setting (`user.browseLayout`) for all of them, separate from home. */
@Composable
fun BrowseLayoutHotkey(vm: SortieViewModel, grid: Boolean) {
    val current by rememberUpdatedState(grid)
    val hotkeys = LocalHotkeys.current
    DisposableEffect(hotkeys) {
        hotkeys.handle(setOf(Hotkeys.Key.CONTEXT))
        onDispose { hotkeys.release(setOf(Hotkeys.Key.CONTEXT)) }
    }
    LaunchedEffect(hotkeys) { hotkeys.events.collect { if (it == Hotkeys.Key.CONTEXT) vm.setBrowseLayout(if (current) "list" else "grid") } }
}

/**
 * Hold-A / long-press sheet for a game, app or shortcut: Favourite · Add to (Remove from) home, plus Want to play and, with two
 * screens, Play on the other screen for games, and for shortcuts whether the Apps screen lists them (`user.shortcuts`).
 */
@Composable
fun ItemSheet(item: LibraryItem, vm: SortieViewModel, onDismiss: () -> Unit) {
    val user by vm.user.collectAsState()
    val context = LocalContext.current
    val otherScreen = (item as? LibraryItem.Game)?.let { playOnOtherScreenAction(vm, it.game) }
    val actions = buildList {
        add(SheetAction(if (item.id in user.favourites) "Remove from favourites" else "Favourite") { vm.toggleFavourite(item.id) })
        add(homeAction(vm, item.id, item.id in user.layout, context))
        if (item is LibraryItem.Game) add(wantToPlayAction(vm, item.id, item.id in user.wantToPlay, context))
        otherScreen?.let { add(it) }
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
    hasSave: Boolean = false,
    annotation: GameAnnotation? = null,
    /** A game's system label for the list row's metadata line (see [GameRow]). */
    platform: String? = null
) {
    val context = LocalContext.current
    val fav = {
        val now = vm.toggleFavourite(item.id)
        Toast.makeText(context, if (now) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
    }
    when (item) {
        is LibraryItem.Game -> {
            val g = item.game
            val queue = { toggleWantToPlay(vm, g.id, context) }
            if (grid) {
                SquareTile(
                    displayTitle(g),
                    onClick,
                    modifier = Modifier.heroOnFocus(g).wantToPlayKey(queue),
                    cover = g.cover ?: g.screenshot,
                    paletteGlow = true,
                    favourite = favourite,
                    annotation = annotation,
                    hack = g.hack,
                    onLongClick = onLongClick,
                    onFavourite = fav
                )
            } else {
                GameRow(
                    g,
                    minutes,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    hasSave = hasSave,
                    onFavourite = fav,
                    annotation = annotation,
                    onWantToPlay = queue,
                    favourite = favourite,
                    platform = platform
                )
            }
        }
        is LibraryItem.App -> if (grid) {
            SquareTile(item.label, onClick, favourite = favourite, icon = { vm.appIcon(item.app.pkg) }, onLongClick = onLongClick, onFavourite = fav)
        } else {
            LauncherRow(
                item.label,
                "app · ${item.app.pkg}",
                favourite,
                onClick,
                onLongClick,
                minutes,
                tall = true,
                onFavourite = fav
            ) { vm.appIcon(item.app.pkg) }
        }
        is LibraryItem.Shortcut -> if (grid) {
            SquareTile(item.label, onClick, favourite = favourite, icon = { vm.shortcutIcon(item.shortcut) }, onLongClick = onLongClick, onFavourite = fav)
        } else {
            LauncherRow(item.label, "shortcut · ${appLabel(item.shortcut.pkg)}", favourite, onClick, onLongClick, minutes, tall = true, onFavourite = fav) {
                vm.shortcutIcon(item.shortcut)
            }
        }
    }
}

/**
 * A system's games; [chrome] is the header of a screen this list is part of (Home's By Console), which then folds both.
 * [onSearch] (Y) opens Search limited to this system; null leaves Y to the screen around it.
 */
@Composable
fun GameListScreen(
    title: String,
    games: List<GameEntity>,
    vm: SortieViewModel,
    onGame: (String) -> Unit,
    subtitle: String? = null,
    chrome: BrowseChrome? = null,
    onSearch: (() -> Unit)? = null
) {
    val items = remember(games) { games.map { LibraryItem.Game(it) } }
    ItemListScreen(title, items, vm, onGame, subtitle, chrome, onSearch)
}

/**
 * A smart folder: games open their detail screen, apps and shortcuts launch directly; hold A for favourite / add to home, R2
 * Want to play, Y Search limited to the folder's games ([onSearch]).
 */
@Composable
fun FolderScreen(view: FolderView, vm: SortieViewModel, onGame: (String) -> Unit, onSearch: () -> Unit) {
    ItemListScreen(view.def.label, view.items, vm, onGame, view.problems.takeIf { it.isNotEmpty() }?.joinToString("; "), onSearch = onSearch)
}

/**
 * Y on a system or folder list: [onSearch] opens Search limited to it (Home's Y searches the whole library). Pressed on the
 * controller, Search opens in keyboard (D-pad) mode, as from Home.
 */
@Composable
private fun SearchHereHotkey(onSearch: () -> Unit) {
    val current by rememberUpdatedState(onSearch)
    val hotkeys = LocalHotkeys.current
    val inputModes = LocalInputModeManager.current
    DisposableEffect(hotkeys) {
        hotkeys.handle(setOf(Hotkeys.Key.FAVOURITE))
        onDispose { hotkeys.release(setOf(Hotkeys.Key.FAVOURITE)) }
    }
    LaunchedEffect(hotkeys) {
        hotkeys.events.collect {
            if (it != Hotkeys.Key.FAVOURITE) return@collect
            @OptIn(ExperimentalComposeUiApi::class)
            inputModes.requestInputMode(InputMode.Keyboard)
            current()
        }
    }
}

/**
 * A system's or folder's games, apps and shortcuts with Pick one and the filter chips above them. The header folds while
 * browsing (see [BrowseChrome]) to one line with the count and the filters that are on; a problem [subtitle] stays shown
 * either way. A [parent] header (Home's) folds this one with it, and its strip has the chevron. [onSearch] is Y's scoped Search.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemListScreen(
    title: String,
    items: List<LibraryItem>,
    vm: SortieViewModel,
    onGame: (String) -> Unit,
    subtitle: String?,
    parent: BrowseChrome? = null,
    onSearch: (() -> Unit)? = null
) {
    val stats by vm.playStats.collectAsState()
    val saves by vm.saveStems.collectAsState()
    val user by vm.user.collectAsState()
    val apps by vm.apps.collectAsState()
    val manifests by vm.manifests.collectAsState()
    val appLabels = remember(apps) { apps.associate { it.pkg to it.label } }
    val systemLabels = remember(manifests) { manifests.systems.associate { it.id to it.label } }
    var minFit by rememberSaveable { mutableStateOf(0) }
    var onDeviceOnly by rememberSaveable { mutableStateOf(true) }
    var notesOnly by rememberSaveable { mutableStateOf(false) }
    var startedOnly by rememberSaveable { mutableStateOf(false) } // games with a save or state on the card
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val grid = user.browseLayout == "grid"
    BrowseLayoutHotkey(vm, grid)
    onSearch?.let { SearchHereHotkey(it) }
    val shown = remember(items, minFit, onDeviceOnly, notesOnly, startedOnly, saves, user.bookmarks) {
        items.filter { item ->
            when (item) {
                is LibraryItem.Game -> item.game.let {
                    // a hand-written guide entry or the player's own bookmark
                    val hasNotes = it.handWritten || it.id in user.bookmarks
                    (!onDeviceOnly || !it.placeholder) && it.fit >= minFit && (!notesOnly || hasNotes) && (!startedOnly || it.saveStem() in saves)
                }
                else -> minFit == 0 && !notesOnly && !startedOnly // fit, notes and saves are game properties
            }
        }
    }
    val gridState = rememberLazyGridState()
    val memory = rememberGridMemory()
    RememberGridPosition(memory, gridState, remember(shown) { shown.map { it.id } })
    val open: (LibraryItem) -> Unit = { if (!vm.launchItem(it)) onGame(it.id) }
    // filters that hide everything keep the header open, so they can be turned off again
    val chrome = parent ?: rememberBrowseChrome(user.browseChrome, pinned = items.isNotEmpty() && shown.isEmpty())
    val pickFocus = remember { FocusRequester() }
    val activeFilters = listOfNotNull(
        "Fit 4+".takeIf { minFit >= 4 },
        "✍ notes".takeIf { notesOnly },
        "💾 started".takeIf { startedOnly },
        "incl. not on device".takeIf { !onDeviceOnly }
    )
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().foldingHeader()) {
            if (!chrome.expanded) {
                FoldedHeader(chrome, toggle = parent == null) {
                    Text(
                        "$title · ${shown.size}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (activeFilters.isNotEmpty()) {
                        Text(
                            activeFilters.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge,
                            color = Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                return@Column
            }
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // the name leads; the count is a quieter fact beside it
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline()
                    )
                    Text(
                        "${shown.size}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline()
                    )
                }
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GlowButton("🎲 Pick one", Modifier.focusRequester(pickFocus), enabled = shown.isNotEmpty()) { shown.randomOrNull()?.let(open) }
                    GlowChip("Fit 4+", minFit >= 4) { minFit = if (minFit >= 4) 0 else 4 }
                    GlowChip("✍ notes", notesOnly) { notesOnly = !notesOnly }
                    GlowChip("💾 started", startedOnly) { startedOnly = !startedOnly }
                    GlowChip("on device", onDeviceOnly) { onDeviceOnly = !onDeviceOnly }
                    if (parent == null) HideHeaderButton(chrome)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ItemGrid(grid, user, Modifier.fillMaxSize().foldsBrowseChrome(chrome, gridState, pickFocus), state = gridState) {
                items(shown, key = { it.id }) { item ->
                    RememberedItem(memory, item.id) {
                        ItemCell(
                            item,
                            grid,
                            vm,
                            favourite = item.id in user.favourites,
                            minutes = stats[item.id]?.minutes,
                            annotation = user.gameAnnotations[item.id],
                            onClick = { open(item) },
                            onLongClick = { sheet = item },
                            appLabel = { appLabels[it] ?: it },
                            hasSave = item is LibraryItem.Game && item.game.saveStem() in saves,
                            platform = (item as? LibraryItem.Game)?.game?.systemId?.let { systemLabels[it]?.takeIf(String::isNotBlank) ?: it }
                        )
                    }
                }
            }
            // an empty grid says why: the filters above (kept open) hide everything, or there is nothing here yet
            if (shown.isEmpty()) {
                Text(
                    if (items.isEmpty()) "Nothing here yet." else "Nothing matches these filters. Turn one off above to see more.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
        }
        BrowseHintBar(
            *listOfNotNull(
                "A" to "open",
                "hold A" to "favourite / add to home",
                ("Y" to "search here").takeIf { onSearch != null },
                "R2" to "want to play",
                "Select" to if (grid) "list view" else "grid view",
                "L1/R1" to "systems",
                "B" to "back"
            ).toTypedArray(),
            compact = chrome.foldable
        )
    }
    sheet?.let { ItemSheet(it, vm) { sheet = null } }
}
