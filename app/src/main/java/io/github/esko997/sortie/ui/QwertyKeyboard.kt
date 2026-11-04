package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val KeyShape = RoundedCornerShape(8.dp)
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L

/**
 * Sortie's touch-only US QWERTY layout, filling the space it is given, with keys sized by their width in units.
 * Delete and cursor keys repeat while held.
 * Everything a key does goes to [onSend]; the keyboard shows nothing of what was typed and keeps no record of it. Shift is
 * the only state it holds.
 */
@Composable
internal fun QwertyKeyboard(onSend: (QwertySend) -> Unit, modifier: Modifier = Modifier) {
    val send by rememberUpdatedState(onSend)
    var shift by remember { mutableStateOf(ShiftMode.OFF) }
    val press = remember {
        { key: QwertyKey ->
            val (out, next) = Qwerty.press(key, shift)
            shift = next
            if (out != null) send(out)
        }
    }
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Qwerty.ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    QwertyKeyCap(
                        key,
                        shift,
                        Modifier.weight(key.width),
                        press
                    )
                }
            }
        }
    }
}

@Composable
private fun QwertyKeyCap(key: QwertyKey, shift: ShiftMode, modifier: Modifier, press: (QwertyKey) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val repeats = key is QwertyKey.Command && key.repeats
    // a held repeating key fires on its own clock; its release must not fire once more
    val held = remember { BooleanArray(1) }
    if (repeats) {
        LaunchedEffect(pressed) {
            if (!pressed) return@LaunchedEffect
            held[0] = false
            delay(REPEAT_DELAY_MS)
            while (true) {
                held[0] = true
                press(key)
                delay(REPEAT_INTERVAL_MS)
            }
        }
    }
    val active = key is QwertyKey.Shift && shift != ShiftMode.OFF
    val label = Qwerty.label(key, shift)
    val name = Qwerty.name(key, shift)
    Box(
        modifier
            .fillMaxHeight()
            .focusGlow(focused, KeyShape, pressed = pressed)
            .clip(KeyShape)
            .background(if (active) Accent else PanelColour)
            .clickable(interactionSource = interaction, indication = PressVeil, role = Role.Button) {
                if (held[0]) held[0] = false else press(key)
            }
            .clearAndSetSemantics {
                contentDescription = name
                role = Role.Button
                if (key is QwertyKey.Shift) {
                    stateDescription = when (shift) {
                        ShiftMode.OFF -> "Off"
                        ShiftMode.ONCE -> "On for one character"
                        ShiftMode.LOCKED -> "Locked"
                    }
                }
            }
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (key is QwertyKey.Shift && shift == ShiftMode.LOCKED) "Shift lock" else label,
            style = if (key is QwertyKey.Character && key.base != ' ') MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = if (key is QwertyKey.Character) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                active -> CanvasColour
                key is QwertyKey.Character -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}
