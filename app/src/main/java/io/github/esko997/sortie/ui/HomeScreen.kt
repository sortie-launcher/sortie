package io.github.esko997.sortie.ui

import android.view.Display
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.GameAnnotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tile ids of the custom home layout (`user.layout`): `system:<id>`, `folder:<id>`, [APPS] for the built-in Apps folder,
 * [CONTINUE] for the last game played, a game's index id (`<system>/<file>`), or the LibraryItem ids `app:<pkg>` and
 * `shortcut:<pkg>/<id>`.
 */
object HomeIds {
    const val SYSTEM = "system:"
    const val FOLDER = "folder:"
    const val APPS = "apps"
    const val CONTINUE = "continue"

    fun isGame(id: String): Boolean = LibraryItem.isGameId(id) && !id.startsWith(SYSTEM) && !id.startsWith(FOLDER) && id != APPS && id != CONTINUE

    /** Continue, smart folders and Apps are always on Custom (the generated sorts hold systems only), so they cannot be removed. */
    fun isAutomatic(id: String): Boolean = id == CONTINUE || id == APPS || id.startsWith(FOLDER)

    /**
     * The Custom home's order: [layout] as saved, plus the automatic entries it does not hold yet — Continue first, then every
     * smart folder ([folders]: tile ids in manifest order) and Apps last. Each id appears once; unresolved ids keep their place.
     */
    fun custom(layout: List<String>, folders: List<String>): List<String> = buildList {
        if (CONTINUE !in layout) add(CONTINUE)
        addAll(layout)
        addAll(folders)
        add(APPS)
    }.distinct()
}

val SORTS = listOf("alpha" to "Alphabetical", "count" to "Game count", "generation" to "Generation", "custom" to "Custom", "console" to "By Console")

private val BITS = Regex("^(\\d+)-bit", RegexOption.IGNORE_CASE)

/** "8-bit" … "128-bit" in ascending order first, then every other generation ("arcade", "handheld", …) in manifest order. */
private fun generationRank(generation: String): Int = BITS.find(generation)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

private val AppsGrey = Color(0xFF9AA3AD)

/**
 * One home entry: a system, or on Custom only, a smart folder, the Apps folder, Continue, a game, app or shortcut. [colour]
 * marks systems and folders, [item] the launchable entries. [id] is the custom-layout tile id; [section] groups the generated sorts.
 */
private class HomeTile(
    val id: String,
    val title: String,
    val subtitle: String,
    val count: Int,
    val section: String,
    val colour: Color? = null,
    val cover: String? = null,
    /** A system's logo image; drawn in place of the title (the cover stays as a dimmed backdrop). */
    val logo: String? = null,
    val item: LibraryItem? = null,
    /** Hold R1: a random game of this system or folder (opens its detail). */
    val random: (suspend () -> GameEntity?)? = null,
    val onClick: () -> Unit
)

/**
 * Home: systems in one of three generated, fixed orders (alphabetical, game count, generation) or the custom layout, which
 * also holds Continue, every smart folder and Apps ([HomeIds.custom]) plus the systems, games, apps and shortcuts the user placed.
 * Custom tiles have an edit mode: hold A → Move lifts the tile, the D-pad moves it one slot per press, A drops (persisted via
 * `setLayout`), B cancels; touch drops it on the tapped tile.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: SortieViewModel,
    onSystem: (String) -> Unit,
    onFolder: (String) -> Unit,
    onGame: (String) -> Unit,
    onSearch: () -> Unit,
    onDiagnostics: () -> Unit,
    onApps: () -> Unit,
    onManual: () -> Unit,
    onMagazines: () -> Unit,
    onJournal: () -> Unit,
    onLibrary: () -> Unit
) {
    val systems by vm.systems.collectAsState()
    val folders by vm.folders.collectAsState()
    val biosMissing by vm.biosMissing.collectAsState()
    val user by vm.user.collectAsState()
    val total by vm.total.collectAsState()
    val index by vm.index.collectAsState()
    val manifest by vm.manifests.collectAsState()
    val apps by vm.apps.collectAsState()
    val shortcuts by vm.shortcuts.collectAsState()
    val covers by vm.tileCovers.collectAsState()
    val logos by vm.systemLogos.collectAsState()
    val layoutGames by vm.layoutGames.collectAsState()
    val stats by vm.playStats.collectAsState()
    val lastPlayed by vm.lastPlayed.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    // X moves Sortie to the other screen (MainActivity.swapScreens): only offered when there is one to move to
    val presentationIndex = manifest.device.screens.presentationIndex
    val canSwap = remember(view, presentationIndex) {
        (view.display?.displayId ?: Display.DEFAULT_DISPLAY) != Display.DEFAULT_DISPLAY ||
            (context.applicationContext as SortieApp).graph.switcher.secondaryDisplay() != null
    }
    val systemLabels = remember(manifest) { manifest.systems.associate { it.id to it.label } }
    val scope = rememberCoroutineScope()
    val grid = user.homeLayout == "grid"
    val sort = user.homeSort.takeIf { s -> SORTS.any { it.first == s } } ?: "alpha"
    val custom = sort == "custom"
    val console = sort == "console"
    val homeConsole by vm.homeConsole.collectAsState()
    LaunchedEffect(console, systems) { if (console && homeConsole == null && systems.isNotEmpty()) vm.homeConsole.value = systems.first().def.id }
    var sheet by remember { mutableStateOf<HomeTile?>(null) }
    // edit mode: the lifted tile id and the working copy of the custom order (HomeIds.custom) it is being moved through
    var lifted by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<List<String>?>(null) }
    val cancelMove = {
        lifted = null
        moving = null
    }
    val currentGrid by rememberUpdatedState(grid)
    val currentConsole by rememberUpdatedState(console)
    val currentOnSearch by rememberUpdatedState(onSearch)
    val gridState = rememberLazyGridState()
    val memory = rememberGridMemory()
    // Home shows no game, except a focused game tile and By Console's game list, which set the selection themselves. Coming
    // back to a game tile (Back, or the X swap) keeps the selection, so the companion does not drop the game meanwhile.
    LaunchedEffect(Unit) {
        val back = memory.focused
        if (!console && back != HomeIds.CONTINUE && back?.let(HomeIds::isGame) != true) Selection.set(null)
    }
    LaunchedEffect(sort) { cancelMove() }
    BackHandler(enabled = lifted != null) { cancelMove() }
    BackHandler(enabled = console && lifted == null) { vm.leaveConsoleSort() }
    // Y opens search from Home (Start is intercepted by Android while Sortie is the home app); Select toggles list/grid.
    DisposableEffect(Unit) {
        val keys = setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT)
        Hotkeys.handle(keys)
        onDispose { Hotkeys.release(keys) }
    }
    LaunchedEffect(Unit) {
        Hotkeys.events.collect {
            if (lifted != null) return@collect
            when (it) {
                Hotkeys.Key.FAVOURITE -> currentOnSearch()
                Hotkeys.Key.CONTEXT -> if (!currentConsole) vm.setHomeLayout(if (currentGrid) "list" else "grid") // the console list toggles its own

                else -> Unit
            }
        }
    }

    val appsTile = HomeTile(HomeIds.APPS, "Apps", "${apps.size}", apps.size, "", colour = AppsGrey, onClick = onApps)
    val folderTiles = folders.map { f ->
        HomeTile(
            HomeIds.FOLDER + f.def.id,
            f.def.label,
            "${f.items.size}" + if (f.problems.isNotEmpty()) " · rule problems" else "",
            f.items.size,
            "",
            colour = Amber,
            random = { f.items.filterIsInstance<LibraryItem.Game>().filter { !it.game.placeholder }.randomOrNull()?.game }
        ) { onFolder(f.def.id) }
    }
    val systemTile = { row: SystemRow ->
        val missing = biosMissing[row.def.id]
        HomeTile(
            HomeIds.SYSTEM + row.def.id,
            row.def.label,
            "${row.count} · ${row.def.generation}" + (missing?.let { " · BIOS missing: ${it.joinToString()}" } ?: ""),
            row.count,
            if (sort == "generation") row.def.generation else "Systems",
            colour = parseColour(row.def.colour),
            cover = covers[row.def.id],
            logo = logos[row.def.id],
            random = { vm.randomGameOf(row.def.id) }
        ) { onSystem(row.def.id) }
    }
    val customIds = HomeIds.custom(user.layout, folderTiles.map { it.id })
    val tiles: List<HomeTile> = if (custom) {
        val systemById = systems.associateBy { it.def.id }
        val folderById = folderTiles.associateBy { it.id }
        val appById = apps.associateBy { it.id }
        val shortcutById = shortcuts.associateBy { it.id }
        val appLabels = apps.associate { it.pkg to it.label }
        // ids that no longer resolve (uninstalled app, removed system, nothing played yet) are skipped here but stay in user.json
        (moving ?: customIds).mapNotNull { id ->
            when {
                id == HomeIds.CONTINUE -> lastPlayed?.let { g ->
                    HomeTile(id, "Continue", displayTitle(g), 0, "", cover = g.cover ?: g.screenshot, item = LibraryItem.Game(g)) { onGame(g.id) }
                }
                id == HomeIds.APPS -> appsTile
                id.startsWith(HomeIds.SYSTEM) -> systemById[id.removePrefix(HomeIds.SYSTEM)]?.let(systemTile)
                id.startsWith(HomeIds.FOLDER) -> folderById[id]
                id.startsWith(LibraryItem.APP_PREFIX) -> appById[id]?.let { a ->
                    HomeTile(id, a.label, "app · ${a.pkg}", 0, "", item = LibraryItem.App(a)) { vm.launchApp(a) }
                }
                id.startsWith(LibraryItem.SHORTCUT_PREFIX) -> shortcutById[id]?.let { s ->
                    HomeTile(id, s.label, "shortcut · ${appLabels[s.pkg] ?: s.pkg}", 0, "", item = LibraryItem.Shortcut(s)) { vm.launchShortcut(s) }
                }
                else -> layoutGames[id]?.let { g ->
                    val system = systemById[g.systemId]?.def?.label ?: g.systemId
                    HomeTile(id, displayTitle(g), system, 0, "", cover = g.cover ?: g.screenshot, item = LibraryItem.Game(g)) { onGame(g.id) }
                }
            }
        }
    } else {
        // the generated sorts list systems only; Continue, folders and Apps live on Custom
        val systemTiles = systems.map(systemTile)
        when (sort) {
            "count" -> systemTiles.sortedByDescending { it.count }
            "generation" -> systemTiles.groupBy { it.section }.entries.sortedBy { generationRank(it.key) }.flatMap { it.value }
            else -> systemTiles.sortedBy { it.title.lowercase() }
        }
    }

    // D-pad step across the grid's minor axis while a tile is lifted: a row in a vertical grid, a column in a horizontal one
    val horizontal = grid && user.gridHorizontal
    // Moves the lifted tile to [target]'s slot; unresolved ids keep their place in the working list.
    val placeAt = placeAt@{ target: String ->
        val id = lifted ?: return@placeAt
        val full = moving ?: return@placeAt
        val visible = tiles.map { it.id }
        val i = visible.indexOf(id)
        val j = visible.indexOf(target)
        if (i < 0 || j < 0 || i == j) return@placeAt
        val rest = (full - id).toMutableList()
        rest.add(rest.indexOf(target) + if (j > i) 1 else 0, id)
        moving = rest
    }
    val moveBy = { delta: Int ->
        val i = tiles.indexOfFirst { it.id == lifted }
        if (i >= 0) placeAt(tiles[(i + delta).coerceIn(0, tiles.lastIndex)].id)
    }
    val drop = {
        moving?.let { vm.setLayout(it) }
        cancelMove()
    }
    val headers = !custom && (!grid || sort == "generation") // section headers: every generated sort in list view, only generation in grid view
    // every grid item's key, in order, for restoring the focus and position by key
    val gridKeys = buildList<Any> {
        var section: String? = null
        tiles.forEach { t ->
            if (headers && t.section != section) add("h:${t.section}")
            section = t.section
            add(t.id)
        }
    }
    val liftKeys = Modifier.onPreviewKeyEvent { e ->
        if (lifted == null) return@onPreviewKeyEvent false
        val down = e.type == KeyEventType.KeyDown
        // Custom layouts have no spanning headers. Use the measured cross-axis count, not the saved density:
        // a smaller display may fit fewer slots. A partially filled grid still clamps moves to the last tile.
        val cols = if (!grid) {
            1
        } else {
            (
                gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { if (horizontal) it.row else it.column }?.plus(1) ?: 1
                ).coerceAtLeast(1)
        }
        when (e.key) {
            Key.DirectionLeft -> if (down) moveBy(if (horizontal) -cols else -1)
            Key.DirectionRight -> if (down) moveBy(if (horizontal) cols else 1)
            Key.DirectionUp -> if (down) moveBy(if (horizontal) -1 else -cols)
            Key.DirectionDown -> if (down) moveBy(if (horizontal) 1 else cols)
            Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyUp) drop()
            Key.ButtonB, Key.Back, Key.Escape -> if (e.type == KeyEventType.KeyUp) cancelMove()
            else -> return@onPreviewKeyEvent false
        }
        true
    }
    LaunchedEffect(moving) {
        val id = lifted ?: return@LaunchedEffect
        val i = tiles.indexOfFirst { it.id == id }
        if (i >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.key == id }) gridState.animateScrollToItem(i)
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sortie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Clock()
                Spacer(Modifier.width(12.dp))
                Text("$total games", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (index.running) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onApps) { Text("Apps", maxLines = 1) }
                OutlinedButton(onClick = onLibrary) { Text("Library setup", maxLines = 1) }
                OutlinedButton(onClick = onSearch) { Text("Search", maxLines = 1) }
                OutlinedButton(onClick = onMagazines) { Text("Magazines", maxLines = 1) }
                OutlinedButton(onClick = onJournal) { Text("Journal", maxLines = 1) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onManual, modifier = Modifier.semantics { contentDescription = "Manual" }) {
                        Text("📖", maxLines = 1)
                    }
                    OutlinedButton(onClick = onDiagnostics) { Text("Settings", maxLines = 1) }
                }
            }
        }
        if (total == 0 && !index.running) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (manifest.systems.isEmpty()) "Set up your library" else "No games found",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    if (manifest.systems.isEmpty()) {
                        "Choose your game folders and installed emulators. Your games stay where they are."
                    } else {
                        "Check your folders, file types and storage connection in Library setup, then scan again."
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(onClick = onLibrary) { Text(if (manifest.systems.isEmpty()) "Set up library" else "Manage library") }
                Text(
                    "You can also use Apps, read Magazines or open the Manual without adding games.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState())
        ) {
            Text("Sort", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SORTS.forEach { (id, label) -> SortChip(label, selected = sort == id) { vm.setHomeSort(id) } }
        }
        if (console) {
            // By Console: a strip of consoles (L1/R1 page it, B leaves the sort) and the chosen console's games below
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                state = rememberLazyListState().also { st ->
                    val i = systems.indexOfFirst { it.def.id == homeConsole }
                    LaunchedEffect(i) { if (i >= 0) st.animateScrollToItem(i) }
                }
            ) {
                items(systems, key = { it.def.id }) { row ->
                    SortChip(row.def.label, selected = row.def.id == homeConsole) { vm.homeConsole.value = row.def.id }
                }
            }
            val chosen = systems.firstOrNull { it.def.id == homeConsole }
            if (chosen != null) {
                val games by remember(chosen.def.id) { vm.gamesOf(chosen.def.id) }.collectAsState(emptyList())
                Box(Modifier.weight(1f)) { key(chosen.def.id) { GameListScreen(chosen.def.label, games, vm, onGame) } } // filters/focus per console
            } else {
                Spacer(Modifier.weight(1f))
            }
            return@Column
        }
        // While a tile is lifted the grid itself holds the focus, so every D-pad press reaches [liftKeys] instead of moving the
        // focus between tiles. The sheet that started the lift is still closing when `lifted` is set: retry until it lands.
        val gridFocus = remember { FocusRequester() }
        var gridFocused by remember { mutableStateOf(false) }
        LaunchedEffect(lifted) {
            if (lifted == null) return@LaunchedEffect
            repeat(20) {
                if (gridFocused) return@LaunchedEffect
                runCatching { gridFocus.requestFocus() }
                delay(40)
            }
        }
        // Focusable only while lifting: a permanently focusable container swallows D-pad focus itself (no tile outline, no
        // up/down between tiles).
        val liftFocus = if (lifted != null) Modifier.focusRequester(gridFocus).onFocusChanged { gridFocused = it.isFocused }.focusable() else Modifier
        RememberGridPosition(memory, gridState, gridKeys)
        ItemGrid(grid, user, Modifier.weight(1f).then(liftFocus).then(liftKeys), state = gridState) {
            var section: String? = null
            tiles.forEach { t ->
                if (headers && t.section != section) {
                    val s = t.section
                    fullItem("h:$s") { SectionTitle(s) }
                }
                section = t.section
                item(key = t.id) {
                    RememberedItem(memory, t.id) {
                        HomeTileView(
                            t,
                            grid,
                            vm,
                            lifted = lifted == t.id,
                            favourite = (t.item?.id ?: t.id) in user.favourites,
                            minutes = stats[t.item?.id ?: t.id]?.minutes,
                            annotation = t.item?.id?.let { user.gameAnnotations[it] },
                            onClick = {
                                if (lifted != null) {
                                    placeAt(t.id)
                                    drop()
                                } else {
                                    t.onClick()
                                }
                            },
                            onLongClick = { if (lifted == null) sheet = t },
                            onFavourite = t.item?.let { item ->
                                {
                                    val now = vm.toggleFavourite(item.id)
                                    Toast.makeText(context, if (now) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
                                }
                            } ?: t.random?.let { pick ->
                                {
                                    scope.launch {
                                        val g = pick()
                                        if (g != null) onGame(g.id) else Toast.makeText(context, "Nothing playable in ${t.title}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            // not while a tile is lifted: the grid holds the focus then, and R2 must not edit the queue mid-move
                            onWantToPlay = (t.item as? LibraryItem.Game)?.takeIf { lifted == null }?.let { g -> { toggleWantToPlay(vm, g.id, context) } },
                            platform = (t.item as? LibraryItem.Game)?.game?.systemId?.let { systemLabels[it]?.takeIf(String::isNotBlank) ?: it }
                        )
                    }
                }
            }
        }
        val layoutHint = "Select" to if (grid) "list view" else "grid view"
        val swapHint = ("X" to "swap screens").takeIf { canSwap }
        when {
            lifted != null -> HintBar("D-pad" to "move", "A" to "drop here", "B" to "cancel")
            custom -> HintBar(
                *listOfNotNull(
                    "A" to "open",
                    "hold A" to "move / remove",
                    "hold R1" to "random",
                    "Y" to "search",
                    layoutHint,
                    "L1/R1" to "systems",
                    swapHint
                ).toTypedArray()
            )
            else -> HintBar(
                *listOfNotNull(
                    "A" to "open",
                    "hold A" to "add to home",
                    "hold R1" to "random",
                    "Y" to "search",
                    layoutHint,
                    "L1/R1" to "systems",
                    swapHint
                ).toTypedArray()
            )
        }
    }

    sheet?.let { t ->
        val game = t.item as? LibraryItem.Game
        val queue = game?.let { wantToPlayAction(vm, it.id, it.id in user.wantToPlay, context) }
        val actions = if (custom) {
            listOfNotNull(
                SheetAction("Move") {
                    moving = customIds
                    lifted = t.id
                },
                SheetAction("Remove from home") { vm.removeFromLayout(t.id) }.takeUnless { HomeIds.isAutomatic(t.id) },
                queue
            )
        } else {
            listOfNotNull(homeAction(vm, t.id, t.id in user.layout, context), queue)
        }
        ContextSheet(t.title, actions) { sheet = null }
    }
}

/** A home sort choice; [focusGlow] so the controller can see where it is. */
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.focusGlow(focused, FilterChipDefaults.shape),
        interactionSource = interaction
    )
}

/** Renders a [HomeTile] as a square tile (grid) or a row (list); [lifted] marks the tile being moved. */
@Composable
private fun HomeTileView(
    t: HomeTile,
    grid: Boolean,
    vm: SortieViewModel,
    lifted: Boolean,
    favourite: Boolean,
    minutes: Int?,
    annotation: GameAnnotation?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFavourite: (() -> Unit)?,
    /** R2 on a game tile (see [wantToPlayKey]); null for systems, folders, apps and shortcuts. */
    onWantToPlay: (() -> Unit)?,
    /** A game's system label for the list row (see [GameRow]). */
    platform: String?
) {
    val item = t.item
    if (grid) {
        when (item) {
            null -> SquareTile(
                t.title,
                onClick,
                subtitle = t.subtitle.substringBefore(" · BIOS"),
                colour = t.colour,
                cover = t.cover,
                logo = t.logo,
                lifted = lifted,
                onLongClick = onLongClick,
                onFavourite = onFavourite
            )
            is LibraryItem.Game -> SquareTile(
                t.title,
                onClick,
                modifier = Modifier.heroOnFocus(item.game).wantToPlayKey(onWantToPlay),
                subtitle = t.subtitle,
                cover = t.cover,
                paletteGlow = true,
                favourite = favourite,
                annotation = annotation,
                lifted = lifted,
                onLongClick = onLongClick,
                onFavourite = onFavourite
            )
            is LibraryItem.App -> SquareTile(
                t.title,
                onClick,
                favourite = favourite,
                lifted = lifted,
                icon = { vm.appIcon(item.app.pkg) },
                onLongClick = onLongClick,
                onFavourite = onFavourite
            )
            is LibraryItem.Shortcut -> SquareTile(
                t.title,
                onClick,
                favourite = favourite,
                lifted = lifted,
                icon = { vm.shortcutIcon(item.shortcut) },
                onLongClick = onLongClick,
                onFavourite = onFavourite
            )
        }
    } else {
        Box(if (lifted) Modifier.border(3.dp, LiftColour, RoundedCornerShape(10.dp)) else Modifier) {
            when (item) {
                null -> {
                    val colour = t.colour ?: Amber
                    FocusRow(
                        onClick = onClick,
                        onLongClick = onLongClick,
                        colour = colour,
                        onFavourite = onFavourite
                    ) { FolderLine(t.title, t.subtitle, colour) }
                }
                is LibraryItem.Game -> GameRow(
                    item.game,
                    minutes,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onFavourite = onFavourite,
                    annotation = annotation,
                    onWantToPlay = onWantToPlay,
                    favourite = favourite,
                    platform = platform,
                    label = if (t.id == HomeIds.CONTINUE) t.title else null
                )
                is LibraryItem.App -> LauncherRow(t.title, t.subtitle, favourite, onClick, onLongClick, minutes, tall = true, onFavourite = onFavourite) {
                    vm.appIcon(item.app.pkg)
                }
                is LibraryItem.Shortcut -> LauncherRow(t.title, t.subtitle, favourite, onClick, onLongClick, minutes, tall = true, onFavourite = onFavourite) {
                    vm.shortcutIcon(item.shortcut)
                }
            }
        }
    }
}

@Composable
private fun FolderLine(title: String, subtitle: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColourChip(colour)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
