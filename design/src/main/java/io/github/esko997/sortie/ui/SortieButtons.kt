package io.github.esko997.sortie.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Layout, hit target, semantics and click handling remain Material's; only the static shell changes. */
@Composable
fun SortieButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.buttonTreatment(source, enabled),
        enabled = enabled,
        shape = sortieButtonShape(shape),
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

@Composable
fun SortieOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = modifier.buttonTreatment(source, enabled),
        enabled = enabled,
        shape = sortieButtonShape(shape),
        colors = colors,
        elevation = elevation,
        border = border,
        contentPadding = contentPadding,
        interactionSource = source,
        content = content
    )
}

private val InstrumentShape = RoundedCornerShape(6.dp)
private val ArcadeShape = RoundedCornerShape(50)

@Composable
fun sortieButtonShape(original: Shape = ButtonDefaults.shape): Shape = when (LocalSortieButtonStyle.current) {
    SortieButtonStyle.DEFAULT -> original
    SortieButtonStyle.INSTRUMENT -> InstrumentShape
    SortieButtonStyle.ARCADE -> ArcadeShape
}

@Composable
private fun Modifier.buttonTreatment(source: MutableInteractionSource, enabled: Boolean): Modifier {
    val style = LocalSortieButtonStyle.current
    if (style == SortieButtonStyle.DEFAULT) return this
    val focused by source.collectIsFocusedAsState()
    val pressed by source.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme
    val edge = (if (focused) scheme.primary else scheme.onSurface).copy(alpha = if (enabled) 0.85f else 0.25f)
    val shadow = scheme.surfaceContainerLowest.copy(alpha = if (enabled) 0.8f else 0.3f)
    return drawWithCache {
        val inset = 3.dp.toPx()
        val edgeStroke = Stroke((if (focused) 2.dp else 1.dp).toPx())
        val shadowStroke = Stroke(2.dp.toPx())
        val screw = 7.dp.toPx()
        val dot = 1.dp.toPx()
        val radius = if (style == SortieButtonStyle.ARCADE) size.height / 2 else 6.dp.toPx()
        val lowerLip = (if (pressed) 1.dp else 3.dp).toPx()
        onDrawWithContent {
            drawContent()
            if (size.width > inset * 4 && size.height > inset * 4) {
                drawRoundRect(
                    shadow,
                    Offset(inset, inset + lowerLip),
                    Size(size.width - inset * 2, size.height - inset * 2 - lowerLip),
                    CornerRadius(radius),
                    style = shadowStroke
                )
                drawRoundRect(
                    edge,
                    Offset(inset, inset),
                    Size(size.width - inset * 2, size.height - inset * 2 - lowerLip),
                    CornerRadius(radius),
                    style = edgeStroke
                )
                if (style == SortieButtonStyle.INSTRUMENT) {
                    drawCircle(edge, dot, Offset(screw, screw))
                    drawCircle(edge, dot, Offset(size.width - screw, screw))
                    drawCircle(edge, dot, Offset(screw, size.height - screw))
                    drawCircle(edge, dot, Offset(size.width - screw, size.height - screw))
                }
            }
        }
    }
}

/** Preview controls are real launcher controls, scoped so browsing never commits an appearance. */
@Composable
fun SortieButtonStylePreview(style: SortieButtonStyle, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalSortieButtonStyle provides style) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortieButton(onClick = {}) { Text("Play") }
            SortieOutlinedButton(onClick = {}) { Text("Details") }
        }
    }
}
