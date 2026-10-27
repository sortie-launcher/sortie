package io.github.esko997.sortie.ui

import android.graphics.BlurMaskFilter
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Focus landing: the glow flares in, then settles to [GLOW_REST] and holds; nothing animates once it has settled. */
private const val GLOW_FLARE_MS = 110
private const val GLOW_SETTLE_MS = 320
private const val GLOW_REST = 0.72f
private const val PRESS_MS = 70

/** The pressed element sinks to this share of its focus lift, then springs back on release. */
private const val PRESS_DEPTH = 0.35f

/** A small, quickly damped overshoot as the focus lands; the fine threshold keeps the spring from snapping before it shows. */
private val FocusSpring = spring(dampingRatio = 0.6f, stiffness = 700f, visibilityThreshold = 0.0005f)
private val UnfocusTween = tween<Float>(150, easing = FastOutSlowInEasing)
private val PressTween = tween<Float>(PRESS_MS, easing = FastOutSlowInEasing)

/**
 * Controller-focus highlight shared by every focusable row/tile: a 3dp [colour] border, a soft blurred glow just outside the
 * bounds and a [focusedScale] lift. The focus lands with one short gesture — the lift springs in with a slight overshoot, the
 * glow flares and settles — and then nothing runs until the next change: an idle launcher costs no frames. [pressed] (the
 * controller's confirm button held, see [confirmHeld]) sinks the lift and brightens the glow; release springs it back. With the
 * system's animations off every change lands at once. Unfocused items draw a 2dp [idleBorder] unless it is transparent.
 */
@Composable
fun Modifier.focusGlow(
    focused: Boolean,
    shape: Shape,
    colour: Color = Accent,
    focusedScale: Float = 1.02f,
    idleBorder: Color = Color.Transparent,
    pressed: Boolean = false
): Modifier {
    // A press only shows in a focused window: its release may have gone to whichever window took the focus mid-press. The
    // window state is read only while pressed, so idle elements do not recompose when the window gains or loses focus.
    val down = focused && pressed && LocalWindowInfo.current.isWindowFocused
    val scale = animateFloatAsState(
        when {
            !focused -> 1f
            down -> 1f + (focusedScale - 1f) * PRESS_DEPTH
            else -> focusedScale
        },
        when {
            !focused -> UnfocusTween
            down -> PressTween
            else -> FocusSpring
        },
        label = "focusScale"
    )
    // The glow's state lives only while focused, so an unfocused item holds no animation at all. Animations run on the
    // composition's frame clock: they pause with the window and, with Remove animations, finish in a single frame.
    val glow: State<Float>? = if (focused) {
        val alpha = remember { Animatable(0f) }
        LaunchedEffect(down) {
            if (down) {
                alpha.animateTo(1f, PressTween)
            } else {
                if (alpha.value < 1f) alpha.animateTo(1f, tween(GLOW_FLARE_MS, easing = LinearOutSlowInEasing))
                alpha.animateTo(GLOW_REST, tween(GLOW_SETTLE_MS, easing = FastOutSlowInEasing))
            }
        }
        alpha.asState()
    } else {
        null
    }
    // state is read only inside the layer/draw lambdas, so the motion re-draws without recomposing the row
    val base = this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    if (glow == null) return if (idleBorder == Color.Transparent) base else base.border(2.dp, idleBorder, shape)
    return base
        .drawWithCache {
            // One blurred outline per element size and shape, shared by every element of that size (a grid's tiles, a list's
            // rows) and tinted at draw time: moving the focus along a grid re-uses it instead of blurring a new bitmap.
            val mask = GlowMasks.get(shape, size, layoutDirection, this)
            val tint = ColorFilter.tint(colour, BlendMode.SrcIn)
            val topLeft = Offset(-mask.margin, -mask.margin)
            onDrawBehind { drawImage(mask.image, topLeft = topLeft, alpha = glow.value, colorFilter = tint) }
        }
        .border(3.dp, colour, shape)
}

private class GlowMask(val image: ImageBitmap, val margin: Float)

private data class GlowKey(val shape: Shape, val width: Int, val height: Int, val density: Float, val layoutDirection: LayoutDirection)

/**
 * The few glow masks in use (one focused element at a time per window, a handful of element sizes per screen), least recently
 * used first out. Built and read while drawing, on the main thread.
 */
private object GlowMasks {
    private const val KEEP = 6
    private val masks = object : LinkedHashMap<GlowKey, GlowMask>(KEEP + 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GlowKey, GlowMask>?): Boolean = size > KEEP
    }

    fun get(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): GlowMask = synchronized(masks) {
        masks.getOrPut(GlowKey(shape, size.width.toInt(), size.height.toInt(), density.density, layoutDirection)) {
            build(shape, size, layoutDirection, density)
        }
    }

    private fun build(shape: Shape, size: Size, layoutDirection: LayoutDirection, density: Density): GlowMask = with(density) {
        val spread = 3.dp.toPx()
        val margin = (spread + 12.dp.toPx()).toInt()
        val bitmap = ImageBitmap(size.width.toInt() + 2 * margin, size.height.toInt() + 2 * margin)
        val outline = shape.createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, density)
        val paint = Paint().apply {
            color = Color.White
            style = PaintingStyle.Stroke
            strokeWidth = 6.dp.toPx()
            asFrameworkPaint().maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).run {
            translate(margin - spread, margin - spread)
            drawOutline(outline, paint)
        }
        GlowMask(bitmap, margin.toFloat())
    }
}

/**
 * Reports whether the controller's confirm button (A, or its D-pad centre / Enter fallback) is held on this element, for the
 * pressed look of [focusGlow]. It only observes: every key goes on to [keyLongPress] and the click, unchanged. Place it before
 * both. The press ends on release, on losing the focus, when the key starts repeating (a hold is [keyLongPress]'s), and when
 * the window loses focus: a game, a sheet or the other screen taking the window also takes the release, so nothing may wait
 * for it. [focusGlow] in turn never draws a press while the window is unfocused, so a returning screen is never caught down.
 */
@Composable
fun Modifier.confirmHeld(onHeld: (Boolean) -> Unit): Modifier {
    val report by rememberUpdatedState(onHeld)
    var held by remember { mutableStateOf(false) }
    fun set(down: Boolean) {
        held = down
        report(down)
    }
    // Window focus loss arrives straight from the view's own callback, not through composition or a frame: a hidden or
    // stopped window composes nothing, and the release may already belong to another window. Idle, the listener does nothing.
    val view = LocalView.current
    DisposableEffect(view) {
        val listener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus -> if (!hasFocus && held) set(false) }
        val observer = view.viewTreeObserver
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            // an observer from before the view was attached is merged into the window's and dies; remove from the live one
            (if (observer.isAlive) observer else view.viewTreeObserver).removeOnWindowFocusChangeListener(listener)
        }
    }
    return this
        .onFocusChanged { if (!it.isFocused && held) set(false) }
        .onPreviewKeyEvent { e ->
            if (e.key == Key.ButtonA || e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) {
                set(e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0)
            }
            false
        }
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

/**
 * Touch feedback for Sortie's own clickable surfaces (rows, tiles, dock slots) instead of the Material ripple: a faint light veil over
 * the clipped element that fades in on press and out on release. A quick tap still shows it briefly before it fades. Presses made
 * while the element has the controller focus are ignored: the focus glow already marks it, and a held A ends in a release that
 * [keyLongPress] swallows, so such a press would never end. Nothing runs between presses; the drawing reads one float.
 */
val PressVeil: IndicationNodeFactory = object : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressVeilNode(interactionSource)

    override fun hashCode(): Int = -1

    override fun equals(other: Any?): Boolean = other === this
}

private const val VEIL_ALPHA = 0.08f
private const val VEIL_IN_MS = 90
private const val VEIL_OUT_MS = 180

private class PressVeilNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val veil = Animatable(0f)

    private suspend fun fadeTo(target: Float, ms: Int) = veil.animateTo(target, animationSpec = tween(ms))

    override fun onAttach() {
        coroutineScope.launch {
            veil.snapTo(0f)
            val presses = ArrayList<PressInteraction.Press>(1)
            var focused = false
            var fade: Job? = null
            source.interactions.collect { interaction ->
                val ended = when (interaction) {
                    is FocusInteraction.Focus -> {
                        focused = true
                        null
                    }
                    is FocusInteraction.Unfocus -> {
                        focused = false
                        null
                    }
                    is PressInteraction.Press -> {
                        if (!focused) {
                            presses += interaction
                            fade?.cancel()
                            fade = launch { fadeTo(1f, VEIL_IN_MS) }
                        }
                        null
                    }
                    is PressInteraction.Release -> interaction.press
                    is PressInteraction.Cancel -> interaction.press
                    else -> null
                }
                if (ended != null && presses.remove(ended) && presses.isEmpty()) {
                    val shown = fade
                    fade = launch {
                        shown?.join()
                        fadeTo(0f, VEIL_OUT_MS)
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = veil.value
        if (alpha > 0f) drawRect(Color.White, alpha = VEIL_ALPHA * alpha)
    }
}

/**
 * The surface under a focused [FocusRow]: on CanvasColour it is PanelColour, as rows always were; on a PanelColour dialog (context sheets, pickers) it
 * still lifts the row a step instead of vanishing into the background. A trace of the focus colour warms it.
 */
private const val ROW_TINT = 0.07f
private val RowShape = RoundedCornerShape(10.dp)

/**
 * A focus-aware row: [focusGlow] outline in [colour] plus a subtle surface when focused (D-pad); holding A sinks it a little.
 * Long press = context menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    colour: Color = Accent,
    onFavourite: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var held by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusGlow(focused, RowShape, colour, pressed = held)
            .fillMaxWidth()
            .clip(RowShape)
            .then(if (focused) Modifier.background(FocusSurfaceColour).background(colour.copy(alpha = ROW_TINT)) else Modifier)
            .confirmHeld { held = it }
            .keyLongPress(onLongClick, onFavourite)
            .combinedClickable(
                interactionSource = interaction,
                indication = PressVeil,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) { content() }
}

/**
 * Sortie's standard text button: outlined, or filled in [colour] when [primary]; the [focusGlow] in [colour] shows the controller
 * focus and sinks while A is held. The label stays on one line (ellipsized), so a button never grows into a two-line pill.
 */
@Composable
fun GlowButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false, colour: Color = Accent, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    if (primary) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.focusGlow(focused, ButtonDefaults.shape, colour, pressed = pressed),
            colors = ButtonDefaults.buttonColors(containerColor = colour, contentColor = CanvasColour),
            interactionSource = interaction
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.focusGlow(focused, ButtonDefaults.outlinedShape, colour, pressed = pressed),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colour),
            interactionSource = interaction
        ) { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** A filter toggle (Material filter chip) with the controller [focusGlow]; [trailing] is a short mark after the label, e.g. ▾. */
@Composable
fun GlowChip(label: String, selected: Boolean, modifier: Modifier = Modifier, trailing: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = trailing?.let { mark -> { Text(mark) } },
        modifier = modifier.focusGlow(focused, FilterChipDefaults.shape, pressed = pressed),
        interactionSource = interaction
    )
}
