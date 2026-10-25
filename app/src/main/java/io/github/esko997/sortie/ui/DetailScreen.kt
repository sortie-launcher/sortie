package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.magazine.MagazineFeature
import io.github.esko997.sortie.data.manifest.GameAnnotation
import kotlinx.coroutines.delay

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
    val launchFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    val annotation = user.gameAnnotations[game.id] ?: GameAnnotation()
    // the notes editor replaces the cover and info (full width, usable on the narrow bottom screen too); kept across recreation
    var editing by rememberSaveable(game.id) { mutableStateOf(false) }
    var fromEditor by remember(game.id) { mutableStateOf(false) }
    LaunchedEffect(game.id) { Selection.set(game) }
    androidx.compose.runtime.DisposableEffect(game.id) {
        vm.gamePage = game.id
        onDispose { if (vm.gamePage == game.id) vm.gamePage = null }
    }
    val exitNotice by vm.exitNotice.collectAsState()
    LaunchedEffect(game.id, editing) {
        if (!editing) runCatching { (if (fromEditor) notesFocus else launchFocus).requestFocus() }
    }
    // Y favourites and R2 toggles Want to play only outside the editor: there the claims are dropped, so R2 reaches nothing
    androidx.compose.runtime.DisposableEffect(editing) {
        val claims = !editing
        val keys = setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.WANT_TO_PLAY)
        if (claims) Hotkeys.handle(keys)
        onDispose { if (claims) Hotkeys.release(keys) }
    }
    LaunchedEffect(game.id) {
        Hotkeys.events.collect {
            if (editing) return@collect
            when (it) {
                Hotkeys.Key.FAVOURITE -> vm.toggleFavourite(game.id)
                Hotkeys.Key.WANT_TO_PLAY -> toggleWantToPlay(vm, game.id, context)
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxSize().background(palette?.backdrop ?: Ink)) {
        if (editing) {
            GameAnnotationEditor(
                gameId = game.id,
                current = annotation,
                guideControls = game.controls,
                accent = accent,
                save = { vm.saveGameAnnotation(game.id, it) },
                onClose = {
                    fromEditor = true
                    editing = false
                },
                modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
            )
            HintBar("↕" to "move", "A" to "select / type", "B" to "cancel")
            return@Column
        }
        // the bottom screen is narrow: a smaller cover leaves the info column room for the buttons and notes
        val narrow = LocalConfiguration.current.screenWidthDp < 640
        Row(Modifier.weight(1f).padding(16.dp)) {
            Cover(game, width = if (narrow) 120 else 200, height = if (narrow) 156 else 260)
            Column(Modifier.padding(start = if (narrow) 12.dp else 20.dp).verticalScroll(rememberScrollState())) {
                Text(displayTitle(game), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    Stars(game.fit)
                    Text(
                        "  " + listOfNotNull(
                            game.systemId.uppercase(), game.year?.toString(), game.developer,
                            game.players?.let {
                                "$it players"
                            }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                game.hook?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = accent, modifier = Modifier.padding(top = 8.dp)) }
                stats[game.id]?.let { Text(playedLine(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                game.controls?.let {
                    Text(
                        "Controls: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                GameAnnotationSummary(annotation, Modifier.padding(top = 8.dp))
                val playInteraction = remember { MutableInteractionSource() }
                val profilesInteraction = remember { MutableInteractionSource() }
                val notesInteraction = remember { MutableInteractionSource() }
                val favInteraction = remember { MutableInteractionSource() }
                val queueInteraction = remember { MutableInteractionSource() }
                val referenceInteraction = remember { MutableInteractionSource() }
                val favShape = RoundedCornerShape(12.dp)
                FlowRow(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            vm.launch(game)
                        },
                        enabled = !game.placeholder,
                        modifier = Modifier
                            .focusGlow(playInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape, accent)
                            .focusRequester(launchFocus),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                        interactionSource = playInteraction
                    ) {
                        Text("Play")
                    }
                    OutlinedButton(
                        onClick = { showProfiles = !showProfiles },
                        modifier = Modifier.focusGlow(profilesInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                        interactionSource = profilesInteraction
                    ) { Text("Launch with…") }
                    OutlinedButton(
                        onClick = { editing = true },
                        modifier = Modifier
                            .focusGlow(notesInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent)
                            .focusRequester(notesFocus),
                        interactionSource = notesInteraction
                    ) { Text(if (annotation.isEmpty()) "Add notes" else "Edit notes") }
                    OutlinedButton(
                        onClick = onReference,
                        modifier = Modifier.focusGlow(referenceInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                        interactionSource = referenceInteraction
                    ) { Text("Reference cards") }
                    OutlinedIconToggleButton(
                        checked = fav,
                        onCheckedChange = { vm.toggleFavourite(game.id) },
                        modifier = Modifier
                            .focusGlow(favInteraction.collectIsFocusedAsState().value, favShape, accent)
                            .size(48.dp)
                            .semantics { contentDescription = "Favourite" },
                        shape = favShape,
                        colors = IconButtonDefaults.outlinedIconToggleButtonColors(
                            checkedContainerColor = accent.copy(alpha = 0.18f),
                            checkedContentColor = accent
                        ),
                        interactionSource = favInteraction
                    ) { Text(if (fav) "★" else "☆", fontSize = 24.sp) }
                    OutlinedButton(
                        onClick = { toggleWantToPlay(vm, game.id, context) },
                        modifier = Modifier.focusGlow(queueInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                        colors = if (queued) {
                            ButtonDefaults.outlinedButtonColors(containerColor = accent.copy(alpha = 0.18f), contentColor = accent)
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        interactionSource = queueInteraction
                    ) { Text(if (queued) "✓ Want to play" else "+ Want to play", maxLines = 1) }
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
                            .padding(bottom = 12.dp)
                            .border(1.dp, accent, RoundedCornerShape(8.dp))
                            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                GameProgressSection(graph, game.id, accent = accent)
                FeaturedIn(graph, game, accent, onArticle)
                if (showProfiles) {
                    profiles.forEach { (p, installed) ->
                        FocusRow(
                            onClick = {
                                if (installed) {
                                    vm.setOverride(game.id, p.id)
                                    vm.launch(game, p)
                                }
                            },
                            colour = accent
                        ) {
                            Text(
                                (if (user.profileOverrides[game.id] == p.id) "● " else "○ ") + p.label + if (installed) "" else "  (not installed)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    FocusRow(onClick = { vm.setOverride(game.id, null) }, colour = accent) {
                        Text("Clear override", style = MaterialTheme.typography.bodySmall)
                    }
                    FocusRow(onClick = { vm.hide(game.id, true) }, colour = accent) {
                        Text("Hide this game from folders", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (game.flags.isNotBlank() || game.tags.isNotBlank()) {
                    Row { (game.flags.split(',') + game.tags.split(',')).filter { it.isNotBlank() }.distinct().take(8).forEach { Badge(it) } }
                }
                val variants by remember(game.id) { vm.variantsOf(game.id) }.collectAsState(emptyList())
                if (variants.isNotEmpty()) {
                    Text(
                        "Also: " + variants.joinToString(" · ") { it.fileName },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    variants.forEach { v ->
                        FocusRow(onClick = { vm.launch(v) }, colour = accent) { Text("Play ${v.fileName}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                game.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Text(game.fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HintBar("A" to "play", "Y" to "favourite", "R2" to "want to play", "Select" to "launch with…", "B" to "back")
    }
}

/** How long a game page keeps saying its emulator closed right after starting. */
private const val EXIT_NOTICE_MS = 12_000L

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
    Column(Modifier.padding(top = 12.dp)) {
        Text("Featured in", style = MaterialTheme.typography.titleSmall, color = accent)
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
