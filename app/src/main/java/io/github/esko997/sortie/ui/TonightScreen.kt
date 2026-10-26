package io.github.esko997.sortie.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.esko997.sortie.data.tonight.TonightCard
import io.github.esko997.sortie.data.tonight.TonightDay
import io.github.esko997.sortie.data.tonight.TonightRole
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Keeps a visit stable through Details/back, but never restores yesterday's date after process death. */
private class TonightVisit : ViewModel() {
    val date: String = LocalDate.now().toString()
}

/**
 * Tonight: the day's saved set of up to five games, shown one at a time — a gameplay screenshot (the cover when there is none),
 * what the game is, why it was picked, and Play, Details and, for a Field Notes pick, Read article. The chips along the top (or
 * L1/R1) move between the picks. The store preserves the set for its local date across navigation and restarts; reopening on
 * another date gets that date's set. A pick that can no longer be played stays, disabled, saying why. Play launches here;
 * [onGame] opens the game's page, [onArticle] the exact magazine article.
 */
@Composable
fun TonightScreen(vm: SortieViewModel, onGame: (gameId: String) -> Unit, onArticle: (issueId: String, articleId: String) -> Unit) {
    val visit = viewModel { TonightVisit() }
    val entryDate = visit.date
    var index by rememberSaveable { mutableIntStateOf(0) }
    var focusTarget by rememberSaveable { mutableStateOf(FOCUS_PLAY) }
    val user by vm.user.collectAsState()
    val snap by vm.manifests.collectAsState()
    val total by vm.total.collectAsState()
    val indexing by vm.index.collectAsState()
    var loadError by remember { mutableStateOf<String?>(null) }
    val day by produceState<TonightDay?>(null, entryDate, total, indexing.running) {
        // Indexing writes in chunks; never freeze a partial library as the whole day's set.
        if (indexing.running) return@produceState
        try {
            value = vm.tonight(LocalDate.parse(entryDate))
            loadError = null
        } catch (_: IOException) {
            loadError = "Tonight's picks couldn't be saved. Check available storage, then reopen Tonight."
        }
    }
    // re-checked when hiding, bookmarks or the index change; the set itself never changes during the visit
    val cards by produceState<List<TonightCard>?>(null, day, user, total) { value = day?.let { vm.tonightCards(it) } }
    val tabs = remember { List(MAX_PICKS) { FocusRequester() } }
    val play = remember { FocusRequester() }
    val details = remember { FocusRequester() }
    val article = remember { FocusRequester() }
    val current = cards?.let { it.getOrNull(index.coerceIn(0, (it.size - 1).coerceAtLeast(0))) }
    // a set never grows, but a saved index from a longer one must not point past its end
    LaunchedEffect(cards?.size) { cards?.size?.let { n -> if (n > 0 && index >= n) index = n - 1 } }

    val hotkeys = LocalHotkeys.current
    DisposableEffect(hotkeys) {
        val keys = setOf(Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER)
        hotkeys.handle(keys)
        onDispose { hotkeys.release(keys) }
    }
    val latestCards by rememberUpdatedState(cards)
    LaunchedEffect(hotkeys) {
        hotkeys.events.collect { key ->
            val n = latestCards?.size ?: 0
            if (n == 0) return@collect
            val next = when (key) {
                Hotkeys.Key.NEXT_FOLDER -> (index + 1) % n
                Hotkeys.Key.PREV_FOLDER -> (index - 1 + n) % n
                else -> return@collect
            }
            index = next
            runCatching { tabs[next].requestFocus() }
        }
    }
    LaunchedEffect(current?.game?.id) { current?.game?.let(Selection::set) }
    // once the cards are in, the focus goes back to the control last used: Play first, else what the pick still offers
    LaunchedEffect(cards != null) {
        val c = current ?: return@LaunchedEffect
        val target = when {
            focusTarget == FOCUS_TAB -> tabs[index.coerceIn(0, MAX_PICKS - 1)]
            focusTarget == FOCUS_ARTICLE && c.pick.article != null -> article
            focusTarget == FOCUS_DETAILS && c.game != null -> details
            c.playable -> play
            c.game != null -> details
            c.pick.article != null -> article
            else -> tabs[index.coerceIn(0, MAX_PICKS - 1)]
        }
        runCatching { target.requestFocus() }
    }

    val path = current?.game?.let { it.screenshot ?: it.cover }
    val palette = rememberPalette(path)
    val accent = palette?.accent ?: Amber
    val backdrop = animateColorAsState(palette?.backdrop ?: Ink, tween(BACKDROP_BLEND_MS), label = "tonightBackdrop")
    Column(Modifier.fillMaxSize().drawBehind { drawRect(backdrop.value) }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text("Tonight", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(
                LocalDate.parse(entryDate).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, bottom = 2.dp).weight(1f)
            )
        }
        val list = cards
        when {
            loadError != null -> Message(loadError.orEmpty(), Modifier.weight(1f))
            list == null -> Message(
                if (indexing.running) "Waiting for the library to finish indexing…" else "Opening tonight's games…",
                Modifier.weight(1f)
            )
            list.isEmpty() -> Message(
                "Nothing to suggest tonight: no game here can be played right now (hidden, shelved, not copied or missing ones are left out).",
                Modifier.weight(1f)
            )
            else -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    list.forEachIndexed { i, c ->
                        GlowChip(
                            label = if (c.pick.fallback != null) "Discovery" else c.pick.roleOf?.label ?: c.pick.role,
                            selected = i == index,
                            trailing = if (c.playable) null else "✕",
                            modifier = Modifier.focusRequester(tabs[i]).onFocusChanged {
                                if (it.isFocused) {
                                    index = i
                                    focusTarget = FOCUS_TAB
                                }
                            }
                        ) { index = i }
                    }
                }
                current?.let { c ->
                    TonightPage(
                        c,
                        accent = accent,
                        position = "${list.indexOf(c) + 1} of ${list.size}",
                        systemLabel = snap.systems.firstOrNull { it.id == c.game?.systemId }?.label,
                        play = play,
                        details = details,
                        article = article,
                        onPlay = {
                            focusTarget = FOCUS_PLAY
                            c.game?.let { vm.launch(it) }
                        },
                        onDetails = {
                            focusTarget = FOCUS_DETAILS
                            c.game?.let { onGame(it.id) }
                        },
                        onArticle = {
                            focusTarget = FOCUS_ARTICLE
                            c.pick.article?.let { onArticle(it.issueId, it.articleId) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        HintBar("L1/R1" to "previous / next", "A" to "choose", "B" to "back")
    }
}

@Composable
private fun Message(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One pick: the picture beside (or, narrow and portrait, above) its text and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TonightPage(
    c: TonightCard,
    accent: Color,
    position: String,
    systemLabel: String?,
    play: FocusRequester,
    details: FocusRequester,
    article: FocusRequester,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onArticle: () -> Unit,
    modifier: Modifier
) {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp
    val stacked = width < STACK_BELOW_WIDTH_DP || configuration.screenHeightDp > width
    val picture: @Composable (Modifier) -> Unit = { m -> Picture(c, m) }
    val scroll = remember(c.pick.gameId) { ScrollState(0) }
    val scope = rememberCoroutineScope()
    var reading by remember(c.pick.gameId) { mutableStateOf(false) }
    val textKeys = Modifier
        .onFocusChanged { reading = it.isFocused }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionDown -> scroll.canScrollForward.also { if (it) scope.launch { scroll.scrollBy(160f) } }
                Key.DirectionUp -> scroll.canScrollBackward.also { if (it) scope.launch { scroll.scrollBy(-160f) } }
                else -> false
            }
        }
        .focusable(enabled = scroll.maxValue > 0)
        .semantics { contentDescription = "Recommendation details" }
        .border(1.dp, if (reading) accent else Color.Transparent, RoundedCornerShape(4.dp))
        .padding(4.dp)
    val text: @Composable () -> Unit = {
        val g = c.game
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (c.pick.fallback != null) "Discovery" else c.pick.roleOf?.label ?: c.pick.role,
                style = MaterialTheme.typography.labelLarge,
                color = accent
            )
            Text(
                position,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        c.pick.fallback?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(g?.let(::displayTitle) ?: c.pick.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp))
        if (g != null) {
            Text(
                listOfNotNull(systemLabel?.takeIf(String::isNotBlank) ?: g.systemId, g.year?.toString(), g.developer, g.genre).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(c.pick.reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
        g?.hook?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = accent,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        c.bookmark?.takeIf { c.pick.roleOf == TonightRole.RESUME }?.let {
            Text("Bookmark: $it", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 6.dp))
        }
        c.unavailable?.let {
            Text(
                "Unavailable: $it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    val actions: @Composable () -> Unit = {
        FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GamePageButton("Play", onPlay, accent, Modifier.focusRequester(play), primary = true, enabled = c.playable)
            GamePageButton("Details", onDetails, accent, Modifier.focusRequester(details), enabled = c.game != null)
            if (c.pick.article != null) GamePageButton("Read article", onArticle, accent, Modifier.focusRequester(article))
        }
    }
    if (stacked) {
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f).then(textKeys).verticalScroll(scroll)) {
                picture(Modifier.fillMaxWidth().heightIn(max = 280.dp).aspectRatio(4f / 3f))
                Column(Modifier.padding(top = 12.dp)) { text() }
            }
            actions()
        }
    } else {
        Row(modifier.fillMaxWidth().padding(16.dp)) {
            picture(Modifier.weight(1.1f).fillMaxHeight())
            Column(Modifier.weight(1f).padding(start = 20.dp)) {
                Column(Modifier.weight(1f).then(textKeys).verticalScroll(scroll)) { text() }
                actions()
            }
        }
    }
}

/** The gameplay screenshot, else the cover, letterboxed on Slate; dimmed when the pick cannot be played; the initial when neither exists. */
@Composable
private fun Picture(c: TonightCard, modifier: Modifier) {
    val g = c.game
    val screenshot = g?.screenshot
    val path = screenshot ?: g?.cover
    Box(modifier.clip(PictureShape).background(Slate).border(1.dp, Hairline, PictureShape), contentAlignment = Alignment.Center) {
        if (path != null) {
            CoverImage(
                path,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                alpha = if (c.playable) 1f else UNAVAILABLE_ALPHA,
                // screenshots are mostly low-resolution pixel art: scale them up crisp, covers smoothly
                filterQuality = if (screenshot != null) FilterQuality.None else DrawScope.DefaultFilterQuality
            )
        } else {
            Text((g?.title ?: c.pick.title).take(1), style = MaterialTheme.typography.displayMedium, color = Color(0xFF4A525C))
        }
    }
}

private val PictureShape = RoundedCornerShape(10.dp)

/** The most picks a day holds: one per role. */
private const val MAX_PICKS = 5
private const val STACK_BELOW_WIDTH_DP = 480
private const val BACKDROP_BLEND_MS = 400
private const val UNAVAILABLE_ALPHA = 0.35f

private const val FOCUS_PLAY = "play"
private const val FOCUS_DETAILS = "details"
private const val FOCUS_ARTICLE = "article"
private const val FOCUS_TAB = "tab"
