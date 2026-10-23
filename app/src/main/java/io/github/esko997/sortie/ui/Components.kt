package io.github.esko997.sortie.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.esko997.sortie.data.db.GameEntity
import java.io.File

/**
 * Controller-focus highlight shared by every focusable row/tile: a 3dp [colour] border, a soft blurred glow just outside the bounds whose
 * alpha pulses 0.55..1 (~1.2 s period) and a subtle [focusedScale]. The pulse transition only exists while [focused], so unfocused items
 * run no infinite animation. Unfocused items draw a 2dp [idleBorder] unless it is transparent, i.e. they look exactly as before.
 */
@Composable
fun Modifier.focusGlow(focused: Boolean, shape: Shape, colour: Color = Amber, focusedScale: Float = 1.02f, idleBorder: Color = Color.Transparent): Modifier {
    val scale = animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    val pulse: State<Float>? = if (focused) {
        rememberInfiniteTransition(label = "focusGlow").animateFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "focusGlowAlpha"
        )
    } else {
        null
    }
    // state is read only inside the layer/draw lambdas, so the pulse re-draws without recomposing the row
    val base = this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    if (pulse == null) return if (idleBorder == Color.Transparent) base else base.border(2.dp, idleBorder, shape)
    return base
        .drawWithCache {
            val spread = 3.dp.toPx()
            val outline = shape.createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, this)
            val paint = Paint().apply {
                color = colour
                style = PaintingStyle.Stroke
                strokeWidth = 6.dp.toPx()
                asFrameworkPaint().maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            onDrawBehind {
                paint.alpha = colour.alpha * pulse.value
                translate(-spread, -spread) { drawIntoCanvas { it.drawOutline(outline, paint) } }
            }
        }
        .border(3.dp, colour, shape)
}

/** A focus-aware row: [focusGlow] outline in [colour] plus a subtle surface when focused (D-pad). Long press = context menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusRow(onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, colour: Color = Amber, content: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .focusGlow(focused, shape, colour)
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Slate else Color.Transparent)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
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
        if (path != null) {
            AsyncImage(model = File(path), contentDescription = null, modifier = Modifier.size(width.dp, height.dp))
        } else {
            Text(game.title.take(1), style = MaterialTheme.typography.titleLarge, color = Color(0xFF4A525C))
        }
    }
}

@Composable
fun GameRow(game: GameEntity, minutes: Int?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, hasSave: Boolean = false) {
    FocusRow(onClick = onClick, onLongClick = onLongClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(game)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(game.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stars(game.fit)
                    val meta = listOfNotNull(game.year?.toString(), game.players?.let { "$it P" }, game.genre?.substringBefore(',')?.take(24))
                    Text(
                        "  " + meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    if (game.hack) Badge("hack")
                    if (game.placeholder) Badge("not on device", Color(0xFF7F1D1D))
                    if (game.arriving) Badge("arriving", Color(0xFF1E3A8A))
                    if ((minutes ?: 0) > 0) Badge("${minutes}m played", Color(0xFF14532D))
                    if (hasSave) Badge("save", Color(0xFF3F3F46))
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
        modifier = Modifier.padding(end = 6.dp, top = 2.dp).clip(RoundedCornerShape(4.dp)).background(colour).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun ColourChip(colour: Color) {
    Box(Modifier.width(6.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(colour))
}
