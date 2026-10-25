package io.github.esko997.sortie.mecha.ui

import android.os.SystemClock
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaElement
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.ui.Amber
import io.github.esko997.sortie.ui.Slate
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel

/** The stage of the window being composed; [Modifier.mechaSpot] and [Modifier.mechaObstacle] report to it. */
val LocalMechaStage = staticCompositionLocalOf<MechaStage?> { null }

/** The exposed, control-free part of him must be at least this big each way for a spot to qualify: he is always tappable. */
private val MIN_TAP = 40.dp

private val BUBBLE_MAX_WIDTH = 220.dp
private val BUBBLE_PAD_H = 10.dp
private val BUBBLE_PAD_V = 6.dp

/**
 * One window's stage for the companion: the declared spots and obstacles (window pixels, from layout callbacks, never state),
 * and what is drawn (snapshot state read only while drawing, so a step re-draws the layer and recomposes nothing).
 * A process holds one per window that hosts a [MechaStageHost]; [MechaPresence] says which one has the mech.
 */
@Stable
class MechaStage internal constructor(internal val store: MechaStore, internal val displayId: Int, internal val preferredScale: Int, internal val minTap: Int) {
    internal val elements = LinkedHashMap<String, MechaElement>()

    /** The host's bounds in window pixels, or null before its first layout. */
    internal var bounds: MechaRect? = null
        private set

    /** Resumed, allowed by the host and the companion enabled; maintained by [MechaPresence]. */
    internal var active = false
    internal var resumed = false
    internal var allowed = false

    internal var shown by mutableStateOf(false)
    internal var x by mutableIntStateOf(0)
    internal var floor by mutableIntStateOf(0)
    internal var scale by mutableIntStateOf(1)
    internal var frame by mutableIntStateOf(0)
    internal var bob by mutableIntStateOf(0)
    internal var clip by mutableIntStateOf(Int.MAX_VALUE)
    internal var bubble by mutableStateOf<MechaBubble?>(null)

    /** The tap target in window pixels while he stands still where it fits; read by the host's pointer watcher only. */
    internal var hit: MechaRect? = null

    /** Wakes the director: a layout change, an input while dozing, a closed Hangar, a store change or a return from play. */
    internal val signal = Channel<Unit>(Channel.CONFLATED)
    internal var layoutChanged = false
    internal var dozing = false
    internal var wakeRequested = false
    internal var lastInput = SystemClock.uptimeMillis()

    var hangarOpen by mutableStateOf(false)
        private set

    /** Opens the Hangar over this window (the Home header button, or a tap on the mech). */
    fun openHangar() {
        hangarOpen = true
    }

    internal fun closeHangar() {
        hangarOpen = false
        signal.trySend(Unit)
    }

    internal fun report(e: MechaElement) {
        val old = elements[e.id]
        val same = old != null && old.kind == e.kind && old.bounds == e.bounds && old.visible == e.visible
        if (same && old!!.interactive == e.interactive && old.solid == e.solid) return
        elements[e.id] = e
        changed()
    }

    internal fun remove(id: String) {
        if (elements.remove(id) != null) changed()
    }

    internal fun setBounds(b: MechaRect) {
        if (b == bounds) return
        bounds = b
        changed()
    }

    private fun changed() {
        layoutChanged = true
        refreshAround()
        signal.trySend(Unit)
    }

    /** Where he stands still now (null while he moves or is away): the tap target and bubble belong to it. */
    private var standing: MechaPlacement? = null

    internal fun stand(p: MechaPlacement?) {
        standing = p
        refreshAround()
    }

    /**
     * Re-checks the tap target and the bubble against the elements as they are now, on every layout change and not only when
     * his placement changes: a control that scrolled or reflowed into his tap area takes it back, and a bubble moves to a
     * free side or goes. (His placement itself is the director's job, a moment later.)
     */
    private fun refreshAround() {
        val p = standing
        val b = bounds
        if (p == null || b == null) {
            hit = null
            return
        }
        val current = bubble
        val (h, r) = MechaRoam.refresh(p, scale, minTap, current?.rect, 2 * scale, b, elements.values)
        hit = h
        if (current != null && r != current.rect) bubble = r?.let { MechaBubble(current.text, it) }
    }

    /** Any key or touch in the window: the player is here, so a dozing mech wakes and the doze timer restarts. */
    internal fun poke() {
        lastInput = SystemClock.uptimeMillis()
        if (dozing && !wakeRequested) {
            wakeRequested = true
            signal.trySend(Unit)
        }
    }

    /** True when he has somewhere to stand here now (a spot or the home base): where a stroll may take him across. */
    internal fun hasRoom(): Boolean {
        val b = bounds ?: return false
        val s = MechaRoam.chooseScale(elements.values, b, preferredScale, minTap)
        return MechaRoam.places(elements.values, s, b, minTap).isNotEmpty()
    }

    internal fun hitAt(px: Float, py: Float): Boolean {
        val h = hit ?: return false
        return px >= h.left && px < h.right && py >= h.top && py < h.bottom
    }
}

/** A speech bubble: [text] in [rect] (window pixels), measured to fit before it was placed. */
internal class MechaBubble(val text: String, val rect: MechaRect)

/** The stage for this window: the mech's size from the display's real pixel density (about 9-10 mm tall everywhere). */
@Composable
fun rememberMechaStage(store: MechaStore): MechaStage {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(store, view, density) {
        val metrics = view.context.resources.displayMetrics
        MechaStage(
            store,
            view.display?.displayId ?: Display.DEFAULT_DISPLAY,
            preferredScale = MechaRoam.physicalScale(metrics.ydpi, metrics.densityDpi),
            minTap = with(density) { MIN_TAP.roundToPx() }
        )
    }
}

/**
 * The room a host reserves for the companion's home base ([mechaHomeBase]): the mech box at this stage's size. Zero without a
 * stage or while the companion is hidden or not yet loaded, so a hidden companion takes no space and draws nothing.
 */
@Composable
fun mechaBaseSize(): DpSize {
    val stage = LocalMechaStage.current ?: return DpSize.Zero
    val view by stage.store.view.collectAsState()
    if (view.state?.enabled != true) return DpSize.Zero
    val density = LocalDensity.current
    return remember(stage, density) {
        with(density) { DpSize(MechaBody.width(stage.preferredScale).toDp(), MechaBody.height(stage.preferredScale).toDp()) }
    }
}

/**
 * Declares the host's home base: empty space of [mechaBaseSize] that nothing else covers, where he stands when no other spot
 * qualifies. If even the base cannot hold him he hides (the Hangar header button stays the way in).
 */
fun Modifier.mechaHomeBase(id: String): Modifier = mechaSpot(id, MechaSpotKind.BASE)

/**
 * Hosts the companion in one window: [content] with the mech drawn behind it (so every control, tile and text wins and peeks
 * cost nothing) and its speech bubble over it, only on empty space. Screens inside declare where he may go with
 * [Modifier.mechaSpot]. The mech walks only while this window is resumed and [active] (false e.g. while a game is in front);
 * he is on one stage at a time, process-wide ([MechaPresence]). A tap on him (where it touches no control) opens the Hangar,
 * as does [MechaStage.openHangar]. Taps are watched at this common ancestor and never taken from drags, so a scroll that
 * starts on him still scrolls what is under him.
 */
@Composable
fun MechaStageHost(stage: MechaStage, modifier: Modifier = Modifier, active: Boolean = true, content: @Composable () -> Unit) {
    val view by stage.store.view.collectAsState()
    val state = view.state
    // allowed by the host and shown: from composition; resumed: straight from the lifecycle, because a stopped window
    // composes nothing and would never say it went away
    val allowed = active && state?.enabled == true
    SideEffect {
        stage.allowed = allowed
        MechaPresence.setActive(stage, stage.resumed && allowed)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(stage, lifecycle) {
        MechaPresence.register(stage)
        val observer = LifecycleEventObserver { _, _ ->
            stage.resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            MechaPresence.setActive(stage, stage.resumed && stage.allowed)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            MechaPresence.unregister(stage)
        }
    }
    val load = rememberSpriteLoad(view.parts, state?.equipped.orEmpty())
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val measure: (String) -> IntSize = remember(measurer, style, density) {
        with(density) {
            val maxText = (BUBBLE_MAX_WIDTH - BUBBLE_PAD_H * 2).roundToPx()
            val padH = BUBBLE_PAD_H.roundToPx()
            val padV = BUBBLE_PAD_V.roundToPx()
            val f: (String) -> IntSize = { text ->
                val size = measurer.measure(text, style, constraints = Constraints(maxWidth = maxText), maxLines = 3).size
                IntSize(size.width + padH * 2, size.height + padV * 2)
            }
            f
        }
    }
    LaunchedEffect(stage) { MechaDirector(stage, measure).run() }
    CompositionLocalProvider(LocalMechaStage provides stage) {
        Box(
            modifier
                .fillMaxSize()
                .onGloballyPositioned { stage.setBounds(it.boundsInWindow().toMechaRect()) }
                .onPreviewKeyEvent {
                    stage.poke()
                    false
                }
                .pointerInput(stage) { watchTaps(stage) }
        ) {
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    if (!stage.shown) return@drawBehind
                    val sheets = (load as? SpriteLoad.Ready)?.layers ?: return@drawBehind
                    val b = stage.bounds ?: return@drawBehind
                    val s = stage.scale
                    val left = stage.x - b.left
                    val top = MechaBody.frameTop(stage.floor, s) + stage.bob * s - b.top
                    val c = stage.clip
                    if (c == Int.MAX_VALUE) {
                        drawMechaFrame(sheets, stage.frame, left, top, s)
                    } else {
                        clipRect(bottom = (c - b.top).toFloat()) { drawMechaFrame(sheets, stage.frame, left, top, s) }
                    }
                }
            )
            content()
            stage.bubble?.let { BubbleView(it, stage) }
        }
    }
    if (stage.hangarOpen) Hangar(stage.store, onClose = stage::closeHangar)
}

@Composable
private fun BubbleView(bubble: MechaBubble, stage: MechaStage) {
    val b = stage.bounds ?: return
    val r = bubble.rect
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(r.left - b.left, r.top - b.top) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            .background(Slate, RoundedCornerShape(8.dp))
            .border(1.dp, Amber.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = BUBBLE_PAD_H, vertical = BUBBLE_PAD_V)
    ) {
        Text(bubble.text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 3)
    }
}

/**
 * Watches every pointer event on the way down (the Initial pass) without taking it: any touch counts as the player being
 * here. A short single-finger tap that went down and up on the mech's target, without moving past touch slop, opens the
 * Hangar; only that release is consumed, so the control or panel under him does not also react. Drags are left alone.
 */
private suspend fun PointerInputScope.watchTaps(stage: MechaStage) {
    val slop = viewConfiguration.touchSlop
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitPointerEventScope {
        var tracking: PointerId? = null
        var start = Offset.Zero
        var downAt = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            stage.poke()
            val change = event.changes.firstOrNull() ?: continue
            when (event.type) {
                PointerEventType.Press -> {
                    val b = stage.bounds
                    val onMech = b != null && stage.hitAt(change.position.x + b.left, change.position.y + b.top)
                    tracking = if (event.changes.size == 1 && onMech) change.id else null
                    start = change.position
                    downAt = change.uptimeMillis
                }
                PointerEventType.Move -> if (change.id == tracking && (change.position - start).getDistance() > slop) tracking = null
                PointerEventType.Release -> if (change.id == tracking) {
                    tracking = null
                    if (!change.isConsumed && change.uptimeMillis - downAt < longPress) {
                        change.consume()
                        stage.openHangar()
                    }
                }
                else -> Unit
            }
        }
    }
}

/**
 * Declares a spot for the companion on this element: [kind] says where he stands relative to it. Only reported while the
 * element is laid out (a lazy item scrolled away is gone), and it counts only while he fits there whole and in view. The
 * element is also an obstacle for every other spot; [interactive] marks a control, which a tap on him must never cover.
 * Several spots on one element take one id each.
 */
fun Modifier.mechaSpot(id: String, kind: MechaSpotKind, interactive: Boolean = false): Modifier = this then MechaSpotModifier(id, kind, interactive)

/**
 * Declares something the companion must stay clear of (text, a control) without being a place to stand. [solid] false: the
 * element draws nothing over most of its bounds (a transparent list row): he may show behind it, but a tap on him there
 * would be the row's, so with [interactive] it still keeps his tap target away.
 */
fun Modifier.mechaObstacle(id: String, interactive: Boolean = false, solid: Boolean = true): Modifier =
    this then MechaSpotModifier(id, null, interactive, solid)

private data class MechaSpotModifier(val id: String, val kind: MechaSpotKind?, val interactive: Boolean, val solid: Boolean = true) :
    ModifierNodeElement<MechaSpotNode>() {
    override fun create() = MechaSpotNode(id, kind, interactive, solid)

    override fun update(node: MechaSpotNode) {
        if (node.id != id) node.forget()
        node.id = id
        node.kind = kind
        node.interactive = interactive
        node.solid = solid
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mechaSpot"
        properties["id"] = id
        properties["kind"] = kind
    }
}

private class MechaSpotNode(var id: String, var kind: MechaSpotKind?, var interactive: Boolean, var solid: Boolean) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    private var stage: MechaStage? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val st = currentValueOf(LocalMechaStage) ?: return
        stage = st
        if (!coordinates.isAttached) return
        val p = coordinates.positionInWindow()
        val left = p.x.roundToInt()
        val top = p.y.roundToInt()
        val v = coordinates.boundsInWindow().toMechaRect()
        st.report(
            MechaElement(
                id,
                kind,
                MechaRect(left, top, left + coordinates.size.width, top + coordinates.size.height),
                v,
                interactive,
                solid
            )
        )
    }

    fun forget() {
        stage?.remove(id)
    }

    override fun onDetach() {
        forget()
        stage = null
    }
}

private fun Rect.toMechaRect() = MechaRect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
