package io.github.esko997.sortie.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.reference.PickedDocument
import io.github.esko997.sortie.data.reference.ReferenceCard
import io.github.esko997.sortie.data.reference.ReferenceCards
import io.github.esko997.sortie.data.reference.ReferenceFiles
import io.github.esko997.sortie.data.reference.ReferenceLimits
import io.github.esko997.sortie.data.reference.ReferenceStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Prefix of the editor key for a card that does not exist yet; the rest is the id it will be saved under. */
private const val NEW_CARD = "new:"

private val ErrorRed: Color
    @Composable get() = MaterialTheme.colorScheme.error

/**
 * [gameId]'s reference cards on the main screen: the list (with the guide's controls and the user's mapping notes as separate
 * read-only reference), a card viewer (L1/R1 or left/right at rest step through cards; images pan and zoom) and an editor.
 * Every change returns only once user.json holds it; a failure leaves the draft open and says why. B closes the editor
 * (discarding the draft) or the viewer before it leaves the screen.
 */
@Composable
fun ReferenceCardsScreen(graph: SortieApp.Graph, gameId: String) {
    val context = LocalContext.current
    val store = remember(graph) { ReferenceStore(graph.user, graph.sortieDir) }
    val cards = rememberReferenceCards(graph, gameId)
    val game by produceState<GameEntity?>(null, gameId) { value = graph.db.games().byId(gameId) }
    var viewing by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    // the card the list returns its focus to
    var lastCard by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var noticeIsError by rememberSaveable(gameId) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun tell(message: String?, error: Boolean) {
        notice = message
        noticeIsError = error
    }

    fun runImport(op: suspend () -> ReferenceCard) {
        busy = true
        tell(null, false)
        scope.launch {
            runCatching { op() }
                .onSuccess {
                    lastCard = it.id
                    viewing = it.id
                    tell("Imported “${it.title}”.", false)
                }
                .onFailure { tell(it.message ?: "Import failed.", true) }
            busy = false
        }
    }
    val app = context.applicationContext
    val picked: suspend (Uri) -> PickedDocument = { uri -> withContext(Dispatchers.IO) { PickedDocument.from(app, uri) } }
    val pickMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport { store.importMarkdown(gameId, picked(uri)) }
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) runImport { store.importImage(gameId, picked(uri)) }
    }
    val pickerFailed: (Throwable) -> Unit = { tell("No document picker is available: ${it.message ?: it.javaClass.simpleName}", true) }

    val editKey = editing
    val viewed = viewing?.let { id -> cards.firstOrNull { it.id == id } }
    when {
        editKey != null -> {
            val original = if (editKey.startsWith(NEW_CARD)) null else cards.firstOrNull { it.id == editKey }
            ReferenceCardEditor(
                key = editKey,
                original = original,
                sortieDir = graph.sortieDir,
                save = { title, body ->
                    val card = original?.copy(title = title, body = body)
                        ?: ReferenceCard(id = editKey.removePrefix(NEW_CARD), title = title, body = body)
                    runCatching { store.save(gameId, card) }.fold(
                        onSuccess = {
                            lastCard = it.id
                            viewing = it.id
                            editing = null
                            tell("Saved “${it.title}”.", false)
                            null
                        },
                        onFailure = { it.message ?: "Not saved." }
                    )
                },
                onClose = { editing = null }
            )
        }
        viewed != null -> ReferenceViewer(
            card = viewed,
            cards = cards,
            sortieDir = graph.sortieDir,
            notice = notice,
            noticeIsError = noticeIsError,
            onShow = {
                lastCard = it.id
                viewing = it.id
                tell(null, false)
            },
            onEdit = { editing = viewed.id },
            onDelete = {
                val next = ReferenceCards.neighbour(cards, viewed.id, 1)?.takeIf { it.id != viewed.id }
                runCatching { store.delete(gameId, viewed.id) }.fold(
                    onSuccess = {
                        viewing = next?.id
                        lastCard = next?.id
                        tell("Deleted “${viewed.title}”.", false)
                        true
                    },
                    onFailure = {
                        tell(it.message ?: "Not deleted.", true)
                        false
                    }
                )
            },
            onClose = { viewing = null }
        )
        else -> ReferenceList(
            title = game?.let(::displayTitle) ?: gameId.substringAfterLast('/'),
            cards = cards,
            sortieDir = graph.sortieDir,
            guideControls = game?.controls?.takeIf { it.isNotBlank() },
            focusId = lastCard,
            busy = busy,
            notice = notice,
            noticeIsError = noticeIsError,
            onOpen = {
                lastCard = it.id
                viewing = it.id
                tell(null, false)
            },
            onNew = {
                tell(null, false)
                editing = NEW_CARD + ReferenceStore.newId()
            },
            onImportMarkdown = { runCatching { pickMarkdown.launch(MARKDOWN_TYPES) }.onFailure(pickerFailed) },
            onImportImage = { runCatching { pickImage.launch(IMAGE_TYPES) }.onFailure(pickerFailed) }
        )
    }
}

/** Markdown has no reliable MIME type on Android (often octet-stream); a binary pick is refused after reading. */
private val MARKDOWN_TYPES = arrayOf("text/*", "application/octet-stream")
private val IMAGE_TYPES = arrayOf("image/png", "image/jpeg", "image/webp", "image/gif")

/** Keyboard input mode, then [requester] until it holds the focus (a touch-opened window starts in touch mode). */
@Composable
private fun InitialFocus(key: Any?, requester: FocusRequester) {
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(key) {
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
}

@Composable
private fun Notice(text: String?, error: Boolean, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = if (error) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceList(
    title: String,
    cards: List<ReferenceCard>,
    sortieDir: File,
    guideControls: String?,
    focusId: String?,
    busy: Boolean,
    notice: String?,
    noticeIsError: Boolean,
    onOpen: (ReferenceCard) -> Unit,
    onNew: () -> Unit,
    onImportMarkdown: () -> Unit,
    onImportImage: () -> Unit
) {
    val initial = remember { FocusRequester() }
    val target = cards.firstOrNull { it.id == focusId } ?: cards.firstOrNull()
    // the row the focus returns to must be composed for the focus request to land
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = target?.let { cards.indexOf(it) }?.coerceAtLeast(0) ?: 0)
    InitialFocus(Unit, initial)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, top = 16.dp, end = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (cards.size) {
                    0 -> "Reference cards"
                    1 -> "Reference cards · 1 card"
                    else -> "Reference cards · ${cards.size} cards"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("New card", if (target == null) Modifier.focusRequester(initial) else Modifier, enabled = !busy, onClick = onNew)
                GlowButton("Import Markdown", enabled = !busy, onClick = onImportMarkdown)
                GlowButton("Import image", enabled = !busy, onClick = onImportImage)
            }
            Notice(if (busy) "Importing…" else notice, !busy && noticeIsError, Modifier.padding(top = 8.dp))
        }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (cards.isEmpty()) {
                item {
                    Column(Modifier.padding(8.dp).widthIn(max = EDITOR_MEASURE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("No cards yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Write one, or import a Markdown file or an image — a map, move list or chart — from this device. " +
                                "On dual-screen devices, open them from Now playing while the game stays on the other screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(cards, key = { it.id }) { card ->
                FocusRow(onClick = { onOpen(card) }, modifier = if (card.id == target?.id) Modifier.focusRequester(initial) else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReferenceThumbnail(card, sortieDir)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(
                                card.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                cardLine(card),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            if (guideControls != null) {
                item { ReadOnlyReference(guideControls) }
            }
        }
        HintBar("A" to "open", "B" to "back")
    }
}

/** "Image · from map.png · edited 2 days ago" / the first line of the text. */
private fun cardLine(card: ReferenceCard): String {
    val what = if (card.image != null) {
        "Image" + card.source.takeIf { it.isNotBlank() }?.let { " · from $it" }.orEmpty()
    } else {
        card.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.take(120) ?: "Empty"
    }
    val edited = card.updated.takeIf { it > 0 }?.let {
        "edited " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
    }
    return listOfNotNull(what, edited).joinToString(" · ")
}

/** The guide's hand-written controls, shown beside the cards but never edited or copied into them; focusable so the D-pad can scroll down to it. */
@Composable
private fun ReadOnlyReference(guideControls: String) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .padding(top = 16.dp)
            .focusGlow(focused, shape)
            .fillMaxWidth()
            .clip(shape)
            .background(PanelColour.copy(alpha = 0.6f))
            .focusable(interactionSource = interaction)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Not cards: shown here for reference only", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Guide controls", style = MaterialTheme.typography.titleSmall, color = Accent)
        Text(guideControls, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * One card full-size. The content takes the controller first; up from its top reaches the buttons. Delete asks once more.
 * [onDelete] returns whether the card is gone.
 */
@Composable
private fun ReferenceViewer(
    card: ReferenceCard,
    cards: List<ReferenceCard>,
    sortieDir: File,
    notice: String?,
    noticeIsError: Boolean,
    onShow: (ReferenceCard) -> Unit,
    onEdit: () -> Unit,
    onDelete: suspend () -> Boolean,
    onClose: () -> Unit
) {
    var confirming by rememberSaveable(card.id) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val body = remember { FocusRequester() }
    val keep = remember { FocusRequester() }
    InitialFocus(card.id, body)
    LaunchedEffect(confirming) { if (confirming) runCatching { keep.requestFocus() } }
    BackHandler(enabled = !deleting) { if (confirming) confirming = false else onClose() }
    val step: (Int) -> Unit = { d -> ReferenceCards.neighbour(cards, card.id, d)?.takeIf { it.id != card.id }?.let(onShow) }
    val index = cards.indexOfFirst { it.id == card.id }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // L1/R1 page through the cards from anywhere in the viewer; their release must not reach the folder paging
            if (e.key != Key.ButtonL1 && e.key != Key.ButtonR1) return@onPreviewKeyEvent false
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 && !deleting) step(if (e.key == Key.ButtonL1) -1 else 1)
            true
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GlowButton("‹", enabled = cards.size > 1 && !deleting) { step(-1) }
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${index + 1} of ${cards.size}" + card.source.takeIf { it.isNotBlank() }?.let { " · from $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            GlowButton("›", enabled = cards.size > 1 && !deleting) { step(1) }
            if (confirming) {
                GlowButton(
                    if (deleting) "Deleting…" else "Delete card",
                    enabled = !deleting,
                    primary = true,
                    colour = ErrorRed
                ) {
                    deleting = true
                    scope.launch {
                        val gone = onDelete()
                        deleting = false
                        confirming = false
                        if (!gone) runCatching { body.requestFocus() }
                    }
                }
                GlowButton("Keep", Modifier.focusRequester(keep), enabled = !deleting) { confirming = false }
            } else {
                GlowButton("Edit", onClick = onEdit)
                GlowButton("Delete") { confirming = true }
            }
        }
        Notice(notice, noticeIsError, Modifier.padding(start = 24.dp, top = 4.dp, end = 24.dp))
        ReferenceCardBody(
            card,
            sortieDir,
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            controller = true,
            focusRequester = body,
            onStep = step
        )
        HintBar(
            *buildList {
                add("L1/R1" to "previous / next")
                if (card.image != null) {
                    add("A" to "zoom")
                    add("L2/R2" to "zoom out / in")
                    add("D-pad" to "pan")
                } else {
                    add("D-pad" to "scroll")
                }
                add("B" to "list")
            }.toTypedArray()
        )
    }
}

/**
 * New or existing card as a draft: nothing is written until Save; Cancel or B drops the draft. The draft survives activity
 * recreation. [save] returns null once user.json holds the card, else the reason it does not (the editor then stays open).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ReferenceCardEditor(
    key: String,
    original: ReferenceCard?,
    sortieDir: File,
    save: suspend (title: String, body: String) -> String?,
    onClose: () -> Unit
) {
    var title by rememberSaveable(key) { mutableStateOf(original?.title.orEmpty()) }
    var body by rememberSaveable(key) { mutableStateOf(original?.body.orEmpty()) }
    var error by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    InitialFocus(key, first)
    BackHandler { if (!saving) onClose() }
    val isImage = original?.image != null
    val changed = title != original?.title.orEmpty() || body != original?.body.orEmpty()
    val tooLong = title.length > ReferenceLimits.MAX_TITLE || body.length > ReferenceLimits.MAX_BODY
    val hasContent = title.isNotBlank() || body.isNotBlank() || isImage
    val canSave = !saving && !tooLong && hasContent && (changed || original == null || error != null)
    Column(Modifier.fillMaxSize()) {
        // a form, not a page: on the wide screen it keeps a readable width instead of stretching the text across it
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp)
                .wrapContentWidth(Alignment.Start).widthIn(max = EDITOR_MEASURE),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (original == null) "New card" else "Edit card", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Label("Title")
            ReferenceTextField(
                title,
                { title = it },
                "Boss order, world map, combo list…",
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.focusRequester(first)
            )
            if (isImage) {
                val file = ReferenceFiles.resolve(sortieDir, original?.image)
                Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(PanelColour), contentAlignment = Alignment.Center) {
                    if (file != null && file.isFile) CoverImage(file.path, Modifier.fillMaxSize(), ContentScale.Fit) else Text("Image missing")
                }
                Label("Notes under the image (Markdown)")
            } else {
                Label("Text (Markdown: # heading, - bullet, **bold**, *italic*)")
            }
            // Down reaches Save, never Cancel by accident: an over-long draft keeps the focus (A to shorten it); with Save
            // otherwise disabled the draft is empty or unchanged, so Cancel loses nothing
            ReferenceTextField(
                body,
                { body = it },
                if (isImage) "What to look for on this image" else "Write the reference here",
                enabled = !saving,
                up = first,
                down = when {
                    canSave -> saveFocus
                    tooLong -> FocusRequester.Cancel
                    else -> cancelFocus
                }
            )
            Text(
                "${body.length} / ${ReferenceLimits.MAX_BODY} characters" +
                    if (title.length > ReferenceLimits.MAX_TITLE) " · title over ${ReferenceLimits.MAX_TITLE}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (tooLong) ErrorRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let { Text("Not saved: $it — Save retries; Cancel discards this draft.", style = MaterialTheme.typography.bodySmall, color = ErrorRed) }
            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton(
                    if (saving) "Saving…" else "Save",
                    Modifier.focusRequester(saveFocus),
                    enabled = canSave,
                    primary = true
                ) {
                    saving = true
                    scope.launch {
                        val failure = save(title, body)
                        saving = false
                        error = failure
                    }
                }
                GlowButton("Cancel", Modifier.focusRequester(cancelFocus), enabled = !saving, onClick = onClose)
            }
        }
        HintBar("↕" to "move", "A" to "select / type", "B" to "cancel")
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The card editor's widest column: the title, the text and the buttons stay a comfortable reach on the wide screen. */
private val EDITOR_MEASURE = 760.dp

/**
 * A text field a controller can leave: D-pad up/down from a physical pad moves the focus to [up]/[down] (as for [NoteField]),
 * A raises the keyboard (the editing keys of the soft keyboard and touch keep their usual meaning). The body field is taller
 * than the annotation notes'.
 */
@Composable
private fun ReferenceTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    up: FocusRequester = FocusRequester.Default,
    down: FocusRequester = FocusRequester.Default
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        placeholder = { Text(placeholder) },
        minLines = if (singleLine) 1 else 8,
        maxLines = if (singleLine) 1 else 16,
        modifier = modifier.fillMaxWidth().padFocusExits(up, down).onPreviewKeyEvent { e ->
            when {
                !isPhysicalPad(e) -> false
                e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                    if (e.type == KeyEventType.KeyDown) focus.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                    true
                }
                e.key == Key.ButtonA || e.key == Key.DirectionCenter -> {
                    if (e.type == KeyEventType.KeyUp) keyboard?.show()
                    true
                }
                else -> false
            }
        }
    )
}
