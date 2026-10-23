package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.esko997.sortie.data.db.GameEntity
import java.io.File

/** A focus-aware row: amber outline when focused (D-pad), subtle surface otherwise. */
@Composable
fun FocusRow(onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Slate else Color.Transparent)
            .border(2.dp, if (focused) Amber else Color.Transparent, RoundedCornerShape(10.dp))
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
    ) { content() }
}

@Composable
fun Stars(fit: Int, modifier: Modifier = Modifier) {
    if (fit <= 0) return
    Text("★".repeat(fit.coerceIn(0, 5)) + "☆".repeat(5 - fit.coerceIn(0, 5)), color = Amber, style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

@Composable
fun Cover(game: GameEntity, width: Int = 56, height: Int = 72, modifier: Modifier = Modifier) {
    val path = game.cover ?: game.screenshot
    Box(modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(Slate), contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(model = File(path), contentDescription = null, modifier = Modifier.size(width.dp, height.dp))
        else Text(game.title.take(1), style = MaterialTheme.typography.titleLarge, color = Color(0xFF4A525C))
    }
}

@Composable
fun GameRow(game: GameEntity, minutes: Int?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    FocusRow(onClick = onClick, onLongClick = onLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(game)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stars(game.fit)
                    val meta = listOfNotNull(game.year?.toString(), game.players?.let { "$it P" }, game.genre?.substringBefore(',')?.take(24))
                    Text("  " + meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row {
                    if (game.hack) Badge("hack")
                    if (game.placeholder) Badge("not on device", Color(0xFF7F1D1D))
                    if (game.arriving) Badge("arriving", Color(0xFF1E3A8A))
                    if ((minutes ?: 0) > 0) Badge("${minutes}m played", Color(0xFF14532D))
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, colour: Color = Color(0xFF334155)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFE6E8EB),
        modifier = Modifier.padding(end = 6.dp, top = 2.dp).clip(RoundedCornerShape(4.dp)).background(colour).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
fun ColourChip(colour: Color) {
    Box(Modifier.width(6.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(colour))
}
