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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.folders.FolderView
import io.github.esko997.sortie.data.folders.folderLayout
import io.github.esko997.sortie.data.folders.folderSortLabel
import io.github.esko997.sortie.data.manifest.GameAnnotation

/**
 * Select toggles list↔grid on a browse screen now showing [grid]; [onLayout] saves the other one ("list" or "grid") where that
 * screen keeps it: `user.browseLayout`, shared by systems, Apps and Search, or a folder's own choice. Separate from home.
 */
@Composable
fun BrowseLayoutHotkey(grid: Boolean, onLayout: (String) -> Unit) {
    val current by rememberUpdatedState(grid)
    val save by rememberUpdatedState(onLayout)
    val hotkeys = LocalHotkeys.current
    DisposableEffect(hotkeys) {
        hotkeys.handle(setOf(Hotkeys.Key.CONTEXT))
        onDispose { hotkeys.release(setOf(Hotkeys.Key.CONTEXT)) }
    }
    LaunchedEffect(hotkeys) { hotkeys.events.collect { if (it == Hotkeys.Key.CONTEXT) save(if (current) "list" else "grid") } }
}

/**
 * Hold-A / long-press sheet for a game, app or shortcut: Favourite · Add to (Remove from) home · Add to folder…, plus Want to
 * play, Hide from folders and, with two screens, Play on the other screen for games, Unhide for anything hidden (the Hidden
 * folder lists them), and for shortcuts whether the Apps screen lists them (`user.shortcuts`). [extra] are the screen's own
 * actions (an in-app folder's Remove from folder).
 */
@Composable
fun ItemSheet(item: LibraryItem, vm: SortieViewModel, extra: List<SheetAction> = emptyList(), onDismiss: () -> Unit) {
    val user by vm.user.collectAsState()
    val folders by vm.folders.collectAsState()
    val context = LocalContext.current
    val otherScreen = (item as? LibraryItem.Game)?.let { playOnOtherScreenAction(vm, it.game) }
    SteppedSheet(item.label, onDismiss) { next ->
        buildList {
            add(SheetAction(if (item.id in user.favourites) "Remove from favourites" else "Favourite") { vm.toggleFavourite(item.id) })
            add(homeAction(vm, item.id, item.id in user.layout, context))
            add(addToFolderAction(vm, folders, item.id, context, next))
            addAll(extra)
            if (item is LibraryItem.Game) add(wantToPlayAction(vm, item.id, item.id in user.wantToPlay, context))
            otherScreen?.let { add(it) }
            val hidden = item.id in user.hidden
            if (hidden || item is LibraryItem.Game) {
                add(
                    SheetAction(if (hidden) "Unhide" else "Hide from folders") {
                        vm.hide(item.id, !hidden)
                        Toast.makeText(context, if (hidden) "Unhidden" else "Hidden from folders; find it in Hidden", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            if (item is LibraryItem.Shortcut) {
                val shown = item.id in user.shortcuts
                add(SheetAction(if (shown) "Hide from Apps" else "Show in Apps") { vm.showShortcut(item.id, !shown) })
            }
        }
    }
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
    val user by vm.user.collectAsState()
    val items = remember(games) { games.map { LibraryItem.Game(it) } }
    ItemListScreen(title, items, vm, onGame, subtitle, grid = user.browseLayout == "grid", onLayout = vm::setBrowseLayout, parent = chrome, onSearch = onSearch)
}

/**
 * A folder: first the folders and systems it holds ([FolderView.children]; A opens them, Back comes back here), then its games,
 * which open their detail screen, and apps and shortcuts, which launch directly; hold A for favourite / add to home / add to
 * folder, R2 Want to play, Y Search limited to the games of the folder and the folders inside it ([onSearch]). In an editable
 * folder (any but the built-in ones) hold A also moves its folders and systems (Move to folder…, its first choice moving them
 * up a level), takes games, apps and shortcuts out (Remove from folder) and picks the game whose cover the folder's tile shows;
 * a folder held anywhere offers Sort by…, an editable one also Rename… and Delete folder….
 *
 * Every folder, built-in ones too, keeps its own order and list or grid: the header's Sort and View show and change them, and
 * Select toggles this folder's view. Until chosen it shows its own order ([FolderView.displaySort]) and the browse layout.
 */
@Composable
fun FolderScreen(
    view: FolderView,
    vm: SortieViewModel,
    onGame: (String) -> Unit,
    onFolder: (String) -> Unit,
    onSystem: (String) -> Unit,
    onSearch: () -> Unit
) {
    val user by vm.user.collectAsState()
    val folders by vm.folders.collectAsState()
    val systems by vm.systems.collectAsState()
    val biosMissing by vm.biosMissing.collectAsState()
    val covers by vm.tileCovers.collectAsState()
    val logos by vm.systemLogos.collectAsState()
    val context = LocalContext.current
    val accent = Accent
    val pointers = view.children.mapNotNull { id ->
        if (id.startsWith(HomeIds.FOLDER)) {
            folders.firstOrNull { it.def.id == id.removePrefix(HomeIds.FOLDER) }?.let { folderTile(it, accent, onFolder) }
        } else {
            val systemId = id.removePrefix(HomeIds.SYSTEM)
            systems.firstOrNull { it.def.id == systemId }?.let { systemTile(it, "", biosMissing[systemId], covers[systemId], logos[systemId], vm, onSystem) }
        }
    }
    var held by remember { mutableStateOf<HomeTile?>(null) }
    val id = view.def.id
    val layout = user.folderLayout(id)
    ItemListScreen(
        view.def.label,
        view.items,
        vm,
        onGame,
        view.problems.takeIf { it.isNotEmpty() }?.joinToString("; "),
        grid = layout == "grid",
        onLayout = { vm.setFolderLayout(id, it) },
        onSearch = onSearch,
        pointers = pointers,
        // a built-in folder's contents stay as its rule made them; any folder in it can still be sorted
        onPointerLongClick = { t -> if (view.editable || folders.any { HomeIds.FOLDER + it.def.id == t.id }) held = t },
        itemActions = { item ->
            listOfNotNull(
                removeFromFolderAction(vm, item.id, view, context).takeIf { view.editable && item.id in view.def.include },
                folderCoverAction(vm, item, view, context)
            )
        },
        holdHint = if (view.editable) "move / remove" else "favourite / add to home",
        menus = listOf(
            HeaderMenu("Sort: ${folderSortLabel(view.displaySort)}", folderSortTitle(view), folderSortChoices(vm, view, user.folderSortChoice(id))),
            HeaderMenu(
                "View: ${layoutName(layout)}",
                "Show “${view.def.label}” as",
                folderLayoutChoices(vm, view, user.folderLayoutChoice(id), user.browseLayout)
            )
        )
    )
    held?.let { t -> SteppedSheet(t.title, { held = null }) { next -> folderTileActions(vm, folders, t.id, view, context, next) } }
}

/** A header button showing a current choice ([label]) that opens a [ContextSheet] of [choices] titled [title]. */
private class HeaderMenu(val label: String, val title: String, val choices: List<SheetAction>)

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
 * either way. A [parent] header (Home's) folds this one with it, and its strip has the chevron. [grid] is the layout shown,
 * which Select toggles through [onLayout]. [onSearch] is Y's scoped Search. [pointers] (a folder's folders and systems) lead
 * the grid, unfiltered; holding one calls [onPointerLongClick]. [itemActions] adds the screen's own actions to an item's
 * hold-A sheet, and [holdHint] says what hold A does. [menus] follow the chips in the full header. In list view Left/Right
 * jump by starting letter while the items are in title order ([LetterJump]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemListScreen(
    title: String,
    items: List<LibraryItem>,
    vm: SortieViewModel,
    onGame: (String) -> Unit,
    subtitle: String?,
    grid: Boolean,
    onLayout: (String) -> Unit,
    parent: BrowseChrome? = null,
    onSearch: (() -> Unit)? = null,
    pointers: List<HomeTile> = emptyList(),
    onPointerLongClick: (HomeTile) -> Unit = {},
    itemActions: (LibraryItem) -> List<SheetAction> = { emptyList() },
    holdHint: String = "favourite / add to home",
    menus: List<HeaderMenu> = emptyList()
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
    BrowseLayoutHotkey(grid, onLayout)
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
    val gridKeys = remember(shown, pointers) { pointers.map { it.id } + shown.map { it.id } }
    RememberGridPosition(memory, gridState, gridKeys)
    // list view only: in a grid Left/Right move between tiles (pointers lead, unsorted, and take no letter)
    val letters = remember(shown, grid) { if (grid) null else initials(shown.map { it.sortKey }) }
    val jumper = rememberLetterJump(memory, gridState)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val open: (LibraryItem) -> Unit = { if (!vm.launchItem(it)) onGame(it.id) }
    // filters that hide everything keep the header open, so they can be turned off again
    val chrome = parent ?: rememberBrowseChrome(user.browseChrome, pinned = items.isNotEmpty() && shown.isEmpty())
    val pickFocus = remember { FocusRequester() }
    var menuOpen by remember { mutableStateOf<Int?>(null) }
    // the menu whose button takes the controller focus back once its sheet has closed and the window has the focus again
    var menuReturn by remember { mutableStateOf<Int?>(null) }
    val menuFocus = remember(menus.size) { List(menus.size) { FocusRequester() } }
    val inputModes = LocalInputModeManager.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(menuOpen, windowFocused) {
        val back = menuReturn ?: return@LaunchedEffect
        if (menuOpen != null || !windowFocused) return@LaunchedEffect
        menuReturn = null
        val target = menuFocus.getOrNull(back) ?: return@LaunchedEffect
        // the header may have folded meanwhile: open it on that button
        if (chrome.expanded) runCatching { target.requestFocus() } else chrome.reveal(target)
    }
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
                        "$title · ${pointers.size + shown.size}",
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
                        "${pointers.size + shown.size}",
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
                    menus.forEachIndexed { i, m ->
                        GlowButton(m.label, Modifier.focusRequester(menuFocus[i])) {
                            menuReturn = i.takeIf { inputModes.inputMode == InputMode.Keyboard }
                            menuOpen = i
                        }
                    }
                    if (parent == null) HideHeaderButton(chrome)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ItemGrid(
                grid,
                user,
                Modifier.fillMaxSize().foldsBrowseChrome(chrome, gridState, pickFocus).letterJump(jumper, letters, gridKeys, pointers.size),
                state = gridState
            ) {
                items(pointers, key = { it.id }) { t ->
                    RememberedItem(memory, t.id) {
                        HomeTileView(
                            t,
                            grid,
                            vm,
                            lifted = false,
                            favourite = false,
                            minutes = null,
                            annotation = null,
                            onClick = t.onClick,
                            onLongClick = { onPointerLongClick(t) },
                            // hold R1: a random game of that folder or system, as on Home
                            onFavourite = randomPick(t, scope, context, onGame),
                            onWantToPlay = null,
                            platform = null
                        )
                    }
                }
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
            if (shown.isEmpty() && pointers.isEmpty()) {
                Text(
                    if (items.isEmpty()) "Nothing here yet." else "Nothing matches these filters. Turn one off above to see more.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                )
            }
            LetterFlash(jumper)
        }
        BrowseHintBar(
            *listOfNotNull(
                "A" to "open",
                "hold A" to holdHint,
                ("Y" to "search here").takeIf { onSearch != null },
                "R2" to "want to play",
                "Select" to if (grid) "list view" else "grid view",
                ("←/→" to "letter").takeIf { letters != null },
                "L1/R1" to "systems",
                "B" to "back"
            ).toTypedArray(),
            compact = chrome.foldable
        )
    }
    sheet?.let { ItemSheet(it, vm, itemActions(it)) { sheet = null } }
    menuOpen?.let { i -> menus.getOrNull(i)?.let { m -> ContextSheet(m.title, m.choices) { menuOpen = null } } }
}
