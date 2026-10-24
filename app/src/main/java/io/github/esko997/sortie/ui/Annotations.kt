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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.esko997.sortie.data.manifest.ControllerSupport
import io.github.esko997.sortie.data.manifest.GameAnnotation
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
        color = Color(0xFFE6E8EB),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .semantics { contentDescription = c.label }
            .clip(RoundedCornerShape(4.dp))
            .background(c.colour())
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** Badge, "where was I?" note and personal mapping notes, each only when recorded; nothing at all for an empty annotation. */
@Composable
fun GameAnnotationSummary(annotation: GameAnnotation, modifier: Modifier = Modifier) {
    if (annotation.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (annotation.controller != ControllerSupport.UNKNOWN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControllerBadge(annotation)
                Text(
                    "  " + annotation.controller.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (annotation.returnNote.isNotBlank()) NoteBlock("Where was I?", annotation.returnNote, Amber)
        if (annotation.mappingNotes.isNotBlank()) NoteBlock("My mapping", annotation.mappingNotes, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteBlock(heading: String, text: String, colour: Color) {
    Column {
        Text(heading, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colour)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Edits [current] as a draft: nothing is written until Save, Cancel (or B) drops the draft, Clear empties it (Save then
 * removes the entry). Drafts survive activity recreation (saved state). [save] returns null once user.json holds the change,
 * else the reason it is not on the card; the editor then stays open with the draft and says so. [guideControls] — the
 * guide's hand-written controls — is shown read-only for reference and never edited here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameAnnotationEditor(
    gameId: String,
    current: GameAnnotation,
    guideControls: String?,
    accent: Color,
    save: suspend (GameAnnotation) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var controllerKey by rememberSaveable(gameId) { mutableStateOf(current.controller.key) }
    var returnNote by rememberSaveable(gameId) { mutableStateOf(current.returnNote) }
    var mapping by rememberSaveable(gameId) { mutableStateOf(current.mappingNotes) }
    var error by rememberSaveable(gameId) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val draft = GameAnnotation(ControllerSupport.entries.firstOrNull { it.key == controllerKey } ?: ControllerSupport.UNKNOWN, mapping, returnNote)
    val changed = draft.normalised() != current.normalised()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val chosenSupport = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    // A failed write leaves the saved annotation unchanged; keep the draft retryable.
    val canSave = !saving && (changed || error != null)
    LaunchedEffect(gameId) { runCatching { first.requestFocus() } }
    BackHandler { if (!saving) onClose() }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Notes & controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FieldLabel("Where was I?")
        NoteField(
            returnNote,
            { returnNote = it },
            "Level, boss, save slot, what to do next",
            Modifier.focusRequester(first),
            enabled = !saving,
            down = chosenSupport
        )
        FieldLabel("What it needs")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ControllerSupport.entries.forEach { option ->
                val interaction = remember { MutableInteractionSource() }
                FilterChip(
                    selected = draft.controller == option,
                    enabled = !saving,
                    onClick = { controllerKey = option.key },
                    label = { Text(if (option == ControllerSupport.UNKNOWN) "Not set" else option.badge) },
                    modifier = (if (draft.controller == option) Modifier.focusRequester(chosenSupport) else Modifier)
                        .focusGlow(interaction.collectIsFocusedAsState().value, FilterChipDefaults.shape, accent),
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.25f), selectedLabelColor = accent),
                    interactionSource = interaction
                )
            }
        }
        Text(draft.controller.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FieldLabel("My mapping notes")
        // Down from the last note reaches Save, never Cancel/Clear all by accident; with Save disabled nothing is unsaved
        NoteField(
            mapping,
            { mapping = it },
            "Buttons you remapped, hotkeys, emulator settings",
            enabled = !saving,
            up = chosenSupport,
            down = if (canSave) saveFocus else cancelFocus
        )
        guideControls?.let {
            Text("Guide controls: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text(
                "Not saved to the card: $it — Save retries; Cancel discards this draft.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF87171)
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
                modifier = Modifier.focusRequester(saveFocus).focusGlow(saveInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape, accent),
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                interactionSource = saveInteraction
            ) { Text(if (saving) "Saving…" else "Save") }
            OutlinedButton(
                onClick = onClose,
                enabled = !saving,
                modifier = Modifier.focusRequester(cancelFocus)
                    .focusGlow(cancelInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                interactionSource = cancelInteraction
            ) { Text("Cancel") }
            OutlinedButton(
                onClick = {
                    controllerKey = ControllerSupport.UNKNOWN.key
                    returnNote = ""
                    mapping = ""
                },
                enabled = !saving && !draft.isEmpty(),
                modifier = Modifier.focusGlow(clearInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                interactionSource = clearInteraction
            ) { Text("Clear all") }
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
