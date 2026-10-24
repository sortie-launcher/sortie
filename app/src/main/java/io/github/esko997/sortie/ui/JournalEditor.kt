package io.github.esko997.sortie.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.likeEscape
import io.github.esko997.sortie.data.journal.JournalEntry
import io.github.esko997.sortie.data.journal.JournalStore
import io.github.esko997.sortie.data.journal.MAX_ATTACHMENTS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val JOURNAL_JSON = Json { ignoreUnknownKeys = true }

/** Keeps an entry draft (or "no editor": null) across activity recreation and the X screen swap. */
internal val NullableEntrySaver = Saver<JournalEntry?, String>(
    save = { e -> e?.let { JOURNAL_JSON.encodeToString(JournalEntry.serializer(), it) } },
    restore = { runCatching { JOURNAL_JSON.decodeFromString(JournalEntry.serializer(), it) }.getOrNull() }
)

private val EntrySaver = Saver<JournalEntry, String>(
    save = { JOURNAL_JSON.encodeToString(JournalEntry.serializer(), it) },
    restore = { JOURNAL_JSON.decodeFromString(JournalEntry.serializer(), it) }
)

private val PathsSaver = Saver<List<String>, String>(save = { it.joinToString("\n") }, restore = { s -> s.split('\n').filter { it.isNotEmpty() } })

/** A library item as the journal names it; [indexed] = a game this device's index knows (so its detail page opens). */
internal data class ItemTitle(val title: String, val indexed: Boolean)

/**
 * Edits [seed] as a draft: nothing is written until Save; Cancel (or B) drops the draft and deletes the screenshots imported
 * during it. [saved] is the entry as user.json holds it now (null = a new entry). Screenshots come only from the Android document
 * picker and are copied onto the card immediately (so the draft can show them), but an entry refers to them only once saved.
 * A failed save or delete keeps the editor open with the draft and says why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun JournalEditor(
    seed: JournalEntry,
    saved: JournalEntry?,
    store: JournalStore,
    graph: SortieApp.Graph,
    /** Items played in the week being viewed, offered first when choosing a game. */
    weekItems: List<String>,
    zone: ZoneId,
    /** Called with the entry as saved, or null after Cancel/B or Delete. */
    onClose: (saved: JournalEntry?) -> Unit
) {
    // an unreadable date (hand-edited user.json) starts as today, so Save files the note in a real week
    var draft by rememberSaveable(seed.id, stateSaver = EntrySaver) {
        mutableStateOf(if (seed.localDate() == null) seed.copy(date = LocalDate.now(zone).toString()) else seed)
    }
    var imported by rememberSaveable(seed.id, stateSaver = PathsSaver) { mutableStateOf<List<String>>(emptyList()) }
    var error by rememberSaveable(seed.id) { mutableStateOf<String?>(null) }
    var importNotes by rememberSaveable(seed.id) { mutableStateOf("") }
    var picking by rememberSaveable(seed.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(seed.id) { mutableStateOf(false) }
    var viewing by rememberSaveable(seed.id) { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isNew = saved == null
    val changed = if (isNew) !draft.isBlank() else draft.copy(updatedAt = 0) != saved!!.copy(updatedAt = 0)
    val date = draft.localDate() ?: LocalDate.now(zone)
    val gameTitle by produceState<ItemTitle?>(null, draft.gameId) { value = draft.gameId?.let { resolveTitles(graph, setOf(it))[it] } }

    val cancel: () -> Unit = {
        store.discardImports(imported)
        onClose(null)
    }
    BackHandler(enabled = !picking && viewing == null) { if (busy == null) cancel() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = "Copying ${uris.size} screenshot${if (uris.size == 1) "" else "s"} to the card…"
        scope.launch {
            val notes = mutableListOf<String>()
            val room = (MAX_ATTACHMENTS - draft.attachments.size).coerceAtLeast(0)
            if (uris.size > room) notes += "${uris.size - room} not attached: an entry holds at most $MAX_ATTACHMENTS screenshots."
            for (uri in uris.take(room)) {
                try {
                    val a = store.media.import(context.contentResolver, uri, draft.id)
                    imported = imported + a.path
                    draft = draft.copy(attachments = draft.attachments + a)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    notes += "Not attached: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            importNotes = notes.joinToString("\n")
            busy = null
        }
    }

    viewing?.let { i ->
        JournalViewer(draft.attachments, i, store.media, onClose = { viewing = null })
        return
    }
    if (picking) {
        GamePicker(
            graph = graph,
            weekItems = weekItems,
            onPick = { id ->
                if (id != draft.gameId) draft = draft.copy(gameId = id, sessionStartedAt = null)
                picking = false
            },
            onClose = { picking = false }
        )
        return
    }

    val first = remember { FocusRequester() }
    val chooseGame = remember { FocusRequester() }
    LaunchedEffect(seed.id) { runCatching { first.requestFocus() } }
    val enabled = busy == null
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isNew) "New note" else "Edit note", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            Label("Day")
            Text(date.format(LONG_DATE), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorButton("Day before", enabled) { draft = draft.copy(date = date.minusDays(1).toString()) }
                EditorButton("Day after", enabled) { draft = draft.copy(date = date.plusDays(1).toString()) }
                EditorButton("Week before", enabled) { draft = draft.copy(date = date.minusWeeks(1).toString()) }
                EditorButton("Week after", enabled) { draft = draft.copy(date = date.plusWeeks(1).toString()) }
                EditorButton("Today", enabled && date != LocalDate.now(zone)) { draft = draft.copy(date = LocalDate.now(zone).toString()) }
            }

            Label("Note")
            // Down goes to the game chooser, not whichever game button (Remove game, Unlink session) sits nearest its centre
            NoteField(
                draft.text,
                { draft = draft.copy(text = it) },
                "What you played, what happened, what to try next",
                Modifier.focusRequester(first),
                enabled = enabled,
                down = chooseGame
            )

            Label("Game")
            Text(
                when {
                    draft.gameId == null -> "Not about a particular game"
                    gameTitle == null -> "…"
                    else -> gameTitle!!.title + if (gameTitle!!.indexed) "" else " (not in this device's library)"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            draft.sessionStartedAt?.let { t ->
                Text(
                    "About the session started ${Instant.ofEpochMilli(t).atZone(zone).format(DAY_TIME)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EditorButton(if (draft.gameId == null) "Choose game…" else "Change game…", enabled, Modifier.focusRequester(chooseGame)) { picking = true }
                if (draft.gameId != null) EditorButton("Remove game", enabled) { draft = draft.copy(gameId = null, sessionStartedAt = null) }
                if (draft.sessionStartedAt != null) EditorButton("Unlink session", enabled) { draft = draft.copy(sessionStartedAt = null) }
            }

            Label("Screenshots (${draft.attachments.size} of $MAX_ATTACHMENTS)")
            if (draft.attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    draft.attachments.forEachIndexed { i, a ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AttachmentThumb(a, store.media, onOpen = { viewing = i })
                            EditorButton("Remove", enabled) { draft = draft.copy(attachments = draft.attachments.filterNot { it.path == a.path }) }
                        }
                    }
                }
            }
            EditorButton("Add screenshots…", enabled && draft.attachments.size < MAX_ATTACHMENTS) {
                runCatching {
                    pick.launch(
                        arrayOf("image/*")
                    )
                }.onFailure { importNotes = "No document picker is available: ${it.message ?: it.javaClass.simpleName}" }
            }
            Text(
                "Picked files are copied into sortie/journal on the card and kept there; nothing is attached automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            busy?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
            if (importNotes.isNotEmpty()) Text(importNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            error?.let {
                Text(
                    "Not saved to the card: $it. Try again, or Cancel to discard this draft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val saveInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        val next = draft.copy(text = draft.text.trimEnd(), updatedAt = System.currentTimeMillis())
                        val keep = next.attachments.mapTo(HashSet()) { it.path }
                        val discard = (saved?.attachments.orEmpty().map { it.path } + imported).filterNot { it in keep }
                        busy = "Saving…"
                        scope.launch {
                            val failure = store.save(next, discard)
                            busy = null
                            error = failure
                            if (failure == null) onClose(next)
                        }
                    },
                    enabled = enabled && !draft.isBlank() && (changed || error != null),
                    modifier = Modifier.focusGlow(saveInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Ink),
                    interactionSource = saveInteraction
                ) { Text("Save") }
                EditorButton("Cancel", enabled, onClick = cancel)
                if (!isNew && !confirmDelete) EditorButton("Delete note…", enabled) { confirmDelete = true }
            }
            if (draft.isBlank()) {
                Text(
                    "Write something, choose a game or add a screenshot to save this note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (confirmDelete && saved != null) {
                val pictures = saved.attachments.size
                Text(
                    "Delete this note" + (if (pictures > 0) " and its $pictures screenshot${if (pictures == 1) "" else "s"}" else "") +
                        "? This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorButton("Keep note", enabled) { confirmDelete = false }
                    EditorButton("Delete note", enabled) {
                        busy = "Deleting…"
                        scope.launch {
                            val failure = store.delete(saved)
                            busy = null
                            error = failure
                            if (failure == null) {
                                store.discardImports(imported)
                                onClose(null)
                            }
                        }
                    }
                }
            }
        }
        HintBar("A" to "choose", "B" to "cancel")
    }
}

/** Picks the game a note is about: this week's games and recently played first, or a title search over the library. */
@Composable
private fun GamePicker(graph: SortieApp.Graph, weekItems: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    BackHandler(onBack = onClose)
    val results by produceState<List<Pair<String, ItemTitle>>?>(null, query) {
        val q = query.trim()
        val ids = if (q.isEmpty()) {
            (weekItems + graph.db.games().recentlyPlayed(30).first().map { it.gameId }).distinct()
        } else {
            delay(250) // typing: query once the text settles
            graph.db.games().titleMatches("%${likeEscape(q)}%", 60).map { it.id }
        }
        val titles = resolveTitles(graph, ids.toSet())
        value = ids.mapNotNull { id -> titles[id]?.let { id to it } }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Choose the game", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                query,
                { query = it },
                singleLine = true,
                placeholder = { Text("Search titles") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(field).onPreviewKeyEvent { e ->
                    if (e.key != Key.DirectionDown || !isPhysicalPad(e)) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && focus.moveFocus(FocusDirection.Down)) keyboard?.hide()
                    true
                }
            )
            Text(
                if (query.isBlank()) "Played this week and recently" else "Matching titles",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            when {
                results == null -> Text("Looking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                results!!.isEmpty() -> Text(
                    if (query.isBlank()) "Nothing played yet. Search for a title above." else "No title matches “${query.trim()}”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> results!!.forEach { (id, t) ->
                    FocusRow(onClick = { onPick(id) }) {
                        Column {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!t.indexed) {
                                Text(
                                    "not in this device's library",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            FocusRow(onClick = onClose) { Text("Back to the note", style = MaterialTheme.typography.titleSmall) }
        }
        HintBar("A" to "choose", "B" to "back to the note")
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EditorButton(text: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.focusGlow(interaction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape),
        interactionSource = interaction
    ) { Text(text, maxLines = 1) }
}
