package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.SearchFilters
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Results loaded per step; scrolling near the end of the loaded ones loads the next step (the count is always complete). */
private const val SEARCH_PAGE = 200

/** Load more once the last visible result is this close to the end of the loaded ones. */
private const val LOAD_AHEAD = 40

/** Typing settles this long before the text is queried; filters apply at once. */
private const val TYPING_SETTLE_MS = 200L

private enum class Picker { SYSTEM, TAGS, GENRE, MORE }

/** Text and filters come back with Back from a game and across the X screen swap (saved with the destination). */
private val FiltersSaver: Saver<SearchFilters, Any> = listSaver(
    save = {
        listOf(
            it.text, ArrayList(it.systems), ArrayList(it.tags), ArrayList(it.genres),
            it.favourites, it.wantToPlay, it.unplayed, it.fourStars, it.onDeviceOnly
        )
    },
    restore = {
        @Suppress("UNCHECKED_CAST")
        SearchFilters(
            text = it[0] as String,
            systems = (it[1] as List<String>).toSet(),
            tags = (it[2] as List<String>).toSet(),
            genres = (it[3] as List<String>).toSet(),
            favourites = it[4] as Boolean,
            wantToPlay = it[5] as Boolean,
            unplayed = it[6] as Boolean,
            fourStars = it[7] as Boolean,
            onDeviceOnly = it[8] as Boolean
        )
    }
)

/** One removable chip under the filter buttons: its label and the filters without it. */
private class ActiveFilter(val label: String, val remove: (SearchFilters) -> SearchFilters)

private fun activeFilters(f: SearchFilters, systemLabel: (String) -> String): List<ActiveFilter> = buildList {
    f.systems.sortedBy { systemLabel(it).lowercase() }.forEach { s -> add(ActiveFilter(systemLabel(s)) { it.copy(systems = it.systems - s) }) }
    f.tags.sorted().forEach { t -> add(ActiveFilter(t) { it.copy(tags = it.tags - t) }) }
    f.genres.sortedBy { it.lowercase() }.forEach { g -> add(ActiveFilter(g) { it.copy(genres = it.genres - g) }) }
    if (f.favourites) add(ActiveFilter("★ Favourites") { it.copy(favourites = false) })
    if (f.wantToPlay) add(ActiveFilter("Want to play") { it.copy(wantToPlay = false) })
    if (f.unplayed) add(ActiveFilter("Unplayed") { it.copy(unplayed = false) })
    if (f.fourStars) add(ActiveFilter("Fit 4+") { it.copy(fourStars = false) })
    if (!f.onDeviceOnly) add(ActiveFilter("Incl. not on device") { it.copy(onDeviceOnly = true) })
}

/** The More picker's toggles, keyed for [SearchPicker]. */
private val MORE_OPTIONS = listOf(
    SearchOption("favourites", "★ Favourites", 0),
    SearchOption("want", "Want to play", 0),
    SearchOption("unplayed", "Unplayed (never launched)", 0),
    SearchOption("fit4", "Fit 4+ (guide rating 4 or 5)", 0),
    SearchOption("device", "On device only (default)", 0)
)

private fun SearchFilters.moreSelected(): Set<String> = buildSet {
    if (favourites) add("favourites")
    if (wantToPlay) add("want")
    if (unplayed) add("unplayed")
    if (fourStars) add("fit4")
    if (onDeviceOnly) add("device")
}

private fun SearchFilters.toggleMore(key: String): SearchFilters = when (key) {
    "favourites" -> copy(favourites = !favourites)
    "want" -> copy(wantToPlay = !wantToPlay)
    "unplayed" -> copy(unplayed = !unplayed)
    "fit4" -> copy(fourStars = !fourStars)
    "device" -> copy(onDeviceOnly = !onDeviceOnly)
    else -> this
}

/** How many More toggles differ from their defaults. */
private fun SearchFilters.moreCount(): Int = listOf(favourites, wantToPlay, unplayed, fourStars, !onDeviceOnly).count { it }

/**
 * Text search plus System, Tags, Genre and More filters over the games table. Text may be empty: any filter alone lists its
 * matches. Values within a filter match either; the filters all have to match. The count is of every match; results load in
 * steps as the grid nears the end. Opening Search focuses the search box with the keyboard up. D-pad Down from the box closes
 * the keyboard and goes to the results (Up from them reaches the filters); without results it goes to the filters. A picker is
 * a dialog: A toggles a value, B closes it. While browsing results the filter buttons fold to one line naming the active
 * filters ([BrowseChrome]); the search box never folds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: SortieViewModel, onGame: (String) -> Unit) {
    var filters by rememberSaveable(stateSaver = FiltersSaver) { mutableStateOf(SearchFilters()) }
    var input by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableIntStateOf(SEARCH_PAGE) }
    var picker by rememberSaveable { mutableStateOf<Picker?>(null) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // set by a user change (never a restore): the next page for the new filters starts at the top
    var toTop by remember { mutableStateOf(false) }
    val update: (SearchFilters) -> Unit = { next ->
        if (next != filters) {
            filters = next
            limit = SEARCH_PAGE
            toTop = true
        }
    }
    LaunchedEffect(input) {
        if (input == filters.text) return@LaunchedEffect
        delay(TYPING_SETTLE_MS)
        update(filters.copy(text = input))
    }
    // each page carries the filters it answers, so a page still showing for the previous filters is recognisable
    val result by remember(filters, limit) {
        val asked = filters
        (if (asked.hasCriteria) vm.search(asked, limit) else flowOf(SearchPage(emptyList(), 0))).map { asked to it }
    }.collectAsState(null)
    val page = result?.second
    val fresh = result?.first == filters
    val games = page?.games.orEmpty()
    LaunchedEffect(result) {
        // scrolled only once the new games are in: the grid would otherwise follow a kept key to the middle of the new list
        if (toTop && fresh) {
            toTop = false
            gridState.scrollToItem(0)
        }
    }
    val stats by vm.playStats.collectAsState()
    val saves by vm.saveStems.collectAsState()
    val user by vm.user.collectAsState()
    val manifests by vm.manifests.collectAsState()
    val systemLabels = remember(manifests) { manifests.systems.associate { it.id to it.label } }
    val systemLabel: (String) -> String = { systemLabels[it] ?: it }
    var sheet by remember { mutableStateOf<LibraryItem?>(null) }
    val grid = user.browseLayout == "grid"
    BrowseLayoutHotkey(vm, grid)
    // Nothing asked or nothing found: the filters stay open (the ways out of an empty result need them). The search box itself
    // is never part of the fold, so it stays usable while typing.
    val chrome = rememberBrowseChrome(user.browseChrome, pinned = !filters.hasCriteria || (fresh && games.isEmpty()))
    val memory = rememberGridMemory()
    RememberGridPosition(memory, gridState, remember(games) { games.map { it.id } })
    LaunchedEffect(gridState) {
        snapshotFlow { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to result }.collect { (last, r) ->
            val p = r?.takeIf { it.first == filters }?.second ?: return@collect
            if (p.games.size >= limit && p.total > p.games.size && last >= p.games.size - LOAD_AHEAD) limit = p.games.size + SEARCH_PAGE
        }
    }
    // Down from the search box lands on the remembered result if it is on screen, else on the first visible one
    val entry = remember { FocusRequester() }
    val firstVisible by remember(gridState) { derivedStateOf { gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.key } }
    val focusResults: () -> Boolean = {
        val remembered = memory.focused
        val target = if (remembered != null && gridState.layoutInfo.visibleItemsInfo.any { it.key == remembered }) memory.requester else entry
        runCatching { target.requestFocus() }.isSuccess
    }
    val clearChip = remember { FocusRequester() }
    val systemChip = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var systemChipShown by remember { mutableStateOf(false) }
    val imeVisible by rememberUpdatedState(WindowInsets.isImeVisible)
    val currentGames by rememberUpdatedState(games)
    var leaving by remember { mutableStateOf<Job?>(null) }
    // Down from the search box closes the keyboard first: on a short screen it squeezes the filters and results out of the
    // layout, and a control measured away cannot take the focus. Then retry until the focus has left the box: to the results,
    // or to the filter buttons when there are none on screen (the folded strip's chevron while the filters are folded).
    val leaveField = {
        leaving?.cancel()
        leaving = scope.launch {
            keyboard?.hide()
            withTimeoutOrNull(1_000) { snapshotFlow { imeVisible }.first { !it } }
            repeat(25) {
                if (!fieldFocused) return@launch
                val toResults = currentGames.isNotEmpty() && focusResults()
                if (!toResults) runCatching { (if (systemChipShown) systemChip else chrome.showFocus).requestFocus() }
                delay(40)
            }
        }
    }
    // Opening Search (Y, Start, the Home button) focuses the box with the keyboard up. Coming back from a game, a picker or
    // across the X screen swap keeps where the user was instead.
    var opened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (opened) return@LaunchedEffect
        opened = true
        repeat(25) {
            if (fieldFocused) {
                keyboard?.show()
                return@LaunchedEffect
            }
            runCatching { field.requestFocus() }
            delay(40)
        }
    }
    val clearText = {
        input = ""
        update(filters.copy(text = ""))
    }
    // a removed chip took the controller focus with it: hand it to a control that is still there once the row has recomposed
    val refocus: (FocusRequester) -> Unit = { target ->
        scope.launch {
            withFrameNanos { }
            runCatching { target.requestFocus() }
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            input,
            { input = it },
            // Down must leave the editor instead of being consumed as caret movement. With results it skips the filter
            // controls so a new chip never stands between the text and the games.
            modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.hasFocus }.onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionDown || !isPhysicalPad(e)) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) leaveField()
                true
            },
            singleLine = true,
            placeholder = { Text("title, tag, genre, developer, description…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                update(filters.copy(text = input))
            })
        )
        val total = page?.total?.takeIf { fresh }
        val countText = when (total) {
            null -> "…"
            1 -> "1 game"
            else -> "$total games"
        }
        // the filter controls fold while browsing results; the box above never does
        Column(Modifier.fillMaxWidth().foldingHeader()) {
            if (!chrome.expanded) {
                // folded: the count and every active filter by name, so what narrows the results stays in view
                FoldedHeader(chrome, Modifier.padding(top = 4.dp), inset = 0.dp) {
                    Text(countText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    val active = activeFilters(filters, systemLabel)
                    if (active.isNotEmpty()) {
                        Text(
                            active.joinToString(" · ") { it.label },
                            style = MaterialTheme.typography.labelLarge,
                            color = Amber,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                return@Column
            }
            // folded away, the System button is not on screen: Down from the box must not aim at it
            DisposableEffect(Unit) { onDispose { systemChipShown = false } }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PickerChip(
                    "System",
                    filters.systems.size,
                    Modifier.focusRequester(systemChip).onGloballyPositioned { systemChipShown = it.fullyShown() }
                ) { picker = Picker.SYSTEM }
                PickerChip("Tags", filters.tags.size) { picker = Picker.TAGS }
                PickerChip("Genre", filters.genres.size) { picker = Picker.GENRE }
                PickerChip("More", filters.moreCount()) { picker = Picker.MORE }
                if (filters.hasCriteria) {
                    Text(
                        countText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp)
                    )
                }
                HideHeaderButton(chrome)
            }
            if (filters.hasFilters) {
                // one sideways-scrolling line however many are active, so the results keep their height on a narrow screen
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val clearInteraction = remember { MutableInteractionSource() }
                    AssistChip(
                        onClick = {
                            update(filters.clearedFilters())
                            refocus(systemChip)
                        },
                        label = { Text("Clear filters", maxLines = 1) },
                        modifier = Modifier
                            .focusRequester(clearChip)
                            .focusGlow(clearInteraction.collectIsFocusedAsState().value, AssistChipDefaults.shape),
                        interactionSource = clearInteraction
                    )
                    activeFilters(filters, systemLabel).forEach { chip ->
                        val interaction = remember(chip.label) { MutableInteractionSource() }
                        InputChip(
                            selected = true,
                            onClick = {
                                val next = chip.remove(filters)
                                update(next)
                                refocus(if (next.hasFilters) clearChip else systemChip)
                            },
                            label = { Text(chip.label, maxLines = 1) },
                            trailingIcon = { Text("✕") },
                            modifier = Modifier.focusGlow(interaction.collectIsFocusedAsState().value, InputChipDefaults.shape),
                            interactionSource = interaction
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !filters.hasCriteria -> Text(
                    "Type a title, tag or developer, or pick a System, Tags or Genre filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, start = 4.dp)
                )
                fresh && games.isEmpty() -> NoResults(
                    filters,
                    // the pressed button goes away with the empty state; the System button stays
                    onClearFilters = {
                        update(filters.clearedFilters())
                        refocus(systemChip)
                    },
                    onIncludeAll = {
                        update(filters.copy(onDeviceOnly = false))
                        refocus(systemChip)
                    },
                    onClearText = {
                        clearText()
                        refocus(systemChip)
                    }
                )
                else -> ItemGrid(grid, user, Modifier.fillMaxSize().padding(top = 4.dp).foldsBrowseChrome(chrome, gridState, systemChip), state = gridState) {
                    items(games, key = { it.id }) { g ->
                        val isEntry by remember(g.id) { derivedStateOf { firstVisible == g.id } }
                        val item = LibraryItem.Game(g)
                        Box(if (isEntry) Modifier.focusRequester(entry) else Modifier, propagateMinConstraints = true) {
                            RememberedItem(memory, g.id) {
                                ItemCell(
                                    item,
                                    grid,
                                    vm,
                                    favourite = g.id in user.favourites,
                                    minutes = stats[g.id]?.minutes,
                                    annotation = user.gameAnnotations[g.id],
                                    onClick = { onGame(g.id) },
                                    onLongClick = { sheet = item },
                                    hasSave = g.saveStem() in saves,
                                    platform = systemLabel(g.systemId)
                                )
                            }
                        }
                    }
                }
            }
        }
        BrowseHintBar(
            "A" to "open",
            "hold A" to "favourite / add to home",
            "R2" to "want to play",
            "Select" to if (grid) "list view" else "grid view",
            "B" to "back",
            compact = chrome.foldable
        )
    }
    when (picker) {
        Picker.SYSTEM, Picker.TAGS, Picker.GENRE -> {
            val p = picker!!
            val facets by remember(filters.onDeviceOnly) { vm.searchFacets(filters.onDeviceOnly) }.collectAsState(null)
            val (title, options, selected) = when (p) {
                Picker.SYSTEM -> Triple("System", facets?.systems, filters.systems)
                Picker.TAGS -> Triple("Tags", facets?.tags, filters.tags)
                else -> Triple("Genre", facets?.genres, filters.genres)
            }
            val toggle: (Set<String>, String) -> Set<String> = { set, v -> if (v in set) set - v else set + v }
            SearchPicker(
                title,
                options,
                selected,
                labelOf = if (p == Picker.SYSTEM) systemLabel else String::toString,
                onToggle = { v ->
                    update(
                        when (p) {
                            Picker.SYSTEM -> filters.copy(systems = toggle(filters.systems, v))
                            Picker.TAGS -> filters.copy(tags = toggle(filters.tags, v))
                            else -> filters.copy(genres = toggle(filters.genres, v))
                        }
                    )
                },
                onClear = {
                    update(
                        when (p) {
                            Picker.SYSTEM -> filters.copy(systems = emptySet())
                            Picker.TAGS -> filters.copy(tags = emptySet())
                            else -> filters.copy(genres = emptySet())
                        }
                    )
                },
                onDismiss = { picker = null }
            )
        }
        Picker.MORE -> SearchPicker(
            "More",
            MORE_OPTIONS,
            filters.moreSelected(),
            labelOf = { it },
            onToggle = { update(filters.toggleMore(it)) },
            onClear = { update(filters.copy(favourites = false, wantToPlay = false, unplayed = false, fourStars = false, onDeviceOnly = true)) },
            active = filters.moreCount(),
            onDismiss = { picker = null }
        )
        null -> Unit
    }
    sheet?.let { ItemSheet(it, vm) { sheet = null } }
}

/** Fully inside the window and every clipping parent: measured, not squeezed out of a short layout. */
private fun LayoutCoordinates.fullyShown(): Boolean {
    val bounds: Rect = boundsInWindow()
    return size.height > 0 && size.width > 0 && bounds.height >= size.height - 1f && bounds.width >= size.width - 1f
}

/** A filter button: its name, how many values are picked, and ▾ for the picker it opens. */
@Composable
private fun PickerChip(label: String, picked: Int, modifier: Modifier = Modifier, onClick: () -> Unit) =
    GlowChip(if (picked > 0) "$label · $picked" else label, picked > 0, modifier, trailing = "▾", onClick = onClick)

/** Nothing matches: say why and offer the ways out that apply. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoResults(filters: SearchFilters, onClearFilters: () -> Unit, onIncludeAll: () -> Unit, onClearText: () -> Unit) {
    Column(Modifier.padding(top = 16.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No games match.", style = MaterialTheme.typography.titleMedium)
        if (filters.hasFilters) {
            Text(
                "Every filter has to match; values picked within one filter match either.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (filters.hasFilters) GlowButton("Clear filters", onClick = onClearFilters)
            if (filters.onDeviceOnly) GlowButton("Include games not on device", onClick = onIncludeAll)
            if (filters.query.isNotEmpty()) GlowButton("Clear text", onClick = onClearText)
        }
    }
}

/**
 * Multi-select picker in the [ContextSheet] style: one row per value with its game count, A toggles and keeps the picker
 * open, Done or B closes it. [options] null = still loading. A picked value the library no longer offers (e.g. after
 * switching "on device only") is listed first so it can be unpicked. Flag-only badge labels carry a ⚑. [active] is the count
 * its [PickerChip] shows (More counts changes from the defaults, not ticked rows); the heading repeats it and Clear keeps its
 * row while disabled, so the dialog does not resize or move under the pointer as values toggle.
 */
@Composable
private fun SearchPicker(
    title: String,
    options: List<SearchOption>?,
    selected: Set<String>,
    labelOf: (String) -> String,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    active: Int = selected.size
) {
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    val rows = remember(options, selected) {
        options?.let { o ->
            val known = o.mapTo(HashSet()) { it.value }
            selected.filter { it !in known }.sorted().map { SearchOption(it, labelOf(it), 0) } + o
        }
    }
    val firstModifier = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) focusedOnce = true }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Slate).padding(12.dp)) {
            Text(
                if (active > 0) "$title · $active" else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
            when {
                rows == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                rows.isEmpty() -> Text("Nothing in the library to pick.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                itemsIndexed(rows.orEmpty(), key = { _, o -> o.value }) { i, o ->
                    val on = o.value in selected
                    FocusRow(onClick = { onToggle(o.value) }, modifier = if (i == 0) firstModifier else Modifier) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "☑" else "☐", color = if (on) Amber else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
                            Text(
                                if (o.caution) "⚑ ${o.label}" else o.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (o.n > 0) Text("${o.n}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            FocusRow(onClick = onDismiss, modifier = if (rows.isNullOrEmpty()) firstModifier else Modifier) {
                Text("Done", style = MaterialTheme.typography.bodyLarge)
            }
            if (active > 0) {
                FocusRow(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                // same footprint as the FocusRow above, not focusable: nothing to clear
                Box(Modifier.fillMaxWidth().padding(8.dp)) {
                    Text("Clear", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
                }
            }
            HintBar("A" to "pick", "B" to "done")
        }
        // the ContextSheet focus dance: keyboard mode, then retry until the first row holds the focus (again once loaded)
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(rows == null) {
            focusedOnce = false
            repeat(20) {
                if (focusedOnce) return@LaunchedEffect
                @OptIn(ExperimentalComposeUiApi::class)
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { first.requestFocus() }
                delay(40)
            }
        }
    }
}
