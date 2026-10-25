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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEvent
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
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.mecha.MechaBody
import io.github.esko997.sortie.mecha.MechaEdge
import io.github.esko997.sortie.mecha.MechaElement
import io.github.esko997.sortie.mecha.MechaGesture
import io.github.esko997.sortie.mecha.MechaPlacement
import io.github.esko997.sortie.mecha.MechaProp
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaRoam
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaTouch
import io.github.esko997.sortie.ui.Amber
import io.github.esko997.sortie.ui.Slate
import io.github.esko997.sortie.ui.displayLitFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel

/** The stage of the window being composed; [Modifier.mechaSpot] and [Modifier.mechaObstacle] report to it. */
val LocalMechaStage = staticCompositionLocalOf<MechaStage?> { null }

/** The exposed, control-free part of him must be at least this big each way for a spot to qualify: he is always tappable. */
private val MIN_TAP = 40.dp

/** Let go of him this close to the edge that faces the other display and he goes across. */
private val EDGE_ZONE = 48.dp

private val BUBBLE_MAX_WIDTH = 220.dp
private val BUBBLE_PAD_H = 10.dp
private val BUBBLE_PAD_V = 6.dp

private val HEART = Color(0xFFF472B6)
private val SNORE = Color(0xFFB8C4D6)

/** A declared element pushed by the mech: [id]'s content drawn shifted by ([dx], [dy]) window pixels (a poke, a lift). */
@Immutable
internal data class MechaNudge(val id: String, val dx: Int, val dy: Int)

/**
 * One window's stage for the companion: the declared spots and obstacles (window pixels, from layout callbacks, never state),
 * and what is drawn (snapshot state read only while drawing, so a step re-draws the layer and recomposes nothing).
 * A process holds one per window that hosts a [MechaStageHost]; [MechaPresence] says which one has the mech.
 */
@Stable
class MechaStage internal constructor(
    internal val store: MechaStore,
    internal val displayId: Int,
    internal val preferredScale: Int,
    internal val minTap: Int,
    internal val edgeZone: Int = minTap
) {
    internal val elements = LinkedHashMap<String, MechaElement>()

    /** Ids of declared elements that hold the focus (or contain what does): never lifted, never sat on. */
    internal val focused = HashSet<String>()

    /** Fingers down in this window now, window pixels by pointer id: a touched element is never lifted. */
    internal val touches = HashMap<Long, Pair<Float, Float>>()

    /**
     * The edge facing the other display on a stacked dual-screen handheld: the default display is the upper screen (AYN
     * Thor), so its bottom edge faces down and every other display's top edge faces up. Only used while another display
     * has a stage.
     */
    internal val toward: MechaEdge = if (displayId == Display.DEFAULT_DISPLAY) MechaEdge.BOTTOM else MechaEdge.TOP

    /** The host's bounds in window pixels, or null before its first layout. */
    internal var bounds: MechaRect? = null
        private set

    /** Shown (resumed on a lit display), allowed by the host and the companion enabled; maintained by [MechaPresence]. */
    internal var active = false
    internal var resumed = false
    internal var allowed = false

    internal var shown by mutableStateOf(false)
    internal var x by mutableIntStateOf(0)
    internal var floor by mutableIntStateOf(0)
    internal var scale by mutableIntStateOf(1)
    internal var rig by mutableStateOf(MechaRig(0))
    internal var bob by mutableIntStateOf(0)
    internal var clip by mutableIntStateOf(Int.MAX_VALUE)

    /** Drawn over the interface instead of behind it: while carried, and seated with the legs over an element's face. */
    internal var front by mutableStateOf(false)

    /** A pet's heart rising over his head, by whole sprite pixels (-1: none); a nap's Z likewise. */
    internal var heart by mutableIntStateOf(-1)
    internal var snore by mutableIntStateOf(-1)

    /** The element he is poking or lifting, drawn shifted; dropped back at once on any input ([poke]). */
    internal var nudge by mutableStateOf<MechaNudge?>(null)
    internal var bubble by mutableStateOf<MechaBubble?>(null)

    /** The tap target in window pixels while he stands still where it fits; read by the host's pointer watcher only. */
    internal var hit: MechaRect? = null

    /** Wakes the director: a layout change, an input while dozing, a closed Hangar, a store change, a gesture, a return from play. */
    internal val signal = Channel<Unit>(Channel.CONFLATED)
    internal var layoutChanged = false
    internal var dozing = false
    internal var wakeRequested = false
    internal var lastInput = SystemClock.uptimeMillis()

    /** A vignette is playing; any input ends it ([interrupted]). */
    internal var busy = false
    internal var interrupted = false

    /** In the player's hand (held still or carried): the pointer watcher moves him, the director only swings his legs. */
    internal var carried = false

    /** Finished gestures for the director, oldest first: PET, HANGAR or DROP (with [dropAt], the finger in window pixels). */
    internal val gestures = ArrayDeque<MechaTouch>()
    internal var dropAt: Pair<Float, Float>? = null

    var hangarOpen by mutableStateOf(false)
        private set

    /** Opens the Hangar over this window (the Home header button, or a hold on the mech). */
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
        if (same && old!!.interactive == e.interactive && old.solid == e.solid && old.props == e.props) return
        elements[e.id] = e
        changed()
    }

    internal fun remove(id: String) {
        focused -= id
        if (nudge?.id == id) nudge = null
        if (elements.remove(id) != null) changed()
    }

    internal fun setFocused(id: String, has: Boolean) {
        if (has) {
            focused += id
            if (nudge?.id == id) nudge = null
        } else {
            focused -= id
        }
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

    /**
     * Any key or touch in the window: the player is here, so a dozing mech wakes and the doze timer restarts, and a vignette
     * stops: a poked or lifted element is back in place before this returns.
     */
    internal fun poke() {
        lastInput = SystemClock.uptimeMillis()
        nudge = null
        if (busy && !interrupted) {
            interrupted = true
            signal.trySend(Unit)
        }
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

    // ---- gestures, from the pointer watcher ----

    /** Box-left and feet offsets from the finger while he is in hand, so he hangs where he was gripped. */
    private var gripDx = 0f
    private var gripDy = 0f

    /** Held still long enough: picked up where he stands, drawn over the interface, dangling. */
    internal fun grab(fx: Float, fy: Float) {
        carried = true
        busy = false
        nudge = null
        bubble = null
        stand(null)
        front = true
        clip = Int.MAX_VALUE
        rig = MechaRigs.DANGLE
        bob = -2
        gripDx = fx - x
        gripDy = fy - floor
        signal.trySend(Unit)
    }

    internal fun carryTo(fx: Float, fy: Float) {
        x = (fx - gripDx).roundToInt()
        floor = (fy - gripDy).roundToInt()
    }

    /** Let go: [how] is HANGAR (released still) or DROP (released after a carry, finger at [fx], [fy]). */
    internal fun letGo(how: MechaTouch, fx: Float, fy: Float) {
        carried = false
        front = false
        bob = 0
        dropAt = fx to fy
        gestures.addLast(how)
        signal.trySend(Unit)
    }

    internal fun petted() {
        gestures.addLast(MechaTouch.PET)
        signal.trySend(Unit)
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
            minTap = with(density) { MIN_TAP.roundToPx() },
            edgeZone = with(density) { EDGE_ZONE.roundToPx() }
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
 * cost nothing) and its speech bubble over it, only on empty space; while carried or seated he is drawn over it. Screens
 * inside declare where he may go with [Modifier.mechaSpot]. He lives (idles, strolls, plays) only while this window is
 * resumed on a lit display and [active]; otherwise nothing of his runs at all. He is on one stage at a time, process-wide
 * ([MechaPresence]).
 *
 * Touch on him (watched at this common ancestor in the Initial pass, [MechaGesture]): a tap pets him, a still hold opens the
 * Hangar (as does [MechaStage.openHangar]), a hold then a move carries him. A move before the hold is never taken, so a
 * scroll that starts on him still scrolls what is under him.
 */
@Composable
fun MechaStageHost(stage: MechaStage, modifier: Modifier = Modifier, active: Boolean = true, content: @Composable () -> Unit) {
    val view by stage.store.view.collectAsState()
    val state = view.state
    // allowed by the host and shown: from composition; resumed on a lit display: from the lifecycle's state flow and the
    // display's callbacks, because a stopped or dark window composes nothing and would never say it went away
    val allowed = active && state?.enabled == true
    SideEffect {
        stage.allowed = allowed
        MechaPresence.setActive(stage, stage.resumed && allowed)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val androidView = LocalView.current
    DisposableEffect(stage) {
        MechaPresence.register(stage)
        onDispose {
            stage.resumed = false
            MechaPresence.unregister(stage)
        }
    }
    LaunchedEffect(stage, lifecycle, androidView) {
        mechaShown(lifecycle, displayLitFlow(androidView)).collect { shown ->
            stage.resumed = shown
            MechaPresence.setActive(stage, shown && stage.allowed)
        }
    }
    val load = rememberSpriteLoad(view.parts, state?.equipped.orEmpty())
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val measure: (String) -> IntSize = remember(measurer, style, density) {
        with(density) {
            val maxText = (BUBBLE_MAX_WIDTH - BUBBLE_PAD_H * 2).roundToPx()
            val padH = BUBBLE_PAD_H.roundToPx()
            val padV = BUBBLE_PAD_V.roundToPx()
            val f: (String) -> IntSize = { text ->
                val size = measurer.measure(text, style, constraints = Constraints(maxWidth = maxText), maxLines = 3)
                    .size
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
                .pointerInput(stage) { watchTouches(stage, haptics) }
        ) {
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    if (!stage.shown || stage.front) return@drawBehind
                    val sheets = (load as? SpriteLoad.Ready)?.layers ?: return@drawBehind
                    drawMech(stage, sheets)
                }
            )
            content()
            // over the interface: a carried or seated mech, a pet's heart and a nap's Z (small, short-lived)
            Spacer(
                Modifier.fillMaxSize().drawBehind {
                    if (!stage.shown) return@drawBehind
                    if (stage.front) (load as? SpriteLoad.Ready)?.layers?.let { drawMech(stage, it) }
                    drawMarks(stage)
                }
            )
            stage.bubble?.let { BubbleView(it, stage) }
        }
    }
    if (stage.hangarOpen) Hangar(stage.store, onClose = stage::closeHangar)
}

/** The mech at his stage position, clipped under a peek's edge while drawn behind the interface. */
private fun DrawScope.drawMech(stage: MechaStage, sheets: List<ImageBitmap>) {
    val b = stage.bounds ?: return
    val s = stage.scale
    val left = stage.x - b.left
    val top = MechaBody.frameTop(stage.floor, s) + stage.bob * s - b.top
    val c = stage.clip
    if (c == Int.MAX_VALUE || stage.front) {
        drawMechaFrame(sheets, stage.rig, left, top, s)
    } else {
        clipRect(bottom = (c - b.top).toFloat()) { drawMechaFrame(sheets, stage.rig, left, top, s) }
    }
}

/** A pet's heart over his head and a nap's Z beside it, rising by whole sprite pixels. */
private fun DrawScope.drawMarks(stage: MechaStage) {
    val heart = stage.heart
    val snore = stage.snore
    if (heart < 0 && snore < 0) return
    val b = stage.bounds ?: return
    val s = stage.scale
    val top = MechaBody.frameTop(stage.floor, s) + stage.bob * s - b.top
    val centre = stage.x - b.left + MechaBody.FRAME / 2 * s
    if (heart >= 0) {
        val g = MechaGlyph.HEART
        g.draw(this, HEART, centre - g.width / 2 * s, top - (g.height + 1 + heart) * s, s)
    }
    if (snore >= 0) {
        val g = MechaGlyph.ZED
        g.draw(this, SNORE, centre + (8 + snore) * s, top + (4 - 2 * snore) * s, s)
    }
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
 * Watches every pointer event on the way down (the Initial pass): any touch counts as the player being here, and the fingers
 * down are kept for [io.github.esko997.sortie.mecha.MechaPlay.canLift]. One finger that goes down on the mech's target is
 * classified by [MechaGesture]: a tap pets him, a still hold picks him up (released still: the Hangar), a hold then a move
 * carries him until released. Only what is his is consumed (the pet's release, everything after the hold), so the control
 * or panel under him never also reacts; a move before the hold is a swipe and is left alone for the scroller under him.
 */
private suspend fun PointerInputScope.watchTouches(stage: MechaStage, haptics: HapticFeedback) {
    val slop = viewConfiguration.touchSlop
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            seen(stage, event)
            val change = event.changes.firstOrNull() ?: continue
            if (event.type != PointerEventType.Press || event.changes.size != 1) continue
            val b = stage.bounds ?: continue
            if (!stage.hitAt(change.position.x + b.left, change.position.y + b.top)) continue
            val id: PointerId = change.id
            val gesture = MechaGesture(slop)
            gesture.down(change.position.x, change.position.y, change.uptimeMillis)
            var lastX = change.position.x
            var lastY = change.position.y
            var lastT = change.uptimeMillis
            while (true) {
                val next = if (gesture.state == MechaTouch.PENDING) {
                    withTimeoutOrNull(gesture.holdIn(lastT).coerceAtLeast(1)) { awaitPointerEvent(PointerEventPass.Initial) }
                } else {
                    awaitPointerEvent(PointerEventPass.Initial)
                }
                if (next == null) {
                    // still for the whole hold: picked up
                    lastT += gesture.holdIn(lastT)
                    if (gesture.at(lastT) == MechaTouch.HELD) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        stage.grab(lastX + b.left, lastY + b.top)
                    }
                    continue
                }
                seen(stage, next)
                val c = next.changes.firstOrNull { it.id == id }
                val held = gesture.state == MechaTouch.HELD || gesture.state == MechaTouch.CARRY
                if (c == null || next.changes.size > 1) {
                    // a second finger or a lost pointer: the gesture is over; a mech in hand is let go where he is
                    if (held) stage.letGo(MechaTouch.DROP, lastX + b.left, lastY + b.top)
                    break
                }
                lastX = c.position.x
                lastY = c.position.y
                lastT = c.uptimeMillis
                if (next.type == PointerEventType.Release || !c.pressed) {
                    when (gesture.up(lastX, lastY, lastT)) {
                        MechaTouch.PET -> {
                            c.consume()
                            stage.petted()
                        }
                        MechaTouch.HANGAR -> {
                            c.consume()
                            if (!held) stage.grab(lastX + b.left, lastY + b.top) // the hold ran out between events
                            stage.letGo(MechaTouch.HANGAR, lastX + b.left, lastY + b.top)
                        }
                        MechaTouch.DROP -> {
                            c.consume()
                            if (!held) stage.grab(lastX + b.left, lastY + b.top)
                            stage.letGo(MechaTouch.DROP, lastX + b.left, lastY + b.top)
                        }
                        else -> Unit
                    }
                    break
                }
                when (gesture.move(lastX, lastY, lastT)) {
                    MechaTouch.SWIPE -> break // a scroll that started on him: not his
                    MechaTouch.HELD -> {
                        if (!held) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            stage.grab(lastX + b.left, lastY + b.top)
                        }
                        c.consume()
                    }
                    MechaTouch.CARRY -> {
                        if (!held) stage.grab(lastX + b.left, lastY + b.top)
                        stage.carryTo(lastX + b.left, lastY + b.top)
                        c.consume()
                    }
                    else -> Unit
                }
            }
        }
    }
}

/** Every pointer event: the player is here, and which fingers are down where (window pixels). */
private fun seen(stage: MechaStage, event: PointerEvent) {
    stage.poke()
    val b = stage.bounds ?: return
    for (c in event.changes) {
        if (c.pressed) stage.touches[c.id.value] = (c.position.x + b.left) to (c.position.y + b.top) else stage.touches.remove(c.id.value)
    }
}

/**
 * Declares a spot for the companion on this element: [kind] says where he stands relative to it. Only reported while the
 * element is laid out (a lazy item scrolled away is gone), and it counts only while he fits there whole and in view. The
 * element is also an obstacle for every other spot; [interactive] marks a control, which a tap on him must never cover.
 * [props] lets him poke or lift it ([MechaProp]; never a control). Several spots on one element take one id each.
 */
fun Modifier.mechaSpot(id: String, kind: MechaSpotKind, interactive: Boolean = false, props: Set<MechaProp> = emptySet()): Modifier =
    this then MechaSpotModifier(id, kind, interactive, props = props)

/**
 * Declares something the companion must stay clear of (text, a control) without being a place to stand. [solid] false: the
 * element draws nothing over most of its bounds (a transparent list row): he may show behind it, but a tap on him there
 * would be the row's, so with [interactive] it still keeps his tap target away. [props]: as for [mechaSpot].
 */
fun Modifier.mechaObstacle(id: String, interactive: Boolean = false, solid: Boolean = true, props: Set<MechaProp> = emptySet()): Modifier =
    this then MechaSpotModifier(id, null, interactive, solid, props)

private data class MechaSpotModifier(
    val id: String,
    val kind: MechaSpotKind?,
    val interactive: Boolean,
    val solid: Boolean = true,
    val props: Set<MechaProp> = emptySet()
) : ModifierNodeElement<MechaSpotNode>() {
    override fun create() = MechaSpotNode(id, kind, interactive, solid, props)

    override fun update(node: MechaSpotNode) {
        if (node.id != id) node.forget()
        node.id = id
        node.kind = kind
        node.interactive = interactive
        node.solid = solid
        if (node.props != props) {
            node.props = props
            node.invalidateDraw()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mechaSpot"
        properties["id"] = id
        properties["kind"] = kind
    }
}

/**
 * Reports its element's layout and focus to the stage; an element with [props] also draws its content shifted while he
 * pokes or lifts it (read while drawing, so only this element redraws).
 */
private class MechaSpotNode(var id: String, var kind: MechaSpotKind?, var interactive: Boolean, var solid: Boolean, var props: Set<MechaProp>) :
    Modifier.Node(),
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode,
    DrawModifierNode,
    FocusEventModifierNode {
    private var stage: MechaStage? = null
    private var hasFocus = false

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
                solid,
                props
            )
        )
        st.setFocused(id, hasFocus)
    }

    override fun onFocusEvent(focusState: FocusState) {
        hasFocus = focusState.hasFocus
        stage?.setFocused(id, hasFocus)
    }

    override fun ContentDrawScope.draw() {
        val n = if (props.isEmpty()) null else currentValueOf(LocalMechaStage)?.nudge
        if (n == null || n.id != id) {
            drawContent()
        } else {
            translate(n.dx.toFloat(), n.dy.toFloat()) { this@draw.drawContent() }
        }
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
