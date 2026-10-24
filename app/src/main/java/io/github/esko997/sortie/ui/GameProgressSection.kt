package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.user.GameProgress
import io.github.esko997.sortie.data.user.Shelf
import io.github.esko997.sortie.data.user.VERDICT_MAX_CHARS
import io.github.esko997.sortie.data.user.withShelf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val ShelfDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

private fun Shelf.colour(): Color = when (this) {
    Shelf.FINISHED -> Color(0xFF166534)
    Shelf.SHELVED -> Color(0xFF475569)
}

private fun Shelf.meaning(): String = when (this) {
    Shelf.FINISHED -> "Played through, or as far as you mean to."
    Shelf.SHELVED -> "Put aside without finishing."
}

/**
 * Game [gameId]'s personal shelf on its detail page: Finished or Shelved with the date and the optional verdict, and a button
 * that opens the shelf editor. Self-contained: reads and writes `user.gameProgress` through [graph]'s user store. The editor
 * is a dialog, so the detail screen's hotkeys (Y, R2) do not reach it while the verdict is typed.
 */
@Composable
fun GameProgressSection(graph: SortieApp.Graph, gameId: String, modifier: Modifier = Modifier, accent: Color = Amber) {
    val progress by remember(gameId) { graph.user.data.map { it.gameProgress[gameId] }.distinctUntilChanged() }
        .collectAsState(graph.user.data.value.gameProgress[gameId])
    var editing by rememberSaveable(gameId) { mutableStateOf(false) }
    Column(modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        progress?.let { ShelfSummary(it) }
        val interaction = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = { editing = true },
            modifier = Modifier.focusGlow(interaction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
            interactionSource = interaction
        ) { Text(if (progress == null) "Mark finished or shelved" else "Change shelf or verdict", maxLines = 1) }
    }
    if (editing) {
        ShelfEditor(
            gameId = gameId,
            current = progress,
            accent = accent,
            save = { shelf, verdict -> saveShelf(graph, gameId, shelf, verdict) },
            onClose = { editing = false }
        )
    }
}

/** Durable: returns null once user.json holds the change, else why it does not (nothing was published). */
private suspend fun saveShelf(graph: SortieApp.Graph, gameId: String, shelf: Shelf?, verdict: String): String? = try {
    val today = LocalDate.now()
    graph.user.commit { it.copy(gameProgress = it.gameProgress.withShelf(gameId, shelf, verdict, today)) }
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    failure.message ?: "Unable to write user.json"
}

@Composable
private fun ShelfSummary(progress: GameProgress) {
    val shelf = progress.shelf
    if (shelf == null) {
        Text(
            "Shelf \"${progress.status}\" comes from a newer Sortie; this version shows it on no shelf.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                shelf.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE6E8EB),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(shelf.colour()).padding(horizontal = 8.dp, vertical = 2.dp)
            )
            progress.sinceDate?.let {
                Text(
                    "  since ${it.format(ShelfDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (progress.verdict.isNotBlank()) {
            Text("“${progress.verdict}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        }
    }
}

/**
 * Draft editor for the shelf and verdict: nothing is written until Save; Cancel or B drops the draft (a tap outside does not,
 * so a typed verdict is not lost by accident). Choosing Neither and saving takes the game off its shelf and deletes the verdict.
 * A failed write keeps the dialog open with the draft and says why. Drafts survive activity recreation.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ShelfEditor(gameId: String, current: GameProgress?, accent: Color, save: suspend (Shelf?, String) -> String?, onClose: () -> Unit) {
    var shelfKey by rememberSaveable(gameId) { mutableStateOf(current?.shelf?.key.orEmpty()) }
    var verdict by rememberSaveable(gameId) { mutableStateOf(current?.verdict.orEmpty()) }
    var error by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val draft = Shelf.of(shelfKey)
    val saved = current?.shelf?.let { it to current.verdict }
    val target = draft?.let { it to verdict.trim() }
    // an entry with a status this build does not know is also "changed" by Neither: saving removes it
    val changed = target != saved || (current != null && saved == null)
    val tooLong = draft != null && verdict.trim().length > VERDICT_MAX_CHARS
    val removing = draft == null && current != null
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { if (!saving) onClose() }, properties = DialogProperties(dismissOnClickOutside = false)) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Slate)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Shelf", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (Shelf.entries + null).forEachIndexed { i, option ->
                    val interaction = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = draft == option,
                        enabled = !saving,
                        onClick = { shelfKey = option?.key.orEmpty() },
                        label = { Text(option?.label ?: "Neither") },
                        modifier = (if (i == 0) Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) focusedOnce = true } else Modifier)
                            .focusGlow(interaction.collectIsFocusedAsState().value, FilterChipDefaults.shape, accent),
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.25f), selectedLabelColor = accent),
                        interactionSource = interaction
                    )
                }
            }
            // staying on the saved shelf keeps its date (see withShelf); any other shelf is dated on save
            val keptSince = current?.takeIf { draft != null && it.shelf == draft }?.sinceDate
            Text(
                when {
                    draft != null && keptSince != null -> "${draft.meaning()} On this shelf since ${keptSince.format(ShelfDate)}."
                    draft != null -> "${draft.meaning()} Dated today, ${LocalDate.now().format(ShelfDate)}."
                    removing -> "Saving takes the game off its shelf and deletes its verdict."
                    else -> "Not on a shelf."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Verdict (optional)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NoteField(verdict, { verdict = it }, "Your verdict in a line or two", enabled = !saving && draft != null)
            Text(
                if (tooLong) {
                    "${verdict.trim().length} / $VERDICT_MAX_CHARS characters: shorten it to save."
                } else {
                    "${verdict.trim().length} / $VERDICT_MAX_CHARS"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (tooLong) Color(0xFFF87171) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            error?.let {
                Text(
                    "Not saved to the card: $it — Save retries; Cancel discards this draft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF87171)
                )
            }
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val saveInteraction = remember { MutableInteractionSource() }
                val cancelInteraction = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        saving = true
                        scope.launch {
                            val failure = save(draft, verdict)
                            saving = false
                            error = failure
                            if (failure == null) onClose()
                        }
                    },
                    // a failed write left the saved shelf unchanged; keep the draft retryable
                    enabled = !saving && !tooLong && (changed || error != null),
                    modifier = Modifier.focusGlow(saveInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape, accent),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                    interactionSource = saveInteraction
                ) { Text(if (saving) "Saving…" else if (removing) "Take off shelf" else "Save") }
                OutlinedButton(
                    onClick = onClose,
                    enabled = !saving,
                    modifier = Modifier.focusGlow(cancelInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                    interactionSource = cancelInteraction
                ) { Text("Cancel") }
            }
            HintBar("↕" to "move", "A" to "select / type", "B" to "cancel")
        }
        // see ContextSheet: the dialog window gains focus a few frames late, and touch mode refuses focus moves
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            repeat(20) {
                if (focusedOnce) return@LaunchedEffect
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { first.requestFocus() }
                delay(40)
            }
        }
    }
}
