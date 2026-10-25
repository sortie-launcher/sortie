package io.github.esko997.sortie.mecha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.esko997.sortie.mecha.MechaSlot
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The dock's sprite box aims for this height; [mechaSize] rounds it to a whole multiple of the 64px frame. */
private val DOCK_SPRITE = 52.dp

/** How long a reaction (new part, care, rename) and a status message stay up. */
private const val CHEER_MS = 2_400L
private const val MESSAGE_MS = 5_000L

/** Walk pace in sprite pixels per step; a step lasts as long as one walk frame, so the feet and the ground agree. */
private const val STRIDE_PX = 3
private const val STEP_MS = 120L

/**
 * The companion's strip: a floor lane the mech idles and strolls along, its name and one line of status, and the Hangar
 * button. Touching the mech also opens the Hangar; the controller reaches it through the button only (one focus stop).
 *
 * Behaviour: a stroll to a new spot whenever [wander] changes (each navigation) and now and then while the screen is resumed;
 * never while [reading] (it dozes then), resting or with motion off. New parts, care and renames get a short cheer.
 * A hidden companion shows only a "Show companion" button. [hideStrip] (e.g. while the keyboard is up) removes the strip but
 * keeps an open Hangar composed. Where the dock appears is the host's choice.
 */
@Composable
fun MechaDock(store: MechaStore, reading: Boolean = false, modifier: Modifier = Modifier, wander: Any? = null, hideStrip: Boolean = false) {
    val view by store.view.collectAsState()
    val state = view.state
    var hangar by rememberSaveable { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    val focusBack = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val close = {
        hangar = false
        refocus = true
    }
    // A controller user lands back on the button that opened the Hangar once its window is gone (the screen above keeps its
    // own scroll position and state; nothing there was recomposed away). Touch users keep touch mode, nothing is focused.
    LaunchedEffect(refocus) {
        if (!refocus) return@LaunchedEffect
        // after the dialog window has been removed and the main window has its focus back
        delay(60)
        if (inputMode.inputMode == InputMode.Keyboard) runCatching { focusBack.requestFocus() }
        refocus = false
    }
    val box = mechaSize(DOCK_SPRITE)
    // Keep the dialog composed while its rename field opens the IME; only the underlying strip disappears.
    if (!hideStrip) {
        when {
            state == null && view.error == null -> Spacer(modifier.fillMaxWidth().height(box.height)) // first read of the hangar file
            state == null -> DockError(view.error.orEmpty(), box, onHangar = { hangar = true }, focus = focusBack, modifier = modifier)
            !state.enabled -> Row(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                MechaButton("Show companion", onClick = { store.setEnabled(true) }, modifier = Modifier.focusRequester(focusBack))
            }
            else -> DockStrip(view, state, reading, wander, box, onHangar = { hangar = true }, focus = focusBack, modifier = modifier)
        }
    }
    if (hangar) Hangar(store, onClose = close)
}

@Composable
private fun DockError(error: String, box: DpSize, onHangar: () -> Unit, focus: FocusRequester, modifier: Modifier) {
    Row(modifier.fillMaxWidth().height(box.height).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Companion can't load: $error",
            style = MaterialTheme.typography.labelMedium,
            color = Warning,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        MechaButton("Hangar", onClick = onHangar, modifier = Modifier.padding(start = 8.dp).focusRequester(focus))
    }
}

@Composable
private fun DockStrip(
    view: MechaView,
    state: MechaState,
    reading: Boolean,
    wander: Any?,
    box: DpSize,
    onHangar: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier
) {
    val message = view.message
    val cheering = rememberCheer(state)
    // the message from an action taken in the Hangar shows for a while; one already there when the dock appears does not
    val firstMessage = remember { message }
    var showMessage by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        if (message == null || message == firstMessage) return@LaunchedEffect
        showMessage = true
        delay(MESSAGE_MS)
        showMessage = false
    }
    var walking by remember { mutableStateOf(false) }
    val pose = when {
        cheering && !state.sleeping -> MechaPose.CELEBRATE
        walking -> MechaPose.WALK
        state.sleeping || reading -> MechaPose.SLEEP
        else -> MechaPose.IDLE
    }
    val status = when {
        view.error != null -> view.error
        showMessage && message != null -> message
        state.sleeping -> "Resting in the hangar"
        reading -> "Dozing while you read"
        state.salvage == 1 -> "1 salvage credit to spend"
        state.salvage > 1 -> "${state.salvage} salvage credits to spend"
        else -> "${state.minutesToNextCredit} min of play to the next credit"
    }
    Row(modifier.fillMaxWidth().height(box.height).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Lane(
            view = view,
            equipped = state.equipped,
            pose = pose,
            motion = state.motion,
            canStroll = state.motion && !reading && !state.sleeping,
            wander = wander,
            box = box,
            onWalking = { walking = it },
            onClick = onHangar,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        Column(Modifier.widthIn(max = 280.dp).padding(start = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(state.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (view.error != null) Warning else Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MechaButton("Hangar", onClick = onHangar, modifier = Modifier.padding(start = 10.dp).focusRequester(focus))
    }
}

/**
 * The floor the mech stands on: a one-sprite-pixel ledge under its feet and the mech at a whole-pixel x. Strolls move it in
 * [STRIDE_PX] steps timed with the walk frames; the position is read only at placement, so a step re-places one box and
 * recomposes nothing.
 */
@Composable
private fun Lane(
    view: MechaView,
    equipped: Map<MechaSlot, String>,
    pose: MechaPose,
    motion: Boolean,
    canStroll: Boolean,
    wander: Any?,
    box: DpSize,
    onWalking: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier
) {
    BoxWithConstraints(
        modifier.drawBehind {
            // the sprite stands on the lane's bottom edge (see MechaSprite); its feet are outlined on frame row 62, the ledge
            // is the last row, under them
            val pixel = (size.height.toInt() / (MECHA_FRAME + MECHA_HEADROOM)).coerceAtLeast(1).toFloat()
            val top = size.height - pixel
            drawRect(Hairline, Offset(0f, top), Size(size.width, pixel))
        }
    ) {
        val boxPx = with(LocalDensity.current) { box.width.roundToPx() }
        val room = (constraints.maxWidth - boxPx).coerceAtLeast(0)
        val pixel = (boxPx / MECHA_FRAME).coerceAtLeast(1)
        // -1 until the first stroll: stand near the left end of whatever room the lane has
        val x = remember { mutableIntStateOf(-1) }
        val at = { if (x.intValue < 0) (room * 0.12f).roundToInt() else x.intValue.coerceAtMost(room) }
        val strolls = remember { Channel<Unit>(Channel.CONFLATED) }
        val walking by rememberUpdatedState(onWalking)
        val allowed by rememberUpdatedState(canStroll)
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(wander) { if (wander != null) strolls.trySend(Unit) }
        LaunchedEffect(room, lifecycle, canStroll) {
            if (!canStroll) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                launch {
                    while (true) {
                        delay(Random.nextLong(45_000, 90_000))
                        strolls.trySend(Unit)
                    }
                }
                for (ignored in strolls) {
                    if (!allowed || room < pixel * STRIDE_PX * 6) continue
                    val from = at()
                    var target = Random.nextInt(0, room + 1)
                    if (abs(target - from) < room / 4) target = if (from > room / 2) from - room / 3 else from + room / 3
                    target = target.coerceIn(0, room)
                    val stride = pixel * STRIDE_PX * if (target > from) 1 else -1
                    walking(true)
                    try {
                        var pos = from
                        while (abs(target - pos) >= abs(stride) && allowed) {
                            delay(STEP_MS)
                            pos += stride
                            x.intValue = pos
                        }
                    } finally {
                        walking(false)
                    }
                }
            }
        }
        MechaSprite(
            view.parts,
            equipped,
            pose,
            motion,
            Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(at(), 0) }
                .size(box)
                .focusProperties { canFocus = false }
                .clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Open the Hangar", onClick = onClick)
        )
    }
}

/** True for [CHEER_MS] after the mech found a part, was cared for or renamed (compared with the state it had before). */
@Composable
internal fun rememberCheer(state: MechaState): Boolean {
    var cheering by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(state) }
    LaunchedEffect(state) {
        val before = last
        last = state
        if (before === state) return@LaunchedEffect
        val reacted = state.owned.size > before.owned.size || state.bond > before.bond || state.name != before.name
        if (!reacted) return@LaunchedEffect
        cheering = true
        delay(CHEER_MS)
        cheering = false
    }
    return cheering
}
