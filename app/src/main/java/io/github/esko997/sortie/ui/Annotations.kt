package io.github.esko997.sortie.ui

import android.view.InputDevice
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.manifest.BOOKMARK_MAX_CHARS
import io.github.esko997.sortie.data.manifest.ControllerSupport
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.GameBookmark
import io.github.esko997.sortie.ui.SortieButton as Button
import io.github.esko997.sortie.ui.SortieOutlinedButton as OutlinedButton
import kotlinx.coroutines.launch

/** Input sources of a physical controller: their D-pad leaves a text field instead of moving its caret. */
private val PAD_SOURCES = intArrayOf(InputDevice.SOURCE_GAMEPAD, InputDevice.SOURCE_DPAD, InputDevice.SOURCE_JOYSTICK)

/** Whether [e] comes from a physical controller rather than a soft keyboard's own arrow keys. */
internal fun isPhysicalPad(e: KeyEvent): Boolean {
    val native = e.nativeKeyEvent
    return native.device?.isVirtual == false && PAD_SOURCES.any { native.isFromSource(it) }
}

private fun ControllerSupport.colour(): Color = when (this) {
    ControllerSupport.UNKNOWN -> Color(0xFF334155)
    ControllerSupport.GAMEPAD -> Color(0xFF166534)
    ControllerSupport.POINTER -> Color(0xFF9A3412)
    ControllerSupport.MOTION -> Color(0xFF6B21A8)
    ControllerSupport.TOUCH -> Color(0xFF0E7490)
}

/** The user's recorded controller requirement as a small badge; nothing for [ControllerSupport.UNKNOWN] (no claim is made). */
@Composable
fun ControllerBadge(annotation: GameAnnotation, modifier: Modifier = Modifier) {
    val c = annotation.controller
    if (c == ControllerSupport.UNKNOWN) return
    Text(
        c.badge,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .semantics { contentDescription = c.label }
            .clip(RoundedCornerShape(4.dp))
            .background(c.colour())
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/**
 * The user's own record of a game, each part only when there is one: the controller requirement and the bookmark. Nothing at
 * all for a game with neither. The bookmark leads in the accent colour, as the thing to read before playing.
 */
@Composable
fun GamePersonalSummary(annotation: GameAnnotation?, bookmark: GameBookmark?, modifier: Modifier = Modifier) {
    val controller = annotation?.controller ?: ControllerSupport.UNKNOWN
    val text = bookmark?.text?.takeIf { it.isNotBlank() }
    if (controller == ControllerSupport.UNKNOWN && text == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (text != null) BookmarkBlock(text)
        if (controller != ControllerSupport.UNKNOWN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControllerBadge(annotation ?: GameAnnotation())
                Text("  " + controller.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BookmarkBlock(text: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text("Bookmark", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Edits a game's one bookmark as a draft: nothing is written until Save, which replaces the saved text (a blank draft removes
 * it: Clear empties the draft, Save then confirms). Cancel or B leaves the saved bookmark exactly as it was. Drafts survive
 * activity recreation and the X swap (saved state). [save] returns null once user.json holds the change, else why not; the
 * editor then stays open with the draft. The field takes focus when the editor opens: the editor is only ever opened on purpose.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkEditor(
    gameId: String,
    gameTitle: String,
    current: GameBookmark?,
    accent: Color,
    save: suspend (String) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val saved = current?.text.orEmpty()
    var text by rememberSaveable(gameId) { mutableStateOf(saved) }
    var error by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draft = text.trim()
    val tooLong = draft.length > BOOKMARK_MAX_CHARS
    val changed = draft != saved
    val scope = rememberCoroutineScope()
    val field = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    // a failed write leaves the saved bookmark unchanged; keep the draft retryable
    val canSave = !saving && !tooLong && (changed || error != null)
    LaunchedEffect(gameId) { runCatching { field.requestFocus() } }
    BackHandler { if (!saving) onClose() }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bookmark", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(gameTitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Down from the field reaches Save while there is something to save, never Cancel/Clear by accident
        NoteField(
            text,
            { text = it },
            "Where you are and what to do next: level, boss, save slot",
            Modifier.focusRequester(field),
            enabled = !saving,
            down = if (canSave) saveFocus else cancelFocus
        )
        Text(
            when {
                tooLong -> "${draft.length} / $BOOKMARK_MAX_CHARS characters: shorten it to save."
                saved.isEmpty() -> "One bookmark per game. It shows on this page and on the companion screen."
                else -> "Saving replaces the current bookmark. Cancel keeps it as it is."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let {
            Text(
                "Not saved to the card: $it — Save retries; Cancel keeps the saved bookmark.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val saveInteraction = remember { MutableInteractionSource() }
            val cancelInteraction = remember { MutableInteractionSource() }
            val clearInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val failure = save(draft)
                        saving = false
                        error = failure
                        if (failure == null) onClose()
                    }
                },
                enabled = canSave,
                modifier = Modifier.focusRequester(
                    saveFocus
                ).focusGlow(saveInteraction.collectIsFocusedAsState().value, sortieButtonShape(ButtonDefaults.shape), accent),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = CanvasColour),
                interactionSource = saveInteraction
            ) { Text(if (saving) "Saving…" else if (draft.isEmpty() && saved.isNotEmpty()) "Remove bookmark" else "Save") }
            OutlinedButton(
                onClick = onClose,
                enabled = !saving,
                modifier = Modifier.focusRequester(cancelFocus)
                    .focusGlow(cancelInteraction.collectIsFocusedAsState().value, sortieButtonShape(ButtonDefaults.outlinedShape), accent),
                interactionSource = cancelInteraction
            ) { Text("Cancel") }
            OutlinedButton(
                onClick = {
                    text = ""
                    runCatching { field.requestFocus() }
                },
                enabled = !saving && text.isNotEmpty(),
                modifier = Modifier.focusGlow(clearInteraction.collectIsFocusedAsState().value, sortieButtonShape(ButtonDefaults.outlinedShape), accent),
                interactionSource = clearInteraction
            ) { Text("Clear") }
        }
    }
}

/**
 * Edits what [gameId] needs beyond the built-in controller as a draft: nothing is written until Save; Cancel (or B) drops it.
 * [save] returns null once user.json holds the change, else the reason. [guideControls] — the guide's hand-written controls —
 * is shown read-only for reference and never edited here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControllerEditor(
    gameId: String,
    current: GameAnnotation,
    guideControls: String?,
    accent: Color,
    save: suspend (GameAnnotation) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var controllerKey by rememberSaveable(gameId) { mutableStateOf(current.controller.key) }
    var error by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draft = GameAnnotation(ControllerSupport.entries.firstOrNull { it.key == controllerKey } ?: ControllerSupport.UNKNOWN)
    val scope = rememberCoroutineScope()
    val chosen = remember { FocusRequester() }
    val canSave = !saving && (draft != current || error != null)
    LaunchedEffect(gameId) { runCatching { chosen.requestFocus() } }
    BackHandler { if (!saving) onClose() }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What it needs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ControllerSupport.entries.forEach { option ->
                val interaction = remember { MutableInteractionSource() }
                FilterChip(
                    selected = draft.controller == option,
                    enabled = !saving,
                    onClick = { controllerKey = option.key },
                    label = { Text(if (option == ControllerSupport.UNKNOWN) "Not set" else option.badge) },
                    modifier = (if (draft.controller == option) Modifier.focusRequester(chosen) else Modifier)
                        .focusGlow(interaction.collectIsFocusedAsState().value, FilterChipDefaults.shape, accent),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.25f), selectedLabelColor = accent),
                    interactionSource = interaction
                )
            }
        }
        Text(draft.controller.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        guideControls?.let {
            Text("Guide controls: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(
                "Not saved to the card: $it — Save retries; Cancel discards this change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val saveInteraction = remember { MutableInteractionSource() }
            val cancelInteraction = remember { MutableInteractionSource() }
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        val failure = save(draft)
                        saving = false
                        error = failure
                        if (failure == null) onClose()
                    }
                },
                enabled = canSave,
                modifier = Modifier.focusGlow(saveInteraction.collectIsFocusedAsState().value, sortieButtonShape(ButtonDefaults.shape), accent),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = CanvasColour),
                interactionSource = saveInteraction
            ) { Text(if (saving) "Saving…" else "Save") }
            OutlinedButton(
                onClick = onClose,
                enabled = !saving,
                modifier = Modifier.focusGlow(cancelInteraction.collectIsFocusedAsState().value, sortieButtonShape(ButtonDefaults.outlinedShape), accent),
                interactionSource = cancelInteraction
            ) { Text("Cancel") }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Multi-line note field that a controller can leave: D-pad up/down from a physical gamepad/D-pad moves the focus instead of
 * the caret (the caret stays on the soft keyboard's own arrows and touch), and A brings up the keyboard. [up] and [down] name
 * where those moves land: [FocusRequester.Default] searches spatially, [FocusRequester.Cancel] keeps the focus here. Callers
 * whose field sits above Save/Cancel name Save explicitly, because a spatial search from a wide field picks whichever button
 * is nearest its centre, often Cancel. The caret starts after existing text (so typing appends) and goes there again whenever
 * the caller replaces [value]; the user's own caret moves and IME composition are kept while the text is theirs.
 */
@Composable
internal fun NoteField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    up: FocusRequester = FocusRequester.Default,
    down: FocusRequester = FocusRequester.Default
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var edited by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val field = if (edited.text == value) edited else TextFieldValue(value, TextRange(value.length))
    OutlinedTextField(
        value = field,
        onValueChange = {
            edited = it
            if (it.text != value) onChange(it.text)
        },
        enabled = enabled,
        placeholder = { Text(placeholder) },
        minLines = 2,
        maxLines = 6,
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

/** Explicit up/down focus destinations for a text field (see [NoteField]); must precede the field's own focus target. */
internal fun Modifier.padFocusExits(up: FocusRequester, down: FocusRequester): Modifier = focusProperties {
    this.up = up
    this.down = down
}
