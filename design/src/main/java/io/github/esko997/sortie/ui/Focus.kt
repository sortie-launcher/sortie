package io.github.esko997.sortie.ui

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val PULSE_HALF_PERIOD_S = 0.6
private const val PULSE_FOR_S = 3.0

/**
 * Controller-focus highlight shared by every focusable row/tile: a 3dp [colour] border, a soft blurred glow just outside the bounds whose
 * alpha pulses 0.55..1 (~1.2 s period) and a subtle [focusedScale]. The pulse transition only exists while [focused], so unfocused items
 * run no infinite animation. Unfocused items draw a 2dp [idleBorder] unless it is transparent, i.e. they look exactly as before.
 */
@Composable
fun Modifier.focusGlow(focused: Boolean, shape: Shape, colour: Color = Amber, focusedScale: Float = 1.02f, idleBorder: Color = Color.Transparent): Modifier {
    val scale = animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    // The glow pulses for a few seconds after the focus lands, then holds: an endless pulse kept Sortie redrawing while idle
    // (~50% of a core at the display rate, still ~30% sampled at 20 Hz — a launcher waiting for input should cost nothing).
    // Time comes from the composition's frame clock (vsync on a device), so UI tests can drive the pulse to its end.
    val pulse: State<Float>? = if (focused) {
        val alpha = remember { mutableFloatStateOf(1f) }
        LaunchedEffect(Unit) {
            val t0 = withFrameNanos { it }
            while (true) {
                val t = (withFrameNanos { it } - t0) / 1e9
                if (t >= PULSE_FOR_S) break
                alpha.floatValue = 0.775f + 0.225f * sin(t * PI / PULSE_HALF_PERIOD_S).toFloat()
                delay(50)
            }
            alpha.floatValue = 1f
        }
        alpha
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
            // The blurred outline is rasterised once per size into a bitmap; the pulse then only changes the alpha of one
            // texture draw per frame. Blurring the path on every frame cost ~70% of a core on the Thor.
            val spread = 3.dp.toPx()
            val margin = (spread + 12.dp.toPx()).toInt()
            val w = size.width.toInt() + 2 * margin
            val h = size.height.toInt() + 2 * margin
            val bitmap = ImageBitmap(w, h)
            val outline = shape.createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, this)
            val paint = Paint().apply {
                color = colour
                style = PaintingStyle.Stroke
                strokeWidth = 6.dp.toPx()
                asFrameworkPaint().maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(bitmap).run {
                translate(margin - spread, margin - spread)
                drawOutline(outline, paint)
            }
            onDrawBehind { drawImage(bitmap, topLeft = Offset(-margin.toFloat(), -margin.toFloat()), alpha = pulse.value) }
        }
        .border(3.dp, colour, shape)
}

/**
 * Held buttons for the controller: A (context sheet) and R1 (favourite). `combinedClickable`'s long press only listens to touch;
 * on a held key Android repeats the key-down (first repeat after ~500 ms), so the first repeat is the hold. Every release that
 * follows is swallowed so it does not also click or page: the gamepad's BUTTON_A arrives twice, raw and as the framework's
 * DPAD_CENTER fallback, whose key-up is sent regardless; R1's short press pages systems/sorts on key-up (MainActivity), so
 * swallowing the up after a hold is what keeps a favourite from also paging. A fresh press (repeat 0) re-arms.
 */
@Composable
fun Modifier.keyLongPress(onLongClick: (() -> Unit)?, onHoldR1: (() -> Unit)? = null): Modifier {
    if (onLongClick == null && onHoldR1 == null) return this
    var fired by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { e ->
        val action = when (e.key) {
            Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> onLongClick
            Key.ButtonR1 -> onHoldR1
            else -> null
        } ?: return@onPreviewKeyEvent false
        when {
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                // a fresh press: the previous hold is over (its key-ups, however many, have passed)
                if (fired && e.key != Key.DirectionCenter) fired = false
                fired
            }
            e.type == KeyEventType.KeyDown -> {
                if (!fired) {
                    fired = true
                    action()
                }
                true
            }
            else -> fired
        }
    }
}

/** A focus-aware row: [focusGlow] outline in [colour] plus a subtle surface when focused (D-pad). Long press = context menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    colour: Color = Amber,
    onFavourite: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .focusGlow(focused, shape, colour)
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Slate else Color.Transparent)
            .keyLongPress(onLongClick, onFavourite)
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
