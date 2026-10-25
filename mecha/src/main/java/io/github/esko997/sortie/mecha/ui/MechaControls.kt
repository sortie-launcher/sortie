package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.mecha.MECHA_FAMILIES
import io.github.esko997.sortie.mecha.MechaPart
import io.github.esko997.sortie.ui.Amber
import io.github.esko997.sortie.ui.Ink
import io.github.esko997.sortie.ui.focusGlow
import kotlin.math.roundToInt

internal val Muted = Color(0xFF9AA3AD)
internal val Warning = Color(0xFFF87171)
internal val Hairline = Color(0xFF2A323C)
private val ButtonFace = Color(0xFF26303A)

internal fun familyName(family: String): String = family.replaceFirstChar { it.uppercase() }

/** Catalog family order (the starter yard kit first), then variant. */
internal val partOrder: Comparator<MechaPart> = compareBy<MechaPart>(
    { MECHA_FAMILIES.indexOf(it.family).let { i -> if (i < 0) MECHA_FAMILIES.size else i } },
    { it.variant }
)

/** A compact button in Sortie's focus language: amber glow when the controller is on it, amber face when [primary]. */
@Composable
internal fun MechaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        color = when {
            !enabled -> Muted.copy(alpha = 0.55f)
            primary -> Ink
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            .focusGlow(focused, shape)
            .clip(shape)
            .background(if (primary && enabled) Amber else ButtonFace)
            .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/** One choice of a set (tab, slot, pose, part): the selected one carries an amber outline and text. */
@Composable
internal fun MechaChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, dim: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        color = when {
            selected -> Amber
            dim -> Muted
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            .focusGlow(focused, shape)
            .clip(shape)
            .background(ButtonFace)
            .border(if (selected) 2.dp else 1.dp, if (selected) Amber else Hairline, shape)
            .semantics { this.selected = selected }
            .clickable(interaction, null, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp)
    )
}

/** A stat as a row of ten chunky cells, so it reads like the sprite it belongs to rather than a progress bar. */
@Composable
internal fun StatCells(label: String, value: Int, max: Int, modifier: Modifier = Modifier) {
    val filled = if (max <= 0) 0 else ((value.coerceIn(0, max) * 10f) / max).roundToInt()
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label $value of $max" }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Muted)
            Text("$value", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier
                .padding(top = 3.dp)
                .width(150.dp)
                .height(8.dp)
                .semantics { stateDescription = "$filled of 10" }
                .drawBehind {
                    val gap = 2.dp.toPx()
                    val cell = (size.width - gap * 9) / 10
                    for (i in 0 until 10) {
                        drawRect(if (i < filled) Amber else Hairline, Offset(i * (cell + gap), 0f), Size(cell, size.height))
                    }
                }
        ) {}
    }
}
