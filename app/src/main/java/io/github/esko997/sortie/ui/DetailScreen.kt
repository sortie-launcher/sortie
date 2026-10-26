package io.github.esko997.sortie.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.magazine.MagazineFeature
import io.github.esko997.sortie.data.manifest.GameAnnotation
import kotlinx.coroutines.delay

/**
 * A game's page: cover, title and facts, then two action groups — launching (Play, Launch with…, favourite, Want to play) and
 * the quieter personal ones (bookmark, controller needs, reference cards, shelf) — followed by magazine features, badges, other
 * versions and the description. Wide screens keep the cover beside a scrolling column; portrait and narrow ones put a small cover beside the
 * title and let the whole page scroll, so the actions get the full width. Select opens and closes Launch with….
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(game: GameEntity, vm: SortieViewModel, onReference: () -> Unit, onArticle: (issueId: String, articleId: String) -> Unit) {
    val user by vm.user.collectAsState()
    val stats by vm.playStats.collectAsState()
    var showProfiles by remember { mutableStateOf(false) }
    val snap by vm.manifests.collectAsState()
    val apps by vm.apps.collectAsState()
    val profiles = remember(game.id, user.profileOverrides[game.id], snap.loadedAt, apps, showProfiles) { vm.profilesFor(game) }
    val fav = game.id in user.favourites
    val queued = game.id in user.wantToPlay
    val context = LocalContext.current
    val graph = (context.applicationContext as SortieApp).graph
    val palette = rememberPalette(game.cover ?: game.screenshot)
    val accent = palette?.accent ?: Amber
    // the cover's backdrop arrives a moment after the page: blend into it instead of snapping (read only while drawing)
    val backdrop = animateColorAsState(palette?.backdrop ?: Ink, tween(BACKDROP_BLEND_MS), label = "detailBackdrop")
    val launchFocus = remember { FocusRequester() }
    val bookmarkFocus = remember { FocusRequester() }
    val controllerFocus = remember { FocusRequester() }
    val profilesButton = remember { FocusRequester() }
    var profilesFocused by remember { mutableStateOf(false) }
    val annotation = user.gameAnnotations[game.id] ?: GameAnnotation()
    val bookmark = user.bookmarks[game.id]
    // an editor replaces the cover and info (full width, usable on the narrow bottom screen too); kept across recreation
    var editing by rememberSaveable(game.id) { mutableStateOf<String?>(null) }
    var fromEditor by remember(game.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(game.id) { Selection.set(game) }
    // "Leave a note" on the post-session invitation opens this game's bookmark editor here
    LaunchedEffect(game.id) {
        graph.sessionEnds.editorRequest.collect { id ->
            if (id == game.id) {
                editing = EDIT_BOOKMARK
                graph.sessionEnds.editorOpened(id)
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(game.id) {
        vm.gamePage = game.id
        onDispose { if (vm.gamePage == game.id) vm.gamePage = null }
    }
    val exitNotice by vm.exitNotice.collectAsState()
    LaunchedEffect(game.id, editing) {
        if (editing == null) {
            runCatching {
                when (fromEditor) {
                    EDIT_BOOKMARK -> bookmarkFocus
                    EDIT_CONTROLLER -> controllerFocus
                    else -> launchFocus
                }.requestFocus()
            }
        }
    }
    // Y favourites, R2 toggles Want to play and Select opens/closes Launch with… only outside the editor: there the claims are
    // dropped, so these keys reach nothing
    val hotkeys = LocalHotkeys.current
    androidx.compose.runtime.DisposableEffect(hotkeys, editing) {
        val claims = editing == null
        val keys = setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.WANT_TO_PLAY, Hotkeys.Key.CONTEXT)
        if (claims) hotkeys.handle(keys)
        onDispose { if (claims) hotkeys.release(keys) }
    }
    LaunchedEffect(hotkeys, game.id) {
        hotkeys.events.collect {
            if (editing != null) return@collect
            when (it) {
                Hotkeys.Key.FAVOURITE -> vm.toggleFavourite(game.id)
                Hotkeys.Key.WANT_TO_PLAY -> toggleWantToPlay(vm, game.id, context)
                Hotkeys.Key.CONTEXT -> {
                    // closing the list under the focus hands it to the button that opens it, not to nothing
                    if (showProfiles && profilesFocused) runCatching { profilesButton.requestFocus() }
                    showProfiles = !showProfiles
                }
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxSize().drawBehind { drawRect(backdrop.value) }) {
        editing?.let { which ->
            val close = {
                fromEditor = which
                editing = null
            }
            val editor = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
            if (which == EDIT_CONTROLLER) {
                ControllerEditor(game.id, annotation, game.controls, accent, save = { vm.saveGameAnnotation(game.id, it) }, onClose = close, modifier = editor)
            } else {
                BookmarkEditor(game.id, displayTitle(game), bookmark, accent, save = { vm.saveBookmark(game.id, it) }, onClose = close, modifier = editor)
            }
            HintBar("↕" to "move", "A" to "select / type", "B" to "cancel")
            return@Column
        }
        val configuration = LocalConfiguration.current
        val width = configuration.screenWidthDp
        // portrait (the DMG) and narrow windows: the cover sits beside the title and the page scrolls as one
        val stacked = width < STACK_BELOW_WIDTH_DP || configuration.screenHeightDp > width
        val coverWidth = when {
            stacked -> 112
            width >= 800 -> 200
            width >= 600 -> 152
            else -> 120
        }
        val coverHeight = coverWidth * 13 / 10
        val coverFrame = Modifier.border(1.dp, Hairline, RoundedCornerShape(6.dp))
        val systemLabel = snap.systems.firstOrNull { it.id == game.systemId }?.label?.takeIf(String::isNotBlank) ?: game.systemId
        val heading: @Composable () -> Unit = {
            Text(displayTitle(game), style = MaterialTheme.typography.headlineSmall)
            // the gap follows the stars only when there are any, so an unrated game's line starts under the title
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stars(game.fit, Modifier.padding(top = 1.dp))
                Text(
                    listOfNotNull(
                        systemLabel,
                        game.year?.toString(),
                        game.developer,
                        game.players?.takeIf(String::isNotBlank)?.let { if (it == "1") "1 player" else "$it players" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            game.hook?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = accent, modifier = Modifier.padding(top = 8.dp)) }
        }
        val body: @Composable () -> Unit = {
            stats[game.id]?.let { Text(playedLine(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            game.controls?.let {
                Text(
                    "Controls: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            GamePersonalSummary(annotation, bookmark, Modifier.padding(top = 8.dp))
            // launching: the loudest group, sized so it stays on one line from the Nova's 640 dp up
            FlowRow(
                Modifier.padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GamePageButton("Play", { vm.launch(game) }, accent, Modifier.focusRequester(launchFocus), primary = true, enabled = !game.placeholder)
                GamePageButton("Launch with…", { showProfiles = !showProfiles }, accent, Modifier.focusRequester(profilesButton), selected = showProfiles)
                val favInteraction = remember { MutableInteractionSource() }
                OutlinedIconToggleButton(
                    checked = fav,
                    onCheckedChange = { vm.toggleFavourite(game.id) },
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .focusGlow(
                            favInteraction.collectIsFocusedAsState().value,
                            CircleShape,
                            accent,
                            pressed = favInteraction.collectIsPressedAsState().value
                        )
                        .size(40.dp)
                        .semantics { contentDescription = "Favourite" },
                    shape = CircleShape,
                    colors = IconButtonDefaults.outlinedIconToggleButtonColors(
                        contentColor = accent,
                        checkedContainerColor = accent.copy(alpha = 0.18f),
                        checkedContentColor = accent
                    ),
                    border = BorderStroke(1.dp, accent.copy(alpha = OUTLINE_ALPHA)),
                    interactionSource = favInteraction
                ) { Text(if (fav) "★" else "☆", fontSize = 20.sp) }
                GamePageButton(if (queued) "✓ Want to play" else "+ Want to play", { toggleWantToPlay(vm, game.id, context) }, accent, selected = queued)
            }
            AnimatedVisibility(
                showProfiles,
                enter = expandVertically(tween(PANEL_MS)) + fadeIn(tween(PANEL_MS)),
                exit = shrinkVertically(tween(PANEL_MS)) + fadeOut(tween(PANEL_MS))
            ) {
                val panel = RoundedCornerShape(12.dp)
                Column(
                    Modifier
                        .padding(top = 10.dp)
                        .fillMaxWidth()
                        .background(Slate.copy(alpha = 0.72f), panel)
                        .border(1.dp, Hairline, panel)
                        .padding(4.dp)
                        .onFocusChanged { profilesFocused = it.hasFocus }
                ) {
                    profiles.forEach { (p, installed) ->
                        val chosen = user.profileOverrides[game.id] == p.id
                        FocusRow(
                            onClick = {
                                if (installed) {
                                    vm.setOverride(game.id, p.id)
                                    vm.launch(game, p)
                                }
                            },
                            colour = accent
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (chosen) "●" else "○",
                                    color = if (chosen) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(24.dp)
                                )
                                Text(
                                    p.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!installed) {
                                    Text(
                                        "not installed",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(Hairline))
                    FocusRow(onClick = { vm.setOverride(game.id, null) }, colour = accent) {
                        Text("Clear override", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FocusRow(onClick = { vm.hide(game.id, true) }, colour = accent) {
                        Text("Hide this game from folders", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // the emulator closed right after Play: said here, next to Play, and gone after a while or the next launch
            exitNotice?.takeIf { it.gameId == game.id }?.let { notice ->
                LaunchedEffect(notice) {
                    delay(EXIT_NOTICE_MS)
                    vm.dismissExitNotice()
                }
                Text(
                    notice.message,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .border(1.dp, accent, RoundedCornerShape(8.dp))
                        .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            // the personal group: quieter, after the shelf summary it edits
            GameProgressSection(graph, game.id, Modifier.padding(top = 12.dp), accent = accent) {
                GamePageButton(
                    if (bookmark == null) "Leave a note" else "Edit bookmark",
                    { editing = EDIT_BOOKMARK },
                    accent,
                    Modifier.focusRequester(bookmarkFocus),
                    quiet = true
                )
                GamePageButton("What it needs", { editing = EDIT_CONTROLLER }, accent, Modifier.focusRequester(controllerFocus), quiet = true)
                GamePageButton("Reference cards", onReference, accent, quiet = true)
            }
            FeaturedIn(graph, game, accent, onArticle)
            val badges = remember(game.flags, game.tags) {
                (game.flags.split(',') + game.tags.split(',')).filter { it.isNotBlank() }.distinct().take(8)
            }
            if (badges.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 12.dp)) { badges.forEach { Badge(it) } }
            }
            val variants by remember(game.id) { vm.variantsOf(game.id) }.collectAsState(emptyList())
            if (variants.isNotEmpty()) {
                DetailHeading("Other versions", accent)
                variants.forEach { v ->
                    FocusRow(onClick = { vm.launch(v) }, colour = accent) {
                        Text("Play ${v.fileName}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            game.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 14.dp)) }
            Text(
                game.fileName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
        }
        if (stacked) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row {
                    Cover(game, width = coverWidth, height = coverHeight, modifier = coverFrame)
                    Column(Modifier.padding(start = 14.dp)) { heading() }
                }
                body()
            }
        } else {
            Row(Modifier.weight(1f).padding(16.dp)) {
                Cover(game, width = coverWidth, height = coverHeight, modifier = coverFrame)
                Column(Modifier.padding(start = if (coverWidth < 200) 16.dp else 20.dp).verticalScroll(rememberScrollState())) {
                    heading()
                    body()
                }
            }
        }
        HintBar("A" to "play", "Y" to "favourite", "R2" to "want to play", "Select" to "launch with…", "B" to "back")
    }
}

/** Which editor replaces the page ([DetailScreen]'s saved `editing`). */
private const val EDIT_BOOKMARK = "bookmark"
private const val EDIT_CONTROLLER = "controller"

/** How long a game page keeps saying its emulator closed right after starting. */
private const val EXIT_NOTICE_MS = 12_000L

/** Below this width the page stacks: a small cover beside the title, everything else full width underneath. */
private const val STACK_BELOW_WIDTH_DP = 480

private const val BACKDROP_BLEND_MS = 400
private const val PANEL_MS = 180

/** Outline strength of the game page's outlined actions in the cover's accent: present, a step below the filled Play. */
private const val OUTLINE_ALPHA = 0.55f

private val GamePageButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * A game page action in the cover's [accent]: filled for [primary] (Play), outlined in the accent otherwise, or [quiet] (light
 * text on a neutral outline) for the personal group; [selected] tints the toggles that are on (Want to play, Launch with…).
 * Tighter side padding than Material's keeps the launch group on one line on a 640 dp screen; labels never wrap.
 */
@Composable
internal fun GamePageButton(
    text: String,
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    quiet: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val label: @Composable RowScope.() -> Unit = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.focusGlow(focused, ButtonDefaults.shape, accent, pressed = pressed),
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
            contentPadding = GamePageButtonPadding,
            interactionSource = interaction,
            content = label
        )
    } else {
        val tone = when {
            selected || !quiet -> accent
            else -> MaterialTheme.colorScheme.onSurface
        }
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.focusGlow(focused, ButtonDefaults.outlinedShape, accent, pressed = pressed),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) accent.copy(alpha = 0.18f) else Color.Transparent,
                contentColor = tone
            ),
            border = BorderStroke(1.dp, if (quiet && !selected) MaterialTheme.colorScheme.outline else accent.copy(alpha = OUTLINE_ALPHA)),
            contentPadding = GamePageButtonPadding,
            interactionSource = interaction,
            content = label
        )
    }
}

/** A section heading further down a game page (Featured in, Other versions). */
@Composable
private fun DetailHeading(text: String, accent: Color) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = accent, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
}

/**
 * Magazine articles that discuss this game, matched by the same title/system rules as an article's library lookup. Each opens
 * that article; B from it comes back here. Nothing shows until the shelf is read, and nothing at all when no issue mentions it.
 */
@Composable
private fun FeaturedIn(graph: SortieApp.Graph, game: GameEntity, accent: Color, onArticle: (issueId: String, articleId: String) -> Unit) {
    val features by produceState(emptyList<MagazineFeature>(), game.id) {
        value = graph.magazineGames.featuredIn(game, graph.magazines.shelf())
    }
    if (features.isEmpty()) return
    Column {
        DetailHeading("Featured in", accent)
        features.forEach { f ->
            FocusRow(onClick = { onArticle(f.issue.issue.id, f.article.id) }, colour = accent) {
                Column {
                    Text(
                        f.article.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(f.issue.issue.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
