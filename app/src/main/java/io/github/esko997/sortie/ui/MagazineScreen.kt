package io.github.esko997.sortie.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.ArticleContent
import io.github.esko997.sortie.data.magazine.ClipSource
import io.github.esko997.sortie.data.magazine.GamePresence
import io.github.esko997.sortie.data.magazine.IssueImage
import io.github.esko997.sortie.data.magazine.LocalGameMatch
import io.github.esko997.sortie.data.magazine.MAX_CLIP_NOTE
import io.github.esko997.sortie.data.magazine.MAX_CLIP_TEXT
import io.github.esko997.sortie.data.magazine.MagazineArticle
import io.github.esko997.sortie.data.magazine.MagazineClipping
import io.github.esko997.sortie.data.magazine.MagazineGameReference
import io.github.esko997.sortie.data.magazine.MagazineGames
import io.github.esko997.sortie.data.magazine.MagazineIssue
import io.github.esko997.sortie.data.magazine.MagazineReadingStore
import io.github.esko997.sortie.data.magazine.MagazineRepository
import io.github.esko997.sortie.data.magazine.MagazineShelf
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.data.magazine.ShelfIssue
import io.github.esko997.sortie.data.magazine.articleClip
import io.github.esko997.sortie.data.magazine.articleClipping
import io.github.esko997.sortie.data.magazine.clipText
import io.github.esko997.sortie.data.magazine.highlightClipping
import io.github.esko997.sortie.data.magazine.highlightsIn
import io.github.esko997.sortie.data.magazine.readerOutline
import io.github.esko997.sortie.data.magazine.removeClipping
import io.github.esko997.sortie.data.magazine.saveClipping
import io.github.esko997.sortie.data.magazine.source
import io.github.esko997.sortie.data.markdown.MdBlock
import io.github.esko997.sortie.data.user.UserStore
import io.github.esko997.sortie.mecha.ui.Hangar
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Comfortable reading measure: lines stay this narrow on the wide top screen. */
private val Measure = 680.dp

/** The full reading width: lead images and wide pictures (diagrams, 16:9 shots) may use it beyond the text measure. */
private val WideMeasure = 920.dp

/** A caption under a narrow portrait picture still gets this much width (less on a screen narrower than that). */
private val MinCaptionWidth = 360.dp

/** The article list's (and [ArticleFigure]'s) key for the lead image. */
private const val HERO_KEY = "hero"

/** Below this width (the narrow bottom screen, the DMG) covers stack above the text instead of beside it. */
private val NarrowWidth = 560.dp

private val Muted = Color(0xFF9AA3AD)
private val Warning = Color(0xFFF87171)

private class Loaded(val shelf: MagazineShelf, val store: MagazineReadingStore)

/**
 * Offline magazines (format and sources in data/magazine: issues bundled with Sortie plus packs on the card): a shelf of issues,
 * an issue's editorial, contents and games mentioned, and a long-form article reader. B climbs one level at a time (article →
 * contents → shelf) and only the shelf hands B to the navigation; opened at an article ([opened], a game page's "Featured in"),
 * B from the article hands back straight away. The open issue/article and the article's scroll position are saved state
 * (recreation, the X swap); [MagazineReadingStore] keeps reading positions and focus for when the magazines are left and
 * opened again. [onGame] opens Sortie's page for a local game an article's references resolve to; a reference never claims
 * a game is on the device unless a readable file matched it.
 *
 * The scrapbook (Select on the shelf or in the reader) holds the user's highlights and clipped articles from user.json
 * ([MagazineClipping]); B closes it back to where it was opened, and opening a clipping goes to its article and passage.
 */
@Composable
fun MagazineScreen(graph: SortieApp.Graph, onGame: (String) -> Unit, opened: ReaderLocation? = null) {
    val context = LocalContext.current
    val repo = graph.magazines
    val games = graph.magazineGames
    val user = graph.user
    val clippings by remember(user) { user.data.map { it.magazineClippings }.distinctUntilChanged() }
        .collectAsState(user.data.value.magazineClippings)
    val readerOwner = remember { Any() }
    DisposableEffect(readerOwner) {
        onDispose {
            // A newly composed reader on the other screen may have already published its own panel.
            if (Selection.magazine.value?.owner === readerOwner) Selection.magazine.value = null
        }
    }
    val loaded by produceState<Loaded?>(null, repo) {
        val store = withContext(Dispatchers.IO) { MagazineReadingStore.open(context) }
        value = Loaded(repo.shelf(), store)
    }
    var issueId by rememberSaveable { mutableStateOf(opened?.issue) }
    var articleId by rememberSaveable { mutableStateOf(opened?.article) }
    var scrapbook by rememberSaveable { mutableStateOf(false) }
    // a clipping whose passage the reader should show once its article is open
    var jumpTo by rememberSaveable { mutableStateOf<String?>(null) }
    var scrapFocus by rememberSaveable { mutableStateOf<String?>(null) }
    // contents: only the games found on this device; and the entry (a game) to return to from an article it opened
    var availableOnly by rememberSaveable { mutableStateOf(false) }
    var contentsReturn by rememberSaveable { mutableStateOf<String?>(null) }
    var contentsReturnArticle by rememberSaveable { mutableStateOf<String?>(null) }
    fun go(to: ReaderLocation) {
        issueId = to.issue
        articleId = to.article
    }
    // opened at an article from elsewhere: B from an article goes back there rather than to the contents
    val handsBack = opened != null && articleId != null
    BackHandler(enabled = issueId != null && !handsBack) { ReaderLocation(issueId, articleId).back()?.let { go(it) } }
    // registered after the level handler, so it wins while the scrapbook is open: B returns to where it was opened
    BackHandler(enabled = scrapbook) { scrapbook = false }
    val data = loaded
    // a pack that changed on the card while the reader was away: fall back to the nearest level that still exists
    LaunchedEffect(data) { if (data != null) go(ReaderLocation(issueId, articleId).reconcile(data.shelf)) }
    val issue = data?.shelf?.issue(issueId)
    val article = issue?.issue?.article(articleId)
    // the issue's references resolved against the library once per visit, shared by the contents and the article reader
    val resolved by produceState<Map<String, List<LocalGameMatch>>?>(null, issue?.issue) {
        value = null
        value = issue?.issue?.let { games.resolve(it.games) }
    }
    if (!scrapbook && issue != null && article == null) PublishMagazineCompanion(readerOwner, issue.issue, null)
    if (issue == null || scrapbook) {
        SideEffect {
            if (Selection.magazine.value?.owner === readerOwner) Selection.magazine.value = null
        }
    }
    LaunchedEffect(readerOwner, issue?.issue) {
        Selection.magazineActions.collect { action ->
            if (issue != null && action.owner === readerOwner && action.issueId == issue.issue.id && action is MagazineAction.OpenArticle) {
                issue.issue.article(action.articleId)?.let { articleId = it.id }
            }
        }
    }
    fun turn(delta: Int) {
        val list = issue?.issue?.articles.orEmpty()
        val i = list.indexOfFirst { it.id == articleId }
        if (i >= 0) list.getOrNull(i + delta)?.let { articleId = it.id }
    }
    Box(
        Modifier
            .fillMaxSize()
            // L1/R1 turn articles here; elsewhere in the magazines they do nothing rather than page Sortie's system lists
            .onPreviewKeyEvent { e ->
                if (e.key != Key.ButtonL1 && e.key != Key.ButtonR1) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && article != null && !scrapbook) {
                    turn(if (e.key == Key.ButtonR1) 1 else -1)
                }
                true
            }
    ) {
        when {
            data == null -> Text("Opening magazines…", color = Muted, modifier = Modifier.align(Alignment.Center))
            scrapbook -> ScrapbookView(
                shelf = data.shelf,
                clippings = clippings,
                user = user,
                target = scrapFocus,
                onFocused = { scrapFocus = it },
                onOpen = { c ->
                    when (c.source(data.shelf)) {
                        ClipSource.AVAILABLE -> {
                            go(ReaderLocation(c.issueId, c.articleId))
                            jumpTo = c.id
                            scrapbook = false
                        }
                        ClipSource.ARTICLE_MISSING -> {
                            go(ReaderLocation(c.issueId))
                            scrapbook = false
                        }
                        ClipSource.ISSUE_MISSING -> Unit
                    }
                },
                onGame = onGame
            )
            issue != null && article != null -> key(issue.issue.id, article.id) {
                ArticleReader(
                    issue = issue,
                    article = article,
                    repo = repo,
                    games = games,
                    resolved = resolved,
                    store = data.store,
                    readerOwner = readerOwner,
                    onGame = onGame,
                    next = issue.issue.articles.getOrNull(issue.issue.articles.indexOf(article) + 1),
                    onArticle = { articleId = it },
                    clippings = clippings,
                    user = user,
                    jumpTo = jumpTo,
                    onJumped = { jumpTo = null },
                    onScrapbook = { scrapbook = true },
                    contentsKey = if (handsBack) null else contentsReturn?.takeIf { contentsReturnArticle == article.id } ?: "article:${article.id}",
                    backLabel = if (handsBack) "back" else "contents"
                )
            }
            issue != null -> key(issue.issue.id) {
                ContentsView(
                    issue,
                    data.store,
                    resolved = resolved,
                    availableOnly = availableOnly,
                    onAvailableOnly = { availableOnly = it },
                    onArticle = { id, from ->
                        contentsReturn = from
                        contentsReturnArticle = id
                        articleId = id
                    },
                    onGame = onGame
                )
            }
            else -> ShelfView(data.shelf, data.store, clippings.size, onOpen = ::go, onScrapbook = { scrapbook = true })
        }
    }
}

// ---------------------------------------------------------------- shared list focus

/**
 * D-pad focus for a magazine list (shelf, contents). Items are focused by key; [onKey] lets the D-pad read through an item
 * taller than the screen before the focus moves on, and Up on the first focusable item reveals the header above it.
 */
@Stable
private class ListFocus(val state: LazyListState, val scope: CoroutineScope, val step: Float, val onFocused: (String) -> Unit) {
    var focused by mutableStateOf<String?>(null)
    var firstFocusable = 0
    private val requesters = HashMap<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun onKey(e: KeyEvent, index: Int): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
        return when (e.key) {
            Key.DirectionDown -> {
                val hidden = item.offset + item.size - info.viewportEndOffset
                if (hidden > 1) {
                    scope.launch { state.scrollBy(min(step, hidden.toFloat())) }
                    true
                } else {
                    false
                }
            }
            Key.DirectionUp -> {
                val hidden = info.viewportStartOffset - item.offset
                when {
                    hidden > 1 -> {
                        scope.launch { state.scrollBy(-min(step, hidden.toFloat())) }
                        true
                    }
                    index == firstFocusable && state.canScrollBackward -> {
                        scope.launch { state.animateScrollToItem(0) }
                        true
                    }
                    else -> false
                }
            }
            else -> false
        }
    }
}

@Composable
private fun rememberListFocus(onFocused: (String) -> Unit): ListFocus {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    val latest by rememberUpdatedState(onFocused)
    return remember(state, scope) { ListFocus(state, scope, step) { latest(it) } }
}

/** On entry, puts the controller focus on [target] (or the first focusable key), scrolling it into the list first. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FocusOnEntry(focus: ListFocus, keys: List<String>, focusable: List<String>, target: String?) {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(focus, keys) {
        val key = target?.takeIf { it in focusable } ?: focusable.firstOrNull() ?: return@LaunchedEffect
        withTimeoutOrNull(1_000) { snapshotFlow { focus.state.layoutInfo.totalItemsCount }.first { it == keys.size } }
        val index = keys.indexOf(key)
        if (focus.state.layoutInfo.visibleItemsInfo.none { it.key == key }) {
            focus.state.scrollToItem(if (index == focus.firstFocusable) 0 else index)
        }
        repeat(25) {
            inputMode.requestInputMode(InputMode.Keyboard)
            if (focus.focused == key) return@LaunchedEffect
            runCatching { focus.requester(key).requestFocus() }
            delay(40)
        }
    }
}

/**
 * One focusable list entry. With [onClick] it is a button with the app's focus glow; without, a passage the D-pad stops on
 * (and reads through, see [ListFocus.onKey]) so long text is reachable without a pointer.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Entry(focus: ListFocus, key: String, index: Int, onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val inputMode = LocalInputModeManager.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .fillMaxWidth()
            .focusRequester(focus.requester(key))
            .onFocusChanged {
                if (it.hasFocus) {
                    focus.focused = key
                    focus.onFocused(key)
                }
            }
            .onPreviewKeyEvent { focus.onKey(it, index) }
            .then(
                when {
                    onClick != null -> Modifier.focusGlow(focused, shape)
                    focused -> Modifier.border(1.dp, Amber.copy(alpha = 0.5f), shape)
                    else -> Modifier
                }
            )
            .clip(shape)
            .background(if (focused) Slate else Color.Transparent)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interactionSource = interaction, indication = PressVeil) {
                        inputMode.requestInputMode(InputMode.Keyboard)
                        focus.requester(key).requestFocus()
                        onClick()
                    }
                } else {
                    Modifier.focusable(interactionSource = interaction)
                }
            )
            .padding(14.dp)
    ) { content() }
}

// ---------------------------------------------------------------- shelf

@Composable
private fun ShelfView(shelf: MagazineShelf, store: MagazineReadingStore, clipCount: Int, onOpen: (ReaderLocation) -> Unit, onScrapbook: () -> Unit) {
    val focus = rememberListFocus { store.shelfFocus = it }
    val target = remember(store) { store.shelfFocus }
    // the article last opened, offered while it is still unfinished and still on the card
    val resume = remember(shelf, store) {
        store.resume()?.takeIf { r -> shelf.issue(r.issue)?.issue?.article(r.article) != null && !store.finished(r.issue!!, r.article!!) }
    }
    val keys = remember(shelf, resume) {
        buildList {
            add("masthead")
            if (resume != null) add("resume")
            add("scrapbook")
            shelf.issues.forEach { add("issue:${it.issue.id}") }
            shelf.broken.forEach { add("broken:${it.folder}") }
            if (shelf.problem != null) {
                add("problem")
            } else if (shelf.issues.isEmpty() && shelf.broken.isEmpty()) {
                add("empty")
            }
        }
    }
    focus.firstFocusable = 1
    // the latest issue (or Continue reading), not the scrapbook, takes the focus on a first visit
    FocusOnEntry(focus, keys, keys.drop(1).sortedBy { it == "scrapbook" }, target)
    val open = { to: ReaderLocation ->
        store.shelfFocus = "issue:${to.issue}"
        onOpen(to)
    }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.key != Key.ButtonSelect) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onScrapbook()
            true
        }
    ) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < NarrowWidth
            LazyColumn(
                Modifier.fillMaxSize(),
                state = focus.state,
                contentPadding = PaddingValues(horizontal = if (narrow) 10.dp else 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "masthead") { ShelfMasthead(Modifier.measure().padding(horizontal = 14.dp)) }
                if (resume != null) {
                    item(key = "resume") {
                        val ri = shelf.issue(resume.issue)!!
                        val ra = ri.issue.article(resume.article)!!
                        Entry(focus, "resume", keys.indexOf("resume"), onClick = { open(resume) }, modifier = Modifier.measure()) {
                            Column {
                                Text("CONTINUE READING", style = kicker())
                                Text(ra.title, style = headline(MaterialTheme.typography.titleLarge), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(ri.issue.title, style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
                item(key = "scrapbook") {
                    Entry(focus, "scrapbook", keys.indexOf("scrapbook"), onClick = onScrapbook, modifier = Modifier.measure()) {
                        Column {
                            Text("YOUR SCRAPBOOK", style = kicker())
                            Text(
                                when (clipCount) {
                                    0 -> "Highlights and clipped recommendations you save while reading"
                                    1 -> "1 clipping"
                                    else -> "$clipCount clippings"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted
                            )
                        }
                    }
                }
                // compact entries keep a number column when any of them has a number, so titles line up
                val numbered = shelf.issues.drop(1).any { it.issue.issueNumber != null }
                itemsIndexed(shelf.issues, key = { _, s -> "issue:${s.issue.id}" }) { i, s ->
                    val k = "issue:${s.issue.id}"
                    Entry(focus, k, keys.indexOf(k), onClick = { open(ReaderLocation(s.issue.id)) }, modifier = Modifier.measure()) {
                        if (i == 0) FeatureIssue(s, narrow) else CompactIssue(s, numbered)
                    }
                }
                itemsIndexed(shelf.broken, key = { _, b -> "broken:${b.folder}" }) { _, b ->
                    val k = "broken:${b.folder}"
                    Entry(focus, k, keys.indexOf(k), onClick = null, modifier = Modifier.measure()) {
                        Column {
                            Text("COULD NOT OPEN", style = kicker().copy(color = Warning))
                            Text("magazines/${b.folder}", style = MaterialTheme.typography.titleMedium)
                            Text(b.reason, style = MaterialTheme.typography.bodyMedium, color = Muted)
                        }
                    }
                }
                if (shelf.problem != null) {
                    item(key = "problem") {
                        Entry(focus, "problem", keys.indexOf("problem"), onClick = null, modifier = Modifier.measure()) {
                            Text(shelf.problem, style = MaterialTheme.typography.bodyLarge, color = Warning)
                        }
                    }
                } else if (shelf.issues.isEmpty() && shelf.broken.isEmpty()) {
                    item(key = "empty") {
                        Entry(focus, "empty", keys.indexOf("empty"), onClick = null, modifier = Modifier.measure()) { EmptyShelf(shelf) }
                    }
                }
            }
        }
        HintBar("A" to "open", "Select" to "scrapbook", "B" to "back")
    }
}

@Composable
private fun ShelfMasthead(modifier: Modifier) {
    Column(modifier.padding(bottom = 6.dp)) {
        Text("SORTIE", style = kicker())
        Text("Magazines", style = headline(MaterialTheme.typography.displaySmall))
        Text("Offline reading", style = MaterialTheme.typography.bodySmall, color = Muted)
        Box(Modifier.padding(top = 10.dp).width(56.dp).height(3.dp).background(Amber))
    }
}

@Composable
private fun EmptyShelf(shelf: MagazineShelf) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No magazines on the card yet", style = headline(MaterialTheme.typography.headlineSmall))
        Text(
            "Sortie reads magazine issues from the SD card, offline. Copy each issue in as its own folder:",
            style = MaterialTheme.typography.bodyLarge
        )
        Text("sortie/magazines/<issue>/issue.json", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = Amber)
        Text(
            "with the issue's articles/ and media/ folders beside issue.json, and the folder named after the issue's id. On this " +
                "device that is ${shelf.dir.path}. Sortie never downloads issues.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

@Composable
private fun FeatureIssue(s: ShelfIssue, narrow: Boolean) {
    val issue = s.issue
    val text: @Composable () -> Unit = {
        Column {
            issue.issueNumber?.let { IssueNumber(it, if (narrow) 34.sp else 46.sp, Modifier.padding(bottom = 4.dp)) }
            Text("LATEST ISSUE · ${issueDate(issue).uppercase()}", style = kicker())
            Text(issue.title, style = headline(if (narrow) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium))
            if (issue.subtitle.isNotEmpty()) Text(issue.subtitle, style = deckStyle(), color = Muted)
        }
    }
    Column {
        if (narrow) {
            Row {
                IssueCover(s, 92.dp, 124.dp)
                Spacer(Modifier.width(14.dp))
                text()
            }
        } else {
            Row {
                IssueCover(s, 156.dp, 208.dp)
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    text()
                    if (issue.editorial.isNotEmpty()) {
                        Text(
                            inlineText(issue.editorial.replace(Regex("\\s+"), " ")),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                    ArticleTeasers(issue, Modifier.padding(top = 10.dp))
                }
            }
        }
        if (narrow) ArticleTeasers(issue, Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ArticleTeasers(issue: MagazineIssue, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        issue.articles.take(4).forEach { a ->
            Row {
                Text("— ", color = Amber, style = MaterialTheme.typography.bodyMedium)
                Text(
                    a.gameTitle.ifEmpty { a.title },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (issue.articles.size > 4) Text("and ${issue.articles.size - 4} more", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

@Composable
private fun CompactIssue(s: ShelfIssue, numberColumn: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (numberColumn) {
            Box(Modifier.width(72.dp)) { s.issue.issueNumber?.let { IssueNumber(it, 30.sp, stacked = true) } }
        }
        IssueCover(s, 58.dp, 78.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(issueDate(s.issue).uppercase(), style = kicker())
            Text(s.issue.title, style = headline(MaterialTheme.typography.titleLarge), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val n = s.issue.articles.size
            Text(
                listOf(s.issue.subtitle, "$n article" + if (n == 1) "" else "s").filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The pack's cover, or the issue title set as type when the pack has none (or the file is missing). */
@Composable
private fun IssueCover(s: ShelfIssue, width: Dp, height: Dp) {
    val file = s.cover?.file
    Box(Modifier.size(width, height).clip(RoundedCornerShape(4.dp)).background(Slate), contentAlignment = Alignment.Center) {
        if (file != null) {
            CoverImage(file.path, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Text(
                s.issue.title,
                style = headline(MaterialTheme.typography.titleSmall),
                color = Amber,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}

/**
 * "No. 010": an issue's number (see [MagazineIssue.issueNumber]) set large, its digits outlined in amber like the printed
 * numbers of the web reader. [size]: the digits; [stacked] puts "No." above them for a narrow column.
 */
@Composable
private fun IssueNumber(n: Int, size: TextUnit, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val stroke = with(LocalDensity.current) { (size.value / 22f).dp.toPx() }
    val digits: @Composable (Modifier) -> Unit = { m ->
        Text(
            issueNumberText(n),
            style = headline(MaterialTheme.typography.displaySmall).copy(fontSize = size, lineHeight = size, drawStyle = Stroke(width = stroke)),
            color = Amber,
            maxLines = 1,
            modifier = m
        )
    }
    val label = kicker().copy(fontSize = (size.value * 0.3f).coerceAtLeast(11f).sp, fontFamily = FontFamily.Serif)
    val described = modifier.semantics(mergeDescendants = true) { contentDescription = "Issue $n" }
    if (stacked) {
        Column(described) {
            Text("No.", style = label)
            digits(Modifier)
        }
    } else {
        Row(described) {
            Text("No.", style = label, modifier = Modifier.alignByBaseline().padding(end = 6.dp))
            digits(Modifier.alignByBaseline())
        }
    }
}

/** At least three digits, as printed: 10 → "010". */
private fun issueNumberText(n: Int): String = n.toString().padStart(3, '0')

// ---------------------------------------------------------------- contents

/** A reference listed under "Games mentioned": the articles that discuss it and what this device holds for it (null: checking). */
private class MentionedGame(val ref: MagazineGameReference, val articles: List<MagazineArticle>, val matches: List<LocalGameMatch>?) {
    val available: Boolean get() = matches?.any { it.available } == true
}

/**
 * The editorial, the articles, then every game the issue mentions, whether or not it is on this device; A on the filter
 * shows only the games found here. A on a game offers its library page (each matching local file, when there are several)
 * and every article that discusses it. The rights notice closes the page.
 */
@Composable
private fun ContentsView(
    s: ShelfIssue,
    store: MagazineReadingStore,
    resolved: Map<String, List<LocalGameMatch>>?,
    availableOnly: Boolean,
    onAvailableOnly: (Boolean) -> Unit,
    onArticle: (articleId: String, from: String) -> Unit,
    onGame: (String) -> Unit
) {
    val issue = s.issue
    val focus = rememberListFocus { store.setContentsFocus(issue.id, it) }
    val target = remember(issue.id) { store.contentsFocus(issue.id) }
    val systemName = rememberSystemNames()
    val editorial = remember(issue) {
        issue.editorial.split(Regex("\\n\\s*\\n")).map { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }.filter { it.isNotEmpty() }
    }
    val mentioned = remember(issue, resolved) {
        issue.games.map { MentionedGame(it, issue.articlesMentioning(it.id), resolved?.get(it.id)) }
    }
    val availableCount = if (resolved == null) null else mentioned.count { it.available }
    val shown = if (availableOnly) mentioned.filter { it.available } else mentioned
    val keys = remember(issue, editorial, shown) {
        buildList {
            add("masthead")
            if (editorial.isNotEmpty()) add("editorial")
            add("toc")
            issue.articles.forEach { add("article:${it.id}") }
            if (mentioned.isNotEmpty()) {
                add("games")
                add("games:filter")
                shown.forEach { add("game:${it.ref.id}") }
                if (shown.isEmpty()) add("games:none")
            }
            if (issue.rightsNotice.isNotEmpty()) add("rights")
        }
    }
    focus.firstFocusable = 0
    // the filter changing the list keeps the focus where it is (on the filter) rather than returning to the entry target; with
    // the filter on, wait for the device check so a remembered game entry exists before the focus is placed
    if (resolved != null || !availableOnly) FocusOnEntry(focus, keys, keys.filter { it != "toc" && it != "games" }, focus.focused ?: target)
    var menuFor by rememberSaveable(issue.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < NarrowWidth
            LazyColumn(
                Modifier.fillMaxSize(),
                state = focus.state,
                contentPadding = PaddingValues(horizontal = if (narrow) 10.dp else 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item(key = "masthead") {
                    Entry(focus, "masthead", keys.indexOf("masthead"), onClick = null, modifier = Modifier.measure()) { IssueMasthead(s, narrow) }
                }
                if (editorial.isNotEmpty()) {
                    item(key = "editorial") {
                        Entry(focus, "editorial", keys.indexOf("editorial"), onClick = null, modifier = Modifier.measure()) {
                            Column {
                                Text("FROM THE EDITOR", style = kicker())
                                editorial.forEach { p -> Text(inlineText(p), style = bodyStyle(), modifier = Modifier.padding(top = 8.dp)) }
                            }
                        }
                    }
                }
                item(key = "toc") { SectionRule("IN THIS ISSUE") }
                itemsIndexed(issue.articles, key = { _, a -> "article:${a.id}" }) { i, a ->
                    val k = "article:${a.id}"
                    Entry(focus, k, keys.indexOf(k), onClick = { onArticle(a.id, k) }, modifier = Modifier.measure()) {
                        ContentsEntry(s, a, i, narrow, store)
                    }
                }
                if (mentioned.isNotEmpty()) {
                    item(key = "games") { SectionRule("GAMES MENTIONED", top = 26.dp) }
                    item(key = "games:filter") {
                        Entry(
                            focus,
                            "games:filter",
                            keys.indexOf("games:filter"),
                            onClick = { onAvailableOnly(!availableOnly) },
                            modifier = Modifier.measure()
                        ) {
                            GamesFilter(mentioned.size, availableCount, availableOnly)
                        }
                    }
                    itemsIndexed(shown, key = { _, g -> "game:${g.ref.id}" }) { _, g ->
                        val k = "game:${g.ref.id}"
                        Entry(focus, k, keys.indexOf(k), onClick = { menuFor = g.ref.id }, modifier = Modifier.measure()) {
                            MentionedGameEntry(g, systemName)
                        }
                    }
                    if (shown.isEmpty()) {
                        item(key = "games:none") {
                            Entry(focus, "games:none", keys.indexOf("games:none"), onClick = null, modifier = Modifier.measure()) {
                                Text(
                                    if (resolved == null) {
                                        "Checking this device…"
                                    } else {
                                        "None of the games in this issue were found on this device. A on the filter lists them all again."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Muted
                                )
                            }
                        }
                    }
                }
                if (issue.rightsNotice.isNotEmpty()) {
                    item(key = "rights") {
                        Entry(focus, "rights", keys.indexOf("rights"), onClick = null, modifier = Modifier.measure().padding(top = 18.dp)) {
                            Text(issue.rightsNotice, style = MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic), color = Muted)
                        }
                    }
                }
            }
        }
        HintBar("A" to "open", "B" to "shelf")
    }
    val chosen = mentioned.firstOrNull { it.ref.id == menuFor }
    if (menuFor != null && chosen == null) SideEffect { menuFor = null } // the issue changed under the open menu
    if (chosen != null) {
        MagazineMenu(
            title = chosen.ref.title,
            subtitle = referenceLine(chosen.ref, systemName),
            items = gameMenuItems(
                chosen.ref,
                chosen.matches,
                onGame = {
                    menuFor = null
                    onGame(it)
                }
            ) + chosen.articles.map { a ->
                MenuItem("Read “${a.title}”", listOf(a.system, a.gameTitle).filter { it.isNotEmpty() }.joinToString(" · ")) {
                    menuFor = null
                    onArticle(a.id, "game:${chosen.ref.id}")
                }
            },
            onDismiss = { menuFor = null }
        )
    }
}

@Composable
private fun SectionRule(label: String, top: Dp = 14.dp) {
    Column(Modifier.measure().padding(start = 14.dp, end = 14.dp, top = top)) {
        Text(label, style = kicker())
        Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(1.dp).background(Hairline))
    }
}

/** Two settings shown side by side; A (or a tap) switches between them. [available]: null while the device is being checked. */
@Composable
private fun GamesFilter(all: Int, available: Int?, availableOnly: Boolean) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterSegment("All $all", active = !availableOnly)
            FilterSegment(if (available == null) "On this device…" else "On this device $available", active = availableOnly)
        }
        Text(
            if (availableOnly) {
                "Showing the games found on this device. A shows every game the issue mentions."
            } else {
                "A shows only the games found on this device."
            },
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun FilterSegment(label: String, active: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) Ink else Muted,
        modifier = Modifier
            .clip(shape)
            .then(if (active) Modifier.background(Amber) else Modifier.border(1.dp, Hairline, shape))
            .semantics { selected = active }
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun MentionedGameEntry(g: MentionedGame, systemName: (String) -> String) {
    Column {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(g.ref.title, style = headline(MaterialTheme.typography.titleMedium))
                val line = referenceLine(g.ref, systemName)
                if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                availabilityLabel(g.ref, g.matches),
                style = MaterialTheme.typography.labelMedium,
                color = if (g.available) Amber else Muted,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(max = 180.dp)
            )
        }
        if (g.articles.isNotEmpty()) {
            Text(
                "In " + g.articles.joinToString("; ") { "“${it.title}”" },
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** "PlayStation 2 · Original release": the platform as this card names it, and the edition the article means. */
private fun referenceLine(ref: MagazineGameReference, systemName: (String) -> String): String =
    listOf(ref.systemId.takeIf { it.isNotBlank() }?.let(systemName).orEmpty(), ref.edition).filter { it.isNotEmpty() }.joinToString(" · ")

/** What this device holds for [ref]; never says a game is here unless a readable file matched. [matches] null: still checking. */
private fun availabilityLabel(ref: MagazineGameReference, matches: List<LocalGameMatch>?): String {
    if (ref.systemId.isBlank()) return "Reference only"
    if (matches == null) return "Checking…"
    val available = matches.count { it.available }
    return when {
        available == 1 -> "On this device"
        available > 1 -> "$available local copies"
        matches.isEmpty() -> "Not on this device"
        matches.any { it.presence == GamePresence.ARRIVING } -> "Still copying to this device"
        matches.any { it.presence == GamePresence.PLACEHOLDER } -> "In the library, not on this device"
        else -> "In the library, file unreadable"
    }
}

private fun presenceDetail(presence: GamePresence): String = when (presence) {
    GamePresence.AVAILABLE -> "On this device"
    GamePresence.PLACEHOLDER -> "Listed in the library; the file is not on this device"
    GamePresence.ARRIVING -> "Still copying to this device"
    GamePresence.MISSING -> "Listed in the library; the file could not be read"
}

/**
 * The library choices for one reference: a page per matching local file (several editions or files are listed side by side,
 * never one picked arbitrarily), or a disabled line saying why there is nothing to open.
 */
private fun gameMenuItems(ref: MagazineGameReference, matches: List<LocalGameMatch>?, onGame: (String) -> Unit): List<MenuItem> = when {
    ref.systemId.isBlank() -> listOf(MenuItem("No library matching", "This reference does not specify a platform", enabled = false) {})
    matches == null -> listOf(MenuItem("Library page", "Checking this device…", enabled = false) {})
    matches.isEmpty() -> listOf(MenuItem("Library page", "Not found on this device", enabled = false) {})
    matches.size == 1 -> listOf(
        MenuItem("Open the library page", "${matches[0].fileName} · ${presenceDetail(matches[0].presence)}") { onGame(matches[0].gameId) }
    )
    else -> matches.map { m -> MenuItem("Library page: ${m.title}", "${m.gameId} · ${presenceDetail(m.presence)}") { onGame(m.gameId) } }
}

/** A systems.json id as this card labels it; ids the card does not define (an issue may cover any platform) read as written. */
@Composable
private fun rememberSystemNames(): (String) -> String {
    val context = LocalContext.current
    return remember(context) {
        val labels = (context.applicationContext as SortieApp).graph.manifests.snapshot.value.systems.associate { it.id.lowercase() to it.label }
        val name: (String) -> String = { id -> labels[id.lowercase()] ?: if (id.equals("arcade", ignoreCase = true)) "Arcade" else id.uppercase() }
        name
    }
}

@Composable
private fun IssueMasthead(s: ShelfIssue, narrow: Boolean) {
    val issue = s.issue
    val text: @Composable () -> Unit = {
        Column {
            issue.issueNumber?.let { IssueNumber(it, if (narrow) 38.sp else 52.sp, Modifier.padding(bottom = 4.dp)) }
            Text(issueDate(issue).uppercase(), style = kicker())
            Text(issue.title, style = headline(if (narrow) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall))
            if (issue.subtitle.isNotEmpty()) Text(issue.subtitle, style = deckStyle(), color = Muted, modifier = Modifier.padding(top = 4.dp))
            val n = issue.articles.size
            val g = issue.games.size
            Text(
                listOfNotNull(
                    "$n article" + if (n == 1) "" else "s",
                    if (g == 0) null else "$g game" + (if (g == 1) "" else "s") + " mentioned"
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
    Column {
        if (narrow) {
            Row {
                IssueCover(s, 104.dp, 140.dp)
                Spacer(Modifier.width(14.dp))
                text()
            }
        } else {
            Row {
                IssueCover(s, 180.dp, 240.dp)
                Spacer(Modifier.width(22.dp))
                text()
            }
        }
        s.cover?.problem?.let { PackNote("Cover: $it") }
    }
}

@Composable
private fun ContentsEntry(s: ShelfIssue, a: MagazineArticle, index: Int, narrow: Boolean, store: MagazineReadingStore) {
    val issueId = s.issue.id
    val status = when {
        store.finished(issueId, a.id) -> "Read"
        store.started(issueId, a.id) -> "Continue reading"
        else -> null
    }
    Row {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = headline(MaterialTheme.typography.headlineMedium),
            color = Amber,
            modifier = Modifier.width(if (narrow) 44.dp else 58.dp)
        )
        Column(Modifier.weight(1f)) {
            val kick = listOf(a.system, a.gameTitle).filter { it.isNotEmpty() }.joinToString(" · ")
            if (kick.isNotEmpty()) Text(kick.uppercase(), style = kicker(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(a.title, style = headline(MaterialTheme.typography.titleLarge))
            if (a.deck.isNotEmpty()) {
                Text(
                    inlineText(a.deck),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            val meta = listOfNotNull(a.readingMinutes?.let { "$it min read" }, status).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status != null) Amber else Muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        val thumb = s.images[a.id]?.file
        if (!narrow && thumb != null) {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(132.dp, 88.dp).clip(RoundedCornerShape(6.dp)).background(Slate)) {
                CoverImage(thumb.path, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

// ---------------------------------------------------------------- article

/** [ArticleReader]'s editor target when nothing is being clipped; [CLIP_WHOLE_ARTICLE] clips the article, `>= 0` a block. */
private const val NOT_CLIPPING = Int.MIN_VALUE
private const val CLIP_WHOLE_ARTICLE = -1

/** An article's text with its pictures, each with its pixel size already read. */
private class OpenedArticle(val content: ArticleContent, val figures: List<ArticleFigure>)

/** The pictures the reader shows, in reading order: the lead image, then every Markdown image whose file is in the issue. Blocking. */
private fun articleFigures(s: ShelfIssue, article: MagazineArticle, content: ArticleContent): List<ArticleFigure> = buildList {
    s.images[article.id]?.file?.let { add(ArticleFigure(HERO_KEY, it, article.imageCaption, imageSize(it))) }
    if (content is ArticleContent.Ready) {
        content.blocks.forEachIndexed { i, b ->
            if (b is MdBlock.Image) content.images[b.src]?.file?.let { add(ArticleFigure("b$i", it, b.caption, imageSize(it))) }
        }
    }
}

/**
 * Clipping in the reader: Y (or the Highlight chip) enters marking, where ↑/↓ choose a passage (a paragraph, bullet or quote)
 * and A saves it with an optional note; a tap chooses one, a long press or the accessibility action opens it straight away.
 * Y or B leaves marking. Select opens the reader menu: mark, clip the whole article as a recommendation, the scrapbook.
 * Saved highlights are marked in the text while their passage is still in the article; [jumpTo] (a clipping id) scrolls to
 * its passage once, or says the passage is gone instead of landing anywhere else.
 * Pictures: a tap on one, or R2 for the one most on screen (the hint bar offers it), opens [FigureViewer] over the article,
 * which stays where it was.
 */
@Composable
private fun ArticleReader(
    issue: ShelfIssue,
    article: MagazineArticle,
    repo: MagazineRepository,
    games: MagazineGames,
    resolved: Map<String, List<LocalGameMatch>>?,
    store: MagazineReadingStore,
    readerOwner: Any,
    onGame: (String) -> Unit,
    next: MagazineArticle?,
    onArticle: (String) -> Unit,
    clippings: List<MagazineClipping>,
    user: UserStore,
    jumpTo: String?,
    onJumped: () -> Unit,
    onScrapbook: () -> Unit,
    contentsKey: String?,
    backLabel: String
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        store.opened(issue.issue.id, article.id)
        contentsKey?.let { store.setContentsFocus(issue.issue.id, it) }
    }
    // the text and its pictures' pixel sizes arrive together, so every figure is laid out at its final size from the first frame
    // and a restored scroll position never shifts while pictures load
    val opened by produceState<OpenedArticle?>(null) {
        val c = repo.article(issue, article)
        value = OpenedArticle(c, withContext(Dispatchers.IO) { articleFigures(issue, article, c) })
    }
    val content = opened?.content
    val figures = opened?.figures.orEmpty()
    if (content == null) PublishMagazineCompanion(readerOwner, issue.issue, article.id)
    var picking by rememberSaveable { mutableStateOf(false) }
    var marking by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(-1) }
    var clipping by rememberSaveable { mutableStateOf(NOT_CLIPPING) }
    var menu by rememberSaveable { mutableStateOf(false) }
    var hangar by rememberSaveable { mutableStateOf(false) }
    // the full-screen picture (its figure key), and the one R2 would open
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    var figureInView by remember { mutableStateOf<String?>(null) }
    val blocks = (content as? ArticleContent.Ready)?.blocks.orEmpty()
    val highlights = remember(clippings, blocks) { clippings.highlightsIn(issue.issue.id, article.id, blocks) }
    val articleClip = remember(clippings) { clippings.articleClip(issue.issue.id, article.id) }
    val canMark = blocks.any { it.clipText() != null }
    val jump = remember(jumpTo, clippings) { clippings.firstOrNull { it.id == jumpTo } }
    fun stopMarking() {
        marking = false
        selected = -1
    }
    fun toggleMarking() {
        if (marking) stopMarking() else if (canMark) marking = true
    }
    BackHandler(enabled = marking) { stopMarking() }
    // A: straight to the library page when the article's one game matched one local file; otherwise the choices
    fun openGames() {
        val only = article.games.singleOrNull()?.let { resolved?.get(it.id) }?.singleOrNull()
        when {
            article.games.isEmpty() -> Unit
            only != null -> onGame(only.gameId)
            else -> picking = true
        }
    }

    /** The local game ids this article's references resolve to, kept with a clipping so the scrapbook can open them. */
    suspend fun localGameIds(): List<String> {
        if (article.games.isEmpty()) return emptyList()
        val map = resolved ?: games.resolve(article.games)
        return article.games.flatMap { map[it.id].orEmpty() }.map { it.gameId }.distinct()
    }
    val gamesLabel = remember(article, resolved) { articleGamesLabel(article.games, resolved) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val c = content
            if (c == null) {
                Text("Opening article…", color = Muted, modifier = Modifier.align(Alignment.Center))
            } else {
                val narrow = maxWidth < NarrowWidth
                val density = LocalDensity.current.density
                val reading = maxWidth - (if (narrow) 16.dp else 28.dp) * 2
                val boxes = remember(figures, maxWidth, maxHeight, density) {
                    val column = Measure.coerceAtMost(reading).value
                    val wide = WideMeasure.coerceAtMost(reading).value
                    figures.associate { f ->
                        // the lead image leaves room for the headline under it; figures may take most of the screen
                        val hero = f.key == HERO_KEY
                        val cap = (maxHeight * if (hero) 0.55f else 0.7f).value.coerceAtLeast(120f)
                        val limit = if (hero) wide else column
                        f.key to (f.size?.let { figureBox(it.width, it.height, limit, wide, cap, density) } ?: unknownFigureBox(limit, cap))
                    }
                }
                ArticleList(
                    issue,
                    article,
                    c,
                    store,
                    readerOwner = readerOwner,
                    narrow = narrow,
                    figures = figures,
                    boxes = boxes,
                    onView = { viewing = it },
                    onFigureInView = { figureInView = it },
                    onGames = ::openGames,
                    gamesLabel = gamesLabel,
                    next = next,
                    onArticle = onArticle,
                    clips = ReaderClips(
                        highlights = highlights,
                        articleClipped = articleClip != null,
                        marking = marking,
                        selected = selected,
                        jump = jump,
                        onSelect = { selected = it },
                        onClip = { clipping = it },
                        onToggleMarking = ::toggleMarking,
                        onMenu = { menu = true },
                        onScrapbook = onScrapbook,
                        onJumped = onJumped
                    )
                )
            }
        }
        if (marking) {
            MarkingBar(
                selectedHighlighted = selected in highlights,
                canClip = selected >= 0,
                onClip = { if (selected >= 0) clipping = selected },
                onDone = ::stopMarking
            )
        }
        val hints = buildList {
            if (marking) {
                add("↑/↓" to "choose passage")
                add("A" to if (selected in highlights) "edit highlight" else "highlight")
                add("Y/B" to "done")
            } else {
                add("D-pad" to "scroll")
                add("←/→" to "page")
                add("L1/R1" to "articles")
                if (article.games.isNotEmpty()) add("A" to if (article.games.size == 1) "library page" else "games")
                if (figureInView != null) add("R2" to "view picture")
                if (canMark) add("Y" to "highlight")
                add("Select" to "reader menu")
                add("B" to backLabel)
            }
        }
        HintBar(*hints.toTypedArray())
    }
    if (picking) {
        ArticleGamesPicker(article.games, resolved, onPick = {
            picking = false
            onGame(it)
        }, onDismiss = { picking = false })
    }
    if (menu) {
        val n = clippings.size
        MagazineMenu(
            title = article.title,
            subtitle = "Reader tools",
            items = listOf(
                MenuItem("Highlight a passage", "Y · ↑/↓ choose a paragraph, A saves it with an optional note", enabled = canMark) {
                    menu = false
                    if (!marking) toggleMarking()
                },
                MenuItem(
                    if (articleClip == null) "Clip this article" else "Edit this article's clipping",
                    "Keeps the recommendation, its games and your note in the scrapbook"
                ) {
                    menu = false
                    clipping = CLIP_WHOLE_ARTICLE
                },
                MenuItem("Open the scrapbook", if (n == 1) "1 clipping" else "$n clippings") {
                    menu = false
                    onScrapbook()
                },
                MenuItem("Visit the hangar", "Care for your companion or try different parts; your reading position stays here") {
                    menu = false
                    hangar = true
                }
            ),
            onDismiss = { menu = false }
        )
    }
    if (hangar) {
        Hangar((context.applicationContext as SortieApp).graph.mecha, onClose = { hangar = false })
    }
    // after recreation the viewer reopens once the pictures are known again
    if (opened != null) viewing?.let { start -> FigureViewer(figures, start, onClose = { viewing = null }) }
    val target = clipping
    if (target != NOT_CLIPPING) {
        val close = { clipping = NOT_CLIPPING }
        if (target == CLIP_WHOLE_ARTICLE) {
            val existing = articleClip
            ClipEditor(
                draftKey = existing?.id ?: "article:${issue.issue.id}/${article.id}",
                heading = if (existing == null) "Clip recommendation" else "Clipped recommendation",
                source = clipSource(issue.issue.title, article.title),
                excerpt = article.deck.ifEmpty { blocks.firstOrNull { it is MdBlock.Paragraph }?.clipText().orEmpty() },
                shortened = article.deck.isEmpty() && (blocks.firstOrNull { it is MdBlock.Paragraph }?.clipText()?.length ?: 0) > MAX_CLIP_TEXT,
                initialNote = existing?.note.orEmpty(),
                existing = existing != null,
                save = { note ->
                    val now = System.currentTimeMillis()
                    attempt {
                        user.saveClipping(
                            existing?.copy(note = note, updated = now) ?: articleClipping(issue.issue, article, localGameIds(), blocks, note, now)
                        )
                    }
                },
                remove = { attempt { existing?.let { user.removeClipping(it.id) } } },
                onClose = close,
                onDone = { removed ->
                    close()
                    Toast.makeText(context, if (removed) "Clipping removed" else "Clipped to your scrapbook", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            val text = blocks.getOrNull(target)?.clipText()
            if (text == null) {
                // the article was reloaded with different content (recreation): nothing safe to clip at that index
                if (content != null) SideEffect { clipping = NOT_CLIPPING }
            } else {
                val existing = highlights[target]
                ClipEditor(
                    draftKey = existing?.id ?: "highlight:${issue.issue.id}/${article.id}/$target",
                    heading = if (existing == null) "Highlight" else "Your highlight",
                    source = clipSource(issue.issue.title, article.title),
                    excerpt = text,
                    shortened = existing?.shortened ?: (text.length > MAX_CLIP_TEXT),
                    initialNote = existing?.note.orEmpty(),
                    existing = existing != null,
                    save = { note ->
                        val now = System.currentTimeMillis()
                        attempt {
                            user.saveClipping(
                                existing?.copy(note = note, updated = now) ?: highlightClipping(issue.issue, article, localGameIds(), target, text, note, now)
                            )
                        }
                    },
                    remove = { attempt { existing?.let { user.removeClipping(it.id) } } },
                    onClose = close,
                    onDone = { removed ->
                        close()
                        stopMarking()
                        Toast.makeText(context, if (removed) "Highlight removed" else "Highlighted — kept in your scrapbook", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

/** Touch controls for marking (the controller uses ↑/↓, A and Y/B); sits above the hint bar while marking. */
@Composable
private fun MarkingBar(selectedHighlighted: Boolean, canClip: Boolean, onClip: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Slate).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (canClip) "Passage chosen" else "Tap a paragraph, or ↑/↓",
            style = MaterialTheme.typography.labelLarge,
            color = Amber,
            modifier = Modifier.weight(1f)
        )
        Button(onClick = onClip, enabled = canClip, colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink)) {
            Text(if (selectedHighlighted) "Edit highlight" else "Highlight")
        }
        OutlinedButton(onClick = onDone) { Text("Done") }
    }
}

/** Everything the article list needs to show and make clippings; state lives in [ArticleReader]. */
private class ReaderClips(
    val highlights: Map<Int, MagazineClipping>,
    val articleClipped: Boolean,
    val marking: Boolean,
    val selected: Int,
    val jump: MagazineClipping?,
    val onSelect: (Int) -> Unit,
    val onClip: (Int) -> Unit,
    val onToggleMarking: () -> Unit,
    val onMenu: () -> Unit,
    val onScrapbook: () -> Unit,
    val onJumped: () -> Unit
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArticleList(
    s: ShelfIssue,
    article: MagazineArticle,
    content: ArticleContent,
    store: MagazineReadingStore,
    readerOwner: Any,
    narrow: Boolean,
    figures: List<ArticleFigure>,
    boxes: Map<String, FigureBox>,
    onView: (String) -> Unit,
    onFigureInView: (String?) -> Unit,
    onGames: () -> Unit,
    gamesLabel: String?,
    next: MagazineArticle?,
    onArticle: (String) -> Unit,
    clips: ReaderClips
) {
    val issueId = s.issue.id
    val saved = remember { store.position(issueId, article.id) }
    // saved state wins over the stored position after recreation or the swap; the stored one serves a fresh entry
    val state = rememberLazyListState(saved.index, saved.offset)
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    val requester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        repeat(25) {
            inputMode.requestInputMode(InputMode.Keyboard)
            if (hasFocus) return@LaunchedEffect
            runCatching { requester.requestFocus() }
            delay(40)
        }
    }
    fun position() = MagazineReadingStore.Position(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    fun atEnd() = state.layoutInfo.totalItemsCount > 0 && !state.canScrollForward
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, atEnd()) }.collectLatest {
            delay(400) // settle: one write per pause, not per frame
            store.savePosition(issueId, article.id, position(), it.third)
        }
    }
    DisposableEffect(state) { onDispose { store.savePosition(issueId, article.id, position(), atEnd()) } }
    val sections = remember(content, s.images[article.id]) {
        readerOutline((content as? ArticleContent.Ready)?.blocks.orEmpty(), s.images[article.id] != null)
    }
    val activeSection by remember(state, sections) {
        derivedStateOf { sections.indexOfLast { it.itemIndex <= state.firstVisibleItemIndex }.coerceAtLeast(0) }
    }
    PublishMagazineCompanion(readerOwner, s.issue, article.id, sections, activeSection)
    LaunchedEffect(readerOwner, article.id, state, sections) {
        Selection.magazineActions.collect { action ->
            if (action is MagazineAction.Jump && action.owner === readerOwner && action.issueId == issueId && action.articleId == article.id &&
                sections.any { it.itemIndex == action.itemIndex }
            ) {
                state.scrollToItem(action.itemIndex)
            }
        }
    }
    val blocks = (content as? ArticleContent.Ready)?.blocks.orEmpty()
    val bodyStart = if (s.images[article.id] != null) 2 else 1
    val clippable = remember(blocks) { blocks.indices.filter { blocks[it].clipText() != null } }
    val context = LocalContext.current
    // the passage a scrapbook entry opened, outlined for a moment
    var flash by remember { mutableStateOf(-1) }
    LaunchedEffect(flash) {
        if (flash >= 0) {
            delay(2_500)
            flash = -1
        }
    }
    val figureKeys = remember(figures) { figures.mapTo(HashSet()) { it.key } }

    /** The picture most on screen, when at least half of it (or half the screen) shows: what R2 opens full screen. */
    fun figureInView(): String? {
        val info = state.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        return info.visibleItemsInfo.mapNotNull { item ->
            val key = (item.key as? String)?.takeIf { it in figureKeys } ?: return@mapNotNull null
            val shown = min(item.offset + item.size, info.viewportEndOffset) - max(item.offset, info.viewportStartOffset)
            if (shown * 2 >= min(item.size, viewport)) key to shown else null
        }.maxByOrNull { it.second }?.first
    }
    LaunchedEffect(state, figureKeys) { snapshotFlow { figureInView() }.collect { onFigureInView(it) } }
    LaunchedEffect(clips.jump?.id, content) {
        val clip = clips.jump ?: return@LaunchedEffect
        if (content is ArticleContent.Ready) {
            if (clip.isArticle) {
                state.scrollToItem(0)
            } else {
                val i = clip.locate(content.blocks)
                if (i == null) {
                    Toast.makeText(
                        context,
                        "That passage is no longer in this article — the issue changed. Your copy stays in the scrapbook.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    withTimeoutOrNull(1_000) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > bodyStart + i } }
                    state.scrollToItem(bodyStart + i)
                    flash = i
                }
            }
        }
        clips.onJumped() // last: this restarts the effect, which would cancel the scroll
    }

    /** The first passage whose top is on screen (else the first one partly on screen), to start marking from. */
    fun nearestVisible(): Int {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo.mapNotNull { item ->
            val i = item.index - bodyStart
            if (blocks.getOrNull(i)?.clipText() != null) i to item else null
        }
        return (visible.firstOrNull { it.second.offset >= info.viewportStartOffset } ?: visible.firstOrNull())?.first
            ?: clippable.firstOrNull { it + bodyStart >= state.firstVisibleItemIndex }
            ?: clippable.lastOrNull()
            ?: -1
    }
    LaunchedEffect(clips.marking) {
        if (clips.marking && clips.selected !in clippable) clips.onSelect(nearestVisible())
    }

    /** Scrolls passage [i] fully into view when it fits, else its top to the top. */
    suspend fun reveal(i: Int) {
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == bodyStart + i }
        if (item == null) {
            state.animateScrollToItem(bodyStart + i)
            return
        }
        val top = item.offset - info.viewportStartOffset
        val bottomHidden = item.offset + item.size - info.viewportEndOffset
        when {
            top < 0 -> state.animateScrollBy(top.toFloat())
            bottomHidden > 0 -> state.animateScrollBy(min(bottomHidden, top).toFloat())
        }
    }

    /** Marking, ↑/↓: read through a passage taller than the screen first, then choose the next passage. */
    fun move(dir: Int) {
        val current = clips.selected
        if (current !in clippable) {
            clips.onSelect(nearestVisible())
            return
        }
        val info = state.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == bodyStart + current }
        if (item != null) {
            val hidden = if (dir > 0) item.offset + item.size - info.viewportEndOffset else info.viewportStartOffset - item.offset
            if (hidden > 1) {
                scope.launch { state.scrollBy(dir * min(step, hidden.toFloat())) }
                return
            }
        }
        val next = if (dir > 0) clippable.firstOrNull { it > current } else clippable.lastOrNull { it < current }
        if (next == null) {
            scope.launch { state.scrollBy(dir * step) } // past the last passage: the end (or the head) is still reachable
        } else {
            clips.onSelect(next)
            scope.launch { reveal(next) }
        }
    }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .focusRequester(requester)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                val down = e.type == KeyEventType.KeyDown
                val first = down && e.nativeKeyEvent.repeatCount == 0
                val page = state.layoutInfo.viewportSize.height * 0.85f
                fun turnPage(by: Float) {
                    scope.launch {
                        state.animateScrollBy(by)
                        if (clips.marking) clips.onSelect(nearestVisible())
                    }
                }
                when (e.key) {
                    Key.DirectionDown -> {
                        if (down) if (clips.marking) move(1) else scope.launch { state.scrollBy(step) }
                        true
                    }
                    Key.DirectionUp -> {
                        if (down) if (clips.marking) move(-1) else scope.launch { state.scrollBy(-step) }
                        true
                    }
                    Key.DirectionRight, Key.PageDown -> {
                        if (down) turnPage(page)
                        true
                    }
                    Key.DirectionLeft, Key.PageUp -> {
                        if (down) turnPage(-page)
                        true
                    }
                    Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (first) if (clips.marking) clips.selected.takeIf { it in clippable }?.let(clips.onClip) else onGames()
                        true
                    }
                    Key.ButtonY -> {
                        if (first) clips.onToggleMarking()
                        true
                    }
                    Key.ButtonSelect -> {
                        if (first) clips.onMenu()
                        true
                    }
                    // R2 zooms in the picture viewer too; here it opens the picture in view (never while marking)
                    Key.ButtonR2 -> {
                        if (first && !clips.marking) figureInView()?.let(onView)
                        true
                    }
                    else -> false
                }
            },
        state = state,
        contentPadding = PaddingValues(horizontal = if (narrow) 16.dp else 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val lead = s.images[article.id]
        if (lead != null) item(key = HERO_KEY) { Hero(lead, article.imageCaption, boxes[HERO_KEY]) { onView(HERO_KEY) } }
        item(key = "head") {
            ArticleHead(
                s.issue,
                article,
                onGames,
                gamesLabel,
                canMark = clippable.isNotEmpty(),
                marking = clips.marking,
                highlightCount = clips.highlights.size,
                articleClipped = clips.articleClipped,
                onMark = clips.onToggleMarking,
                onClipArticle = { clips.onClip(CLIP_WHOLE_ARTICLE) },
                onScrapbook = clips.onScrapbook
            )
        }
        when (content) {
            is ArticleContent.Ready -> itemsIndexed(content.blocks, key = { i, _ -> "b$i" }) { i, b ->
                val mark = if (b.clipText() == null) {
                    null
                } else {
                    BlockMark(
                        highlight = clips.highlights[i],
                        selected = (clips.marking && clips.selected == i) || flash == i,
                        onTap = {
                            when {
                                clips.marking && clips.selected == i -> clips.onClip(i)
                                clips.marking -> clips.onSelect(i)
                                i in clips.highlights -> clips.onClip(i)
                            }
                        },
                        onHold = { clips.onClip(i) }
                    )
                }
                ArticleBlock(b, content.images, boxes["b$i"], onView = { onView("b$i") }, mark)
            }
            is ArticleContent.Failed -> item(key = "error") {
                Column(Modifier.measure().padding(vertical = 16.dp)) {
                    Text("This article could not be opened", style = MaterialTheme.typography.titleMedium, color = Warning)
                    Text(content.reason, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                    Text(
                        "The rest of the issue is unaffected: B returns to the contents.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        item(key = "end") { ArticleEnd(s.issue, next, onArticle) }
    }
}

@Composable
private fun Hero(lead: IssueImage, caption: String, box: FigureBox?, onView: () -> Unit) {
    val file = lead.file
    if (file != null && box != null) {
        Figure(file, caption, box, RoundedCornerShape(8.dp), onView)
    } else {
        Column(Modifier.widthIn(max = WideMeasure).fillMaxWidth()) { lead.problem?.let { PackNote("Lead image: $it") } }
    }
}

/**
 * A picture at its final [box] size — the slate shows only while it loads, so nothing below moves — with its caption under it
 * at the picture's width (never narrower than [MinCaptionWidth]). A tap opens the full-screen viewer; the controller uses R2.
 */
@Composable
private fun Figure(file: File, caption: String, box: FigureBox, shape: Shape, onView: () -> Unit, modifier: Modifier = Modifier) {
    val width = box.width.dp
    Column(modifier.width(width.coerceAtLeast(MinCaptionWidth)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width, box.height.dp)
                .clip(shape)
                .background(Slate)
                // touch only: the D-pad scrolls the article and never stops on a picture
                .focusProperties { canFocus = false }
                .clickable(role = Role.Image, onClickLabel = "View full screen", onClick = onView)
        ) {
            CoverImage(
                file.path,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = if (box.upscaled) FilterQuality.None else DrawScope.DefaultFilterQuality
            )
        }
        if (caption.isNotEmpty()) Text(inlineText(caption), style = captionStyle(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArticleHead(
    issue: MagazineIssue,
    article: MagazineArticle,
    onGames: () -> Unit,
    gamesLabel: String?,
    canMark: Boolean,
    marking: Boolean,
    highlightCount: Int,
    articleClipped: Boolean,
    onMark: () -> Unit,
    onClipArticle: () -> Unit,
    onScrapbook: () -> Unit
) {
    Column(Modifier.measure().padding(top = 18.dp, bottom = 8.dp)) {
        val kick = listOf(article.system, article.gameTitle).filter { it.isNotEmpty() }.joinToString(" · ")
        if (kick.isNotEmpty()) Text(kick.uppercase(), style = kicker())
        Text(article.title, style = headline(MaterialTheme.typography.headlineMedium), modifier = Modifier.padding(top = 4.dp))
        if (article.deck.isNotEmpty()) Text(inlineText(article.deck), style = deckStyle(), modifier = Modifier.padding(top = 10.dp))
        val meta = listOfNotNull(issue.title, issueDate(issue), article.readingMinutes?.let { "$it min read" }).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 10.dp))
        if (gamesLabel != null) {
            Text(
                gamesLabel,
                style = MaterialTheme.typography.labelLarge,
                color = Amber,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Amber.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onGames)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        // touch twins of Y and Select; the controller never focuses these (the D-pad scrolls the article)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canMark) {
                HeadChip(
                    when {
                        marking -> "Y  Done marking"
                        highlightCount > 0 -> "Y  Highlight · $highlightCount saved"
                        else -> "Y  Highlight a passage"
                    },
                    onMark
                )
            }
            HeadChip(if (articleClipped) "Clipped ✓  edit" else "Select  Clip article", onClipArticle)
            HeadChip("Scrapbook", onScrapbook)
        }
        Box(Modifier.padding(top = 16.dp).width(56.dp).height(3.dp).background(Amber))
    }
}

/**
 * The reader's A control, as its touch chip reads: one game's library page, or how many games the article names and how many
 * of them this device holds (left out until the device check is done). Null when the article names none.
 */
private fun articleGamesLabel(refs: List<MagazineGameReference>, resolved: Map<String, List<LocalGameMatch>>?): String? {
    val only = refs.singleOrNull()
    return when {
        refs.isEmpty() -> null
        only != null -> {
            val matches = resolved?.get(only.id)
            val available = matches?.count { it.available } ?: 0
            when {
                available > 1 -> "A  Library page: ${only.title} · $available versions"
                available == 1 -> "A  Library page: ${only.title}"
                else -> "A  ${only.title} · ${availabilityLabel(only, matches)}"
            }
        }
        resolved == null -> "A  ${refs.size} games in this article"
        else -> "A  ${refs.size} games in this article · ${refs.count { r -> resolved[r.id].orEmpty().any { it.available } }} on this device"
    }
}

@Composable
private fun HeadChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/**
 * How a passage that can be clipped is drawn and touched: [highlight] tints it with an amber margin rule (and shows its
 * note), [selected] outlines it. [onTap] chooses or opens it, [onHold] (long press, or the accessibility action) opens it.
 */
private class BlockMark(val highlight: MagazineClipping?, val selected: Boolean, val onTap: () -> Unit, val onHold: () -> Unit)

@Composable
private fun ArticleBlock(b: MdBlock, images: Map<String, IssueImage>, figure: FigureBox?, onView: () -> Unit, mark: BlockMark? = null) {
    val m = Modifier.measure()
    when (b) {
        is MdBlock.Heading -> when (b.level) {
            1 -> Text(b.text, style = headline(MaterialTheme.typography.headlineSmall), modifier = m.padding(top = 22.dp, bottom = 8.dp))
            2 -> Text(b.text, style = headline(MaterialTheme.typography.titleLarge), color = Amber, modifier = m.padding(top = 24.dp, bottom = 8.dp))
            else -> Text(b.text, style = headline(MaterialTheme.typography.titleMedium), modifier = m.padding(top = 16.dp, bottom = 6.dp))
        }
        is MdBlock.Paragraph, is MdBlock.Bullet, is MdBlock.Quote -> TextBlock(b, mark)
        is MdBlock.Image -> {
            val image = images[b.src]
            val file = image?.file
            if (file != null && figure != null) {
                Figure(file, b.caption, figure, RoundedCornerShape(6.dp), onView, Modifier.padding(top = 6.dp, bottom = 18.dp))
            } else {
                Column(m.padding(top = 6.dp, bottom = 18.dp)) { PackNote(image?.problem ?: "Image missing: ${b.src}") }
            }
        }
        MdBlock.Rule -> Box(m.padding(vertical = 14.dp).height(1.dp).background(Hairline))
    }
}

/** A paragraph, bullet or quote. The marks are drawn just outside the text so marking never reflows the article. */
@Composable
private fun TextBlock(b: MdBlock, mark: BlockMark?) {
    val outer = when (b) {
        is MdBlock.Quote -> PaddingValues(top = 6.dp, bottom = 16.dp)
        is MdBlock.Bullet -> PaddingValues(start = 4.dp, bottom = 8.dp)
        else -> PaddingValues(bottom = 14.dp)
    }
    val highlighted = mark?.highlight != null
    val selected = mark?.selected == true
    val tap by rememberUpdatedState(mark?.onTap)
    val hold by rememberUpdatedState(mark?.onHold)
    val touch = if (mark == null) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { tap?.invoke() }, onLongPress = { hold?.invoke() }) }
            .semantics {
                this.selected = selected
                customActions = listOf(
                    CustomAccessibilityAction(if (highlighted) "Edit highlight" else "Highlight this passage") {
                        hold?.invoke()
                        true
                    }
                )
            }
    }
    Column(
        Modifier
            .measure()
            .padding(outer)
            .drawBehind {
                if (!highlighted && !selected) return@drawBehind
                val h = 10.dp.toPx()
                val v = 4.dp.toPx()
                val corner = CornerRadius(6.dp.toPx())
                val topLeft = Offset(-h, -v)
                val box = Size(size.width + 2 * h, size.height + 2 * v)
                if (highlighted) {
                    drawRoundRect(Amber.copy(alpha = 0.12f), topLeft, box, corner)
                    drawRect(Amber, topLeft, Size(3.dp.toPx(), box.height))
                }
                if (selected) drawRoundRect(Amber, topLeft, box, corner, style = Stroke(2.dp.toPx()))
            }
            .then(touch)
    ) {
        when (b) {
            is MdBlock.Paragraph -> Text(inlineText(b.text), style = bodyStyle())
            is MdBlock.Bullet -> Row {
                Text("•", style = bodyStyle(), color = Amber, modifier = Modifier.width(18.dp))
                Text(inlineText(b.text), style = bodyStyle().copy(fontSize = 16.sp, lineHeight = 25.sp))
            }
            is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Amber))
                Text(
                    inlineText(b.text),
                    style = bodyStyle().copy(fontStyle = FontStyle.Italic, fontSize = 19.sp, lineHeight = 29.sp),
                    modifier = Modifier.padding(start = 14.dp)
                )
            }
            else -> Unit
        }
        val note = mark?.highlight?.note.orEmpty()
        if (note.isNotEmpty()) {
            Text(
                "✎  $note",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = Amber,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun ArticleEnd(issue: MagazineIssue, next: MagazineArticle?, onArticle: (String) -> Unit) {
    Column(Modifier.measure().padding(top = 18.dp, bottom = 28.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
        if (next != null) {
            Text("NEXT IN ${issue.title.uppercase()} · R1", style = kicker(), modifier = Modifier.padding(top = 16.dp))
            Text(
                next.title,
                style = headline(MaterialTheme.typography.titleLarge),
                modifier = Modifier.padding(top = 4.dp).clickable { onArticle(next.id) }
            )
            if (next.deck.isNotEmpty()) {
                Text(
                    inlineText(next.deck),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Text("End of ${issue.title}", style = deckStyle(), modifier = Modifier.padding(top = 16.dp))
            Text("B returns to the contents.", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * The games an article discusses, each with its library choices: every matching local file when there are several editions
 * or copies, or why there is nothing to open. [resolved] null: the device is still being checked.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArticleGamesPicker(
    refs: List<MagazineGameReference>,
    resolved: Map<String, List<LocalGameMatch>>?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val systemName = rememberSystemNames()
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Slate) {
            val first = remember { FocusRequester() }
            var focusedAny by remember { mutableStateOf(false) }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                repeat(25) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    if (focusedAny) return@LaunchedEffect
                    runCatching { first.requestFocus() }
                    delay(40)
                }
            }
            val options = refs.map { it to gameMenuItems(it, resolved?.get(it.id), onPick) }
            val firstEnabled = options.flatMap { it.second }.firstOrNull { it.enabled }
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()).onFocusChanged { if (it.hasFocus) focusedAny = true }) {
                Text("Games in this article", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Library pages for what the article discusses. Games not found on this device are still listed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                options.forEach { (ref, items) ->
                    Text(ref.title, style = headline(MaterialTheme.typography.titleSmall), modifier = Modifier.padding(top = 12.dp, start = 8.dp))
                    val line = referenceLine(ref, systemName)
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(start = 8.dp))
                    items.forEach { item ->
                        val body: @Composable () -> Unit = {
                            Column {
                                Text(item.label, style = MaterialTheme.typography.bodyMedium, color = if (item.enabled) Color.Unspecified else Muted)
                                if (item.detail.isNotEmpty()) Text(item.detail, style = MaterialTheme.typography.labelSmall, color = Muted)
                            }
                        }
                        if (item.enabled) {
                            FocusRow(onClick = item.onClick, modifier = if (item === firstEnabled) Modifier.focusRequester(first) else Modifier, content = body)
                        } else {
                            Box(Modifier.fillMaxWidth().padding(8.dp)) { body() }
                        }
                    }
                }
                FocusRow(onClick = onDismiss, modifier = (if (firstEnabled == null) Modifier.focusRequester(first) else Modifier).padding(top = 10.dp)) {
                    Text("Close", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

/** A clipping's saved local game ids (more than one): pick which library page to open. The page may say the game is not indexed. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun GamePicker(ids: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Slate) {
            val first = remember { FocusRequester() }
            var focusedAny by remember { mutableStateOf(false) }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                repeat(25) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    if (focusedAny) return@LaunchedEffect
                    runCatching { first.requestFocus() }
                    delay(40)
                }
            }
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()).onFocusChanged { if (it.hasFocus) focusedAny = true }) {
                Text("Look up in the library", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sortie's page for each game the article names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                ids.forEachIndexed { i, id ->
                    FocusRow(onClick = { onPick(id) }, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                        Column {
                            Text(gameName(id), style = MaterialTheme.typography.titleSmall)
                            Text(id.substringBefore('/'), style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                }
            }
        }
    }
}

/** One choice in [MagazineMenu]; a disabled one is shown with its [detail] (why) but cannot be chosen. */
private class MenuItem(val label: String, val detail: String = "", val enabled: Boolean = true, val onClick: () -> Unit)

/** A short list of actions for the controller and touch alike: the first enabled one takes the focus; B dismisses. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MagazineMenu(title: String, subtitle: String, items: List<MenuItem>, error: String? = null, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = Slate) {
            val first = remember { FocusRequester() }
            var focusedAny by remember { mutableStateOf(false) }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                repeat(25) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    if (focusedAny) return@LaunchedEffect
                    runCatching { first.requestFocus() }
                    delay(40)
                }
            }
            val firstEnabled = items.indexOfFirst { it.enabled }
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()).onFocusChanged { if (it.hasFocus) focusedAny = true }) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
                }
                items.forEachIndexed { i, item ->
                    val body: @Composable () -> Unit = {
                        Column {
                            Text(item.label, style = MaterialTheme.typography.titleSmall, color = if (item.enabled) Color.Unspecified else Muted)
                            if (item.detail.isNotEmpty()) Text(item.detail, style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                    }
                    if (item.enabled) {
                        FocusRow(onClick = item.onClick, modifier = if (i == firstEnabled) Modifier.focusRequester(first) else Modifier, content = body)
                    } else {
                        Box(Modifier.fillMaxWidth().padding(8.dp)) { body() }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warning, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

/**
 * Saves a clipping's note as a draft: nothing is written until Save (A on the focused Save, so A A highlights without a
 * note; ↑ reaches the note field). Cancel or B drops the draft; drafts survive recreation and the X swap ([draftKey]).
 * [save] and [remove] return null once user.json holds the change, else why not: the editor stays open with the draft and
 * says so. [onDone] follows a successful save (false) or removal (true); Remove asks twice. A note over [MAX_CLIP_NOTE]
 * characters is never cut: the count turns red and Save waits until it is shortened. [shortened]: only the beginning of
 * the passage ([MAX_CLIP_TEXT] characters) is or will be kept, and the editor says so.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ClipEditor(
    draftKey: String,
    heading: String,
    source: String,
    excerpt: String,
    initialNote: String,
    existing: Boolean,
    save: suspend (String) -> String?,
    remove: suspend () -> String?,
    onClose: () -> Unit,
    onDone: (removed: Boolean) -> Unit,
    shortened: Boolean = false
) {
    var note by rememberSaveable(draftKey) { mutableStateOf(initialNote) }
    var error by rememberSaveable(draftKey) { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable(draftKey) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun change(removing: Boolean, op: suspend () -> String?) {
        saving = true
        scope.launch {
            val failure = op()
            saving = false
            error = failure
            if (failure == null) onDone(removing)
        }
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), color = Slate, modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(0.94f)) {
            // A then A saves; with an over-long note Save is disabled, so the focus starts on Cancel instead
            val saveFocus = remember { FocusRequester() }
            val noteLength = note.trim().length
            val tooLong = noteLength > MAX_CLIP_NOTE
            var focusedAny by remember { mutableStateOf(false) }
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                repeat(25) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    if (focusedAny) return@LaunchedEffect
                    runCatching { saveFocus.requestFocus() }
                    delay(40)
                }
            }
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()).onFocusChanged { if (it.hasFocus) focusedAny = true },
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(heading.uppercase(), style = kicker())
                if (source.isNotEmpty()) Text(source, style = MaterialTheme.typography.labelMedium, color = Muted)
                if (excerpt.isNotEmpty()) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Amber))
                        Text(
                            inlineText(excerpt),
                            style = bodyStyle().copy(fontSize = 16.sp, lineHeight = 25.sp),
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
                if (shortened) {
                    Text(
                        "This passage is very long: only its first ${"%,d".format(MAX_CLIP_TEXT)} characters are kept in the scrapbook.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
                Text("My note", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Down reaches Save, never Cancel/Remove by accident; an over-long note keeps the focus (A to shorten it)
                NoteField(
                    note,
                    { note = it },
                    "Why it caught your eye, what to try (optional)",
                    enabled = !saving,
                    down = if (tooLong) FocusRequester.Cancel else saveFocus
                )
                Text(
                    if (tooLong) {
                        "$noteLength / $MAX_CLIP_NOTE characters — shorten the note by ${noteLength - MAX_CLIP_NOTE} to save it"
                    } else {
                        "$noteLength / $MAX_CLIP_NOTE characters"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tooLong) Warning else Muted
                )
                error?.let {
                    Text("Not saved to the card: $it — try again, or Cancel to discard.", style = MaterialTheme.typography.bodySmall, color = Warning)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val saveInteraction = remember { MutableInteractionSource() }
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val removeInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { change(false) { save(note.trim()) } },
                        enabled = !saving && !tooLong,
                        modifier = (if (tooLong) Modifier else Modifier.focusRequester(saveFocus))
                            .focusGlow(saveInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                        interactionSource = saveInteraction
                    ) { Text(if (saving) "Saving…" else "Save") }
                    OutlinedButton(
                        onClick = onClose,
                        enabled = !saving,
                        modifier = (if (tooLong) Modifier.focusRequester(saveFocus) else Modifier)
                            .focusGlow(cancelInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape),
                        interactionSource = cancelInteraction
                    ) { Text("Cancel") }
                    if (existing) {
                        OutlinedButton(
                            onClick = { if (confirmRemove) change(true, remove) else confirmRemove = true },
                            enabled = !saving,
                            modifier = Modifier.focusGlow(removeInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, Warning),
                            interactionSource = removeInteraction
                        ) { Text(if (confirmRemove) "Press again to remove" else "Remove", color = Warning) }
                    }
                }
            }
        }
    }
}

private fun clipSource(issueTitle: String, articleTitle: String): String = listOf(issueTitle, articleTitle).filter { it.isNotEmpty() }.joinToString(" · ")

/** Runs a durable user.json edit; null once it is on the card, else the reason it is not. */
private suspend fun attempt(op: suspend () -> Unit): String? = try {
    op()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (e: Exception) {
    e.message ?: e.javaClass.simpleName
}

// ---------------------------------------------------------------- scrapbook

/**
 * The user's clippings, newest first, from the copies kept in user.json: readable whether or not the issue is still on the
 * card. A opens an entry's options — its article and passage, its games' library pages, the note, removal.
 */
@Composable
private fun ScrapbookView(
    shelf: MagazineShelf,
    clippings: List<MagazineClipping>,
    user: UserStore,
    target: String?,
    onFocused: (String) -> Unit,
    onOpen: (MagazineClipping) -> Unit,
    onGame: (String) -> Unit
) {
    val context = LocalContext.current
    val focus = rememberListFocus(onFocused)
    val sorted = remember(clippings) { clippings.sortedByDescending { it.created } }
    val keys = remember(sorted) {
        buildList {
            add("masthead")
            if (sorted.isEmpty()) add("empty") else sorted.forEach { add("clip:${it.id}") }
        }
    }
    focus.firstFocusable = 1
    FocusOnEntry(focus, keys, keys.drop(1), target)
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var actionError by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val narrow = maxWidth < NarrowWidth
            LazyColumn(
                Modifier.fillMaxSize(),
                state = focus.state,
                contentPadding = PaddingValues(horizontal = if (narrow) 10.dp else 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "masthead") { ScrapbookMasthead(sorted.size, Modifier.measure().padding(horizontal = 14.dp)) }
                if (sorted.isEmpty()) {
                    item(key = "empty") {
                        Entry(focus, "empty", keys.indexOf("empty"), onClick = null, modifier = Modifier.measure()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Nothing clipped yet", style = headline(MaterialTheme.typography.headlineSmall))
                                Text(
                                    "In an article, press Y (or tap Highlight) to choose a paragraph and keep it with a note, or Select " +
                                        "to clip the whole recommendation. Clippings are kept on this device with your other Sortie data, " +
                                        "even after an issue leaves the card.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
                itemsIndexed(sorted, key = { _, c -> "clip:${c.id}" }) { _, c ->
                    val k = "clip:${c.id}"
                    Entry(focus, k, keys.indexOf(k), onClick = {
                        actionsFor = c.id
                        confirmRemove = false
                        actionError = null
                    }, modifier = Modifier.measure()) {
                        ClippingCard(c, c.source(shelf))
                    }
                }
            }
        }
        HintBar("A" to "options", "B" to "back")
    }
    val chosen = sorted.firstOrNull { it.id == actionsFor }
    if (actionsFor != null && chosen == null) SideEffect { actionsFor = null } // removed meanwhile (the other screen)
    if (chosen != null) {
        val items = buildList {
            add(
                when (chosen.source(shelf)) {
                    ClipSource.AVAILABLE -> MenuItem(
                        if (chosen.isArticle) "Open the article" else "Open at this passage",
                        clipSource(chosen.issueTitle, chosen.articleTitle)
                    ) {
                        actionsFor = null
                        onOpen(chosen)
                    }
                    ClipSource.ARTICLE_MISSING -> MenuItem("Open the issue", "This article is no longer in ${chosen.issueTitle.ifEmpty { "the issue" }}") {
                        actionsFor = null
                        onOpen(chosen)
                    }
                    ClipSource.ISSUE_MISSING -> MenuItem(
                        "Open the article",
                        "${chosen.issueTitle.ifEmpty { "The issue" }} is no longer on the card",
                        enabled = false
                    ) {}
                }
            )
            when (chosen.gameIds.size) {
                0 -> Unit
                1 -> add(
                    MenuItem("Library page", chosen.gameTitle.ifEmpty { gameName(chosen.gameIds[0]) }) {
                        actionsFor = null
                        onGame(chosen.gameIds[0])
                    }
                )
                else -> add(
                    MenuItem("Look up ${chosen.gameIds.size} games", "Sortie's page for each game the article names") {
                        actionsFor = null
                        picking = chosen.id
                    }
                )
            }
            add(
                MenuItem(if (chosen.note.isEmpty()) "Add a note" else "Edit the note") {
                    actionsFor = null
                    editing = chosen.id
                }
            )
            add(
                MenuItem(
                    if (confirmRemove) "Press again to remove" else "Remove from the scrapbook",
                    if (confirmRemove) "The note goes with it" else "",
                    enabled = !removing
                ) {
                    if (!confirmRemove) {
                        confirmRemove = true
                    } else {
                        removing = true
                        // the neighbour takes the focus once the list no longer holds this entry
                        val i = sorted.indexOf(chosen)
                        (sorted.getOrNull(i + 1) ?: sorted.getOrNull(i - 1))?.let { onFocused("clip:${it.id}") }
                        scope.launch {
                            val failure = attempt { user.removeClipping(chosen.id) }
                            removing = false
                            actionError = failure
                            if (failure == null) {
                                actionsFor = null
                                confirmRemove = false
                                Toast.makeText(context, "Clipping removed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
        }
        MagazineMenu(
            title = if (chosen.isArticle) chosen.articleTitle else "Highlight",
            subtitle = if (chosen.isArticle) chosen.issueTitle else clipSource(chosen.issueTitle, chosen.articleTitle),
            items = items,
            error = actionError?.let { "Not removed from the card: $it" },
            onDismiss = {
                if (!removing) {
                    actionsFor = null
                    confirmRemove = false
                    actionError = null
                }
            }
        )
    }
    val edited = sorted.firstOrNull { it.id == editing }
    if (editing != null && edited == null) SideEffect { editing = null }
    if (edited != null) {
        ClipEditor(
            draftKey = edited.id,
            heading = if (edited.isArticle) "Clipped recommendation" else "Your highlight",
            source = clipSource(edited.issueTitle, edited.articleTitle),
            excerpt = if (edited.isArticle) edited.deck.ifEmpty { edited.text } else edited.text,
            shortened = edited.shortened && (!edited.isArticle || edited.deck.isEmpty()),
            initialNote = edited.note,
            existing = true,
            save = { note -> attempt { user.saveClipping(edited.copy(note = note, updated = System.currentTimeMillis())) } },
            remove = { attempt { user.removeClipping(edited.id) } },
            onClose = { editing = null },
            onDone = { removed ->
                editing = null
                Toast.makeText(context, if (removed) "Clipping removed" else "Note saved", Toast.LENGTH_SHORT).show()
            }
        )
    }
    sorted.firstOrNull { it.id == picking }?.let { c ->
        GamePicker(c.gameIds, onPick = {
            picking = null
            onGame(it)
        }, onDismiss = { picking = null })
    }
}

@Composable
private fun ScrapbookMasthead(count: Int, modifier: Modifier) {
    Column(modifier.padding(bottom = 6.dp)) {
        Text("SORTIE · MAGAZINES", style = kicker())
        Text("Scrapbook", style = headline(MaterialTheme.typography.displaySmall))
        Text(
            when (count) {
                0 -> "Your highlights and clipped recommendations"
                1 -> "1 clipping, kept on this device"
                else -> "$count clippings, kept on this device"
            },
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        Box(Modifier.padding(top = 10.dp).width(56.dp).height(3.dp).background(Amber))
    }
}

@Composable
private fun ClippingCard(c: MagazineClipping, source: ClipSource) {
    Column {
        val kind = if (c.isArticle) "CLIPPED RECOMMENDATION" else "HIGHLIGHT"
        Text(
            listOf(kind, c.issueTitle.uppercase()).filter { it.isNotEmpty() }.joinToString(" · "),
            style = kicker(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (c.isArticle) {
            val kick = listOf(c.system, c.gameTitle).filter { it.isNotEmpty() }.joinToString(" · ")
            if (kick.isNotEmpty()) Text(kick, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 4.dp))
            Text(c.articleTitle, style = headline(MaterialTheme.typography.titleLarge), modifier = Modifier.padding(top = 2.dp))
            val teaser = c.deck.ifEmpty { c.text }
            if (teaser.isNotEmpty()) {
                Text(
                    inlineText(teaser),
                    style = deckStyle(),
                    color = Muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Amber))
                Text(
                    inlineText(c.text),
                    style = bodyStyle().copy(fontSize = 16.sp, lineHeight = 25.sp),
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            val from = if (c.articleTitle.isEmpty()) "" else "from “${c.articleTitle}”"
            val cut = if (c.shortened) "first ${"%,d".format(MAX_CLIP_TEXT)} characters of a longer passage" else ""
            val line = listOf(from, cut).filter { it.isNotEmpty() }.joinToString(" · ")
            if (line.isNotEmpty()) {
                Text(line, style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (c.note.isNotEmpty()) {
            Text(
                "✎  ${c.note}",
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = Amber,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        val games = c.gameIds.size
        val meta = listOfNotNull(
            c.created.takeIf { it > 0 }?.let { "Saved " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE) },
            when (games) {
                0 -> null
                1 -> "1 game"
                else -> "$games games"
            }
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(top = 8.dp))
        when (source) {
            ClipSource.ISSUE_MISSING -> PackNote("This issue is no longer on the card — your copy is kept here.")
            ClipSource.ARTICLE_MISSING -> PackNote("This article is no longer in its issue — your copy is kept here.")
            ClipSource.AVAILABLE -> Unit
        }
    }
}

// ---------------------------------------------------------------- type

@Composable
private fun PackNote(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Warning, modifier = Modifier.padding(top = 6.dp))
}

private fun Modifier.measure(): Modifier = widthIn(max = Measure).fillMaxWidth()

@Composable
private fun kicker(): TextStyle = MaterialTheme.typography.labelMedium.copy(color = Amber, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)

private fun headline(base: TextStyle): TextStyle = base.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)

@Composable
private fun deckStyle(): TextStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, lineHeight = 26.sp)

@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 29.sp)

@Composable
private fun captionStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic, color = Muted)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

private fun issueDate(issue: MagazineIssue): String = issue.publishedDate?.format(DATE) ?: issue.published

/** "Armored Core 3 (USA)" for "ps2/Armored Core 3 (USA).chd". */
private fun gameName(id: String): String = id.substringAfter('/').substringBeforeLast('.').ifEmpty { id }
