package io.github.esko997.sortie.mecha.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.mecha.MechaArcadeGame
import io.github.esko997.sortie.mecha.MechaArcadePhase
import io.github.esko997.sortie.mecha.MechaArcadeSession
import io.github.esko997.sortie.mecha.MechaCircuitEngine
import io.github.esko997.sortie.mecha.MechaFlightEngine
import io.github.esko997.sortie.mecha.MechaScrapEngine
import io.github.esko997.sortie.mecha.MechaState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaView
import io.github.esko997.sortie.ui.Accent
import io.github.esko997.sortie.ui.CanvasColour
import io.github.esko997.sortie.ui.PanelColour
import io.github.esko997.sortie.ui.RuleColour
import io.github.esko997.sortie.ui.focusGlow
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive

private val TOOL_NAMES = arrayOf("Bolt", "Gear", "Fuse", "Wrench", "Drill", "Spring")
private val TOOL_GLYPHS = arrayOf(
    MechaGlyph(listOf("..XXX..", "..XXX..", ".XXXXX.", "...X...", "..XX...", "...XX..", "..XX...")),
    MechaGlyph(listOf("..XXX..", "X.XXX.X", ".XXXXX.", "XXX.XXX", ".XXXXX.", "X.XXX.X", "..XXX..")),
    MechaGlyph(listOf("..XXX..", "..XXX..", "..X.X..", "..XXX..", "..X.X..", "..XXX..", "..XXX..")),
    MechaGlyph(listOf("X...X..", "XX.XX..", ".XXX...", "..XX...", "...XX..", "....XX.", ".....XX")),
    MechaGlyph(listOf(".......", ".XXXX..", ".XXXXXX", ".XXXX..", "..XX...", "..XX...", ".XXX...")),
    MechaGlyph(listOf(".XXXXX.", ".....X.", ".XXXXX.", ".X.....", ".XXXXX.", ".....X.", ".XXXXX."))
)

/** One committed coin buys one chosen run; no game touches the wallet or library play-history accounting. */
@Composable
internal fun MechaArcade(store: MechaStore, view: MechaView, state: MechaState, backRequest: Int = 0, onBack: () -> Unit = {}) {
    val session = store.arcadeSession
    val run by session.run.collectAsState()
    val host = remember { Any() }
    val spriteLoad = rememberSpriteLoad(view.parts, state.equipped, state.equippedPaints)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var visible by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var confirmingEntry by remember { mutableStateOf(false) }
    var confirmingAbandon by remember { mutableStateOf(false) }
    // The parent resets this counter on entry. A rapid A → B can arrive before our first composition.
    var lastBack by remember { mutableIntStateOf(0) }
    val redraw = remember { mutableIntStateOf(0) }
    var score by remember { mutableIntStateOf(session.score) }
    var seconds by remember { mutableIntStateOf(session.scrap?.secondsLeft ?: 45) }
    var cell by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    var playFocused by remember { mutableStateOf(false) }
    val cancelFocus = remember { FocusRequester() }
    val runningHere = run.phase == MechaArcadePhase.RUNNING && session.owns(host)
    val game = run.ticket?.game
    val needsFrames = session.needsFrames

    LaunchedEffect(state.activeArcade) {
        session.sync(store.view.value.state?.activeArcade)
        if (state.activeArcade != null) confirmingEntry = false
        if (state.activeArcade == null) confirmingAbandon = false
        score = session.score
        seconds = session.scrap?.secondsLeft ?: 45
    }
    DisposableEffect(lifecycle, session, host) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!foreground) session.pause(host)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            session.pause(host)
        }
    }
    LaunchedEffect(windowFocused, visible, foreground) {
        if (!windowFocused || !visible || !foreground) session.pause(host)
    }
    LaunchedEffect(backRequest) {
        if (lastBack == backRequest) return@LaunchedEffect
        lastBack = backRequest
        when {
            confirmingAbandon -> confirmingAbandon = false
            confirmingEntry -> confirmingEntry = false
            session.owns(host) -> session.pause(host)
            else -> onBack()
        }
    }
    LaunchedEffect(run.ticket, run.phase, run.revision) {
        score = session.score
        redraw.intValue++
        if (run.phase == MechaArcadePhase.FINISHED && session.claimFinish()) {
            run.ticket?.let { store.finishArcade(it, session.score) }
        }
    }
    LaunchedEffect(runningHere) {
        if (runningHere) playFocus.requestFocus()
    }
    LaunchedEffect(runningHere, windowFocused, visible, foreground, needsFrames) {
        if (!runningHere || !windowFocused || !visible || !foreground || !needsFrames) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (isActive && session.owns(host) && session.needsFrames) {
            val now = withFrameNanos { it }
            session.advance(host, now - previous)
            previous = now
            redraw.intValue++
            if (score != session.score) score = session.score
            val remaining = session.scrap?.secondsLeft ?: 45
            if (seconds != remaining) seconds = remaining
        }
    }
    val resume = {
        if (foreground && visible && windowFocused && !view.busy && spriteLoad is SpriteLoad.Ready) session.resume(host)
        Unit
    }
    val activate = {
        when (game) {
            MechaArcadeGame.FLAPPY_FLIGHT -> session.flap(host)
            MechaArcadeGame.CIRCUIT_MATCH -> session.choose(host, cell)
            else -> Unit
        }
    }
    val controls: @Composable (Boolean) -> Unit = { compact ->
        val scroll = rememberScrollState()
        LaunchedEffect(run.phase, confirmingEntry, confirmingAbandon) { scroll.scrollTo(0) }
        val inputMode = LocalInputModeManager.current.inputMode
        LaunchedEffect(confirmingEntry, confirmingAbandon, inputMode) {
            if ((confirmingEntry || confirmingAbandon) && inputMode == InputMode.Keyboard) cancelFocus.requestFocus()
        }
        val instructions: @Composable () -> Unit = {
            if (game != null && !confirmingAbandon && run.phase != MechaArcadePhase.FINISHED && run.phase != MechaArcadePhase.SAVED) {
                Text(
                    when (game) {
                        MechaArcadeGame.FLAPPY_FLIGHT ->
                            "Tap or press A to flap. Pass through gaps. Pipes, ceiling and floor end the run."
                        MechaArcadeGame.SCRAP_CATCH ->
                            "45 seconds. Catch bolts +10; avoid junk −5. " +
                                "Hold left/right on the D-pad, drag the tray, or use the buttons."
                        MechaArcadeGame.CIRCUIT_MATCH ->
                            "Find six tool pairs in 24 attempts. D-pad selects a card; A or tap turns it. " +
                                "Matched pairs score 10; finish for +5 per unused attempt."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(scroll)) {
            if (!compact || game == null) {
                Text(
                    if (run.phase == MechaArcadePhase.CHOOSING) "Choose your game" else game?.label ?: "Arcade",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!compact) instructions()
            if (game != null) {
                val progress = when (game) {
                    MechaArcadeGame.SCRAP_CATCH -> " · ${seconds}s"
                    MechaArcadeGame.CIRCUIT_MATCH -> session.circuit?.let { " · ${it.pairs}/6 pairs · ${it.attempts}/24" }.orEmpty()
                    else -> ""
                }
                if (compact) {
                    Text("Score $score   Best ${state.arcadeBest[game] ?: 0}$progress", style = MaterialTheme.typography.labelLarge)
                } else {
                    Text("Score $score   Best ${state.arcadeBest[game] ?: 0}", style = MaterialTheme.typography.titleMedium)
                    if (game == MechaArcadeGame.SCRAP_CATCH) Text("$seconds seconds left", style = MaterialTheme.typography.labelLarge)
                    session.circuit?.let { Text("${it.pairs}/6 pairs · ${it.attempts}/24 attempts", style = MaterialTheme.typography.labelLarge) }
                }
            }
            when {
                confirmingAbandon -> {
                    Text("Abandon this paid run? The entry credit is not refunded and this score will not be saved.")
                    MechaButton("Keep run", { confirmingAbandon = false }, Modifier.focusRequester(cancelFocus), enabled = !view.busy)
                    MechaButton("Abandon run", { run.ticket?.let(store::abandonArcade) }, enabled = !view.busy && !runningHere)
                }
                confirmingEntry -> {
                    Text("Spend 1 credit? Choose one game after your coin is saved. Abandoning does not refund it.")
                    MechaButton("Cancel", { confirmingEntry = false }, Modifier.focusRequester(cancelFocus), enabled = !view.busy)
                    MechaButton("Spend 1 credit", { store.insertArcadeCoin(state.arcadeRuns) }, enabled = !view.busy, primary = true)
                }
                run.phase == MechaArcadePhase.CHOOSING -> {
                    Text(
                        "Coin saved. Choose one game; the choice locks this run. No extra charge.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    MechaArcadeGame.entries.forEach { choice ->
                        MechaButton(
                            choice.label,
                            { run.ticket?.let { store.selectArcadeGame(it, choice) } },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !view.busy,
                            primary = true
                        )
                    }
                    MechaButton("Abandon…", { confirmingAbandon = true }, Modifier.heightIn(min = 48.dp), enabled = !view.busy)
                }
                run.phase == MechaArcadePhase.RUNNING -> {
                    if (runningHere) {
                        if (compact) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (game == MechaArcadeGame.SCRAP_CATCH) {
                                    MechaButton("Left", { session.moveTray(host, (session.scrap?.tray ?: 0.5f) - 0.12f) }, Modifier.heightIn(min = 48.dp))
                                    MechaButton("Right", { session.moveTray(host, (session.scrap?.tray ?: 0.5f) + 0.12f) }, Modifier.heightIn(min = 48.dp))
                                } else {
                                    MechaButton(
                                        if (game == MechaArcadeGame.FLAPPY_FLIGHT) "Flap · A" else "Turn card · A",
                                        activate,
                                        Modifier.weight(1f).heightIn(min = 48.dp),
                                        primary = true
                                    )
                                }
                                MechaButton("Pause", { session.pause(host) }, Modifier.heightIn(min = 48.dp))
                            }
                        } else {
                            if (game == MechaArcadeGame.SCRAP_CATCH) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MechaButton("Left", { session.moveTray(host, (session.scrap?.tray ?: 0.5f) - 0.12f) }, Modifier.heightIn(min = 48.dp))
                                    MechaButton("Right", { session.moveTray(host, (session.scrap?.tray ?: 0.5f) + 0.12f) }, Modifier.heightIn(min = 48.dp))
                                }
                            } else {
                                MechaButton(
                                    if (game == MechaArcadeGame.FLAPPY_FLIGHT) "Flap · A" else "Turn card · A",
                                    activate,
                                    Modifier.heightIn(min = 48.dp).fillMaxWidth(),
                                    primary = true
                                )
                            }
                            MechaButton("Pause", { session.pause(host) })
                        }
                        Text("B pauses. Leaving this page also pauses the run.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("This run is playing in another window. Pause or close that window to resume here.")
                    }
                }
                run.phase == MechaArcadePhase.PAUSED -> {
                    val fresh = session.elapsedNanos == 0L && (session.circuit?.first ?: -1) < 0 && (session.circuit?.attempts ?: 0) == 0
                    Text(if (fresh) "Your paid run is ready." else "Paused. Your game is kept here.")
                    if (compact) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MechaButton(
                                if (fresh) "Start paid run" else "Resume game",
                                resume,
                                Modifier.heightIn(min = 48.dp),
                                enabled = !view.busy && spriteLoad is SpriteLoad.Ready,
                                primary = true
                            )
                            MechaButton("Abandon…", { confirmingAbandon = true }, Modifier.heightIn(min = 48.dp), enabled = !view.busy)
                        }
                    } else {
                        MechaButton(
                            if (fresh) "Start paid run" else "Resume game",
                            resume,
                            enabled = !view.busy && spriteLoad is SpriteLoad.Ready,
                            primary = true
                        )
                        MechaButton("Abandon…", { confirmingAbandon = true }, enabled = !view.busy)
                    }
                    Text(
                        "Resuming is free. After an app restart, this same paid run starts over with the same seed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                run.phase == MechaArcadePhase.FINISHED -> {
                    Text("Run ended · $score points")
                    Text(if (view.busy) "Saving your best…" else "This result is not saved yet. Retry to keep your best.")
                    MechaButton("Retry save", { run.ticket?.let { store.finishArcade(it, session.score) } }, enabled = !view.busy, primary = true)
                }
                else -> {
                    Text("1 credit buys one chosen game. No credit or part prizes.", style = MaterialTheme.typography.bodySmall)
                    if (run.phase == MechaArcadePhase.SAVED) Text("Result saved · $score points. No credits or parts awarded.")
                    Text("${state.salvage} credits available", style = MaterialTheme.typography.bodyMedium)
                    MechaButton("Insert coin · 1 credit", {
                        confirmingEntry = true
                    }, enabled = !view.busy && state.activeArcade == null && state.salvage >= 1 && state.arcadeRuns < Int.MAX_VALUE, primary = true)
                }
            }
            if (view.error != null) Text(view.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (compact) instructions()
        }
    }
    val screen: @Composable (Modifier, Boolean) -> Unit = { modifier, compactBezel ->
        ArcadeScreen(
            session, state, score, run.phase, spriteLoad, cell, { redraw.intValue },
            compactBezel = compactBezel,
            onCell = { index ->
                if (runningHere) {
                    cell = index
                    session.choose(host, index)
                }
            },
            modifier = modifier
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    visible = bounds.width > 0f && bounds.height > 0f
                }
                .focusRequester(playFocus)
                .onFocusChanged {
                    playFocused = it.hasFocus
                    if (!it.hasFocus) session.steer(host, 0)
                }
                .focusGlow(playFocused, RoundedCornerShape(16.dp))
                .onPreviewKeyEvent { event ->
                    val key = event.nativeKeyEvent
                    if (!runningHere) {
                        false
                    } else {
                        val down = key.action == AndroidKeyEvent.ACTION_DOWN
                        val action = key.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A || key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                            key.keyCode == AndroidKeyEvent.KEYCODE_SPACE || key.keyCode == AndroidKeyEvent.KEYCODE_ENTER
                        when {
                            action -> {
                                if (down && key.repeatCount == 0) activate()
                                true
                            }
                            game == MechaArcadeGame.SCRAP_CATCH && key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                                session.steer(host, if (down) -1 else 0)
                                true
                            }
                            game == MechaArcadeGame.SCRAP_CATCH && key.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                session.steer(host, if (down) 1 else 0)
                                true
                            }
                            game == MechaArcadeGame.CIRCUIT_MATCH && key.keyCode in AndroidKeyEvent.KEYCODE_DPAD_UP..AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                if (down) {
                                    cell = when (key.keyCode) {
                                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> (cell / 4) * 4 + (cell + 3) % 4
                                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> (cell / 4) * 4 + (cell + 1) % 4
                                        AndroidKeyEvent.KEYCODE_DPAD_UP -> (cell + 8) % 12
                                        else -> (cell + 4) % 12
                                    }
                                }
                                true
                            }
                            else -> false
                        }
                    }
                }
                .focusable()
                .semantics {
                    contentDescription = "${game?.label ?: "Arcade"} playfield"
                    stateDescription = "${run.phase.name.lowercase()}, score $score"
                    if (runningHere && game != MechaArcadeGame.SCRAP_CATCH) {
                        onClick(if (game == MechaArcadeGame.FLAPPY_FLIGHT) "Flap" else "Turn card") {
                            activate()
                            true
                        }
                    }
                },
            playModifier = Modifier.pointerInput(runningHere, game) {
                if (game == MechaArcadeGame.SCRAP_CATCH) {
                    detectDragGestures(onDragStart = { if (runningHere) session.moveTray(host, it.x / size.width) }) { change, _ ->
                        if (runningHere) {
                            change.consume()
                            session.moveTray(host, change.position.x / size.width)
                        }
                    }
                } else if (game == MechaArcadeGame.FLAPPY_FLIGHT) {
                    detectTapGestures { if (runningHere) session.flap(host) }
                }
            }
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = 10.dp)) {
        if (maxWidth >= 560.dp) {
            val leftWidth = (maxWidth - 18.dp) * 0.52f
            val screenWidth = minOf(leftWidth, 460.dp, (maxHeight - 82.dp).coerceAtLeast(0.dp) * 1.6f + 24.dp)
            val screenHeight = ((screenWidth - 24.dp).coerceAtLeast(0.dp) / 1.6f + 82.dp).coerceAtMost(maxHeight)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(0.52f), contentAlignment = Alignment.Center) {
                    screen(Modifier.width(screenWidth).height(screenHeight), false)
                }
                Box(Modifier.weight(0.48f)) { controls(false) }
            }
        } else if (
            game == null || confirmingEntry || confirmingAbandon ||
            run.phase == MechaArcadePhase.FINISHED || run.phase == MechaArcadePhase.SAVED
        ) {
            // The idle CRT is a preview, not the purchase interface: keep every choice beside it.
            val previewWidth = minOf(170.dp, (maxWidth - 12.dp) * 0.34f)
            val previewHeight = ((previewWidth - 16.dp).coerceAtLeast(0.dp) / 1.3f + 58.dp).coerceAtMost(maxHeight)
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                screen(Modifier.width(previewWidth).height(previewHeight), true)
                Box(Modifier.weight(1f)) { controls(true) }
            }
        } else {
            // The active CRT keeps the useful area; the first control row fits below it without scrolling.
            val screenHeightLimit = (maxHeight - 128.dp).coerceAtLeast(58.dp).coerceAtMost(maxHeight)
            val aspect = if (game == MechaArcadeGame.CIRCUIT_MATCH) 1.3f else 1.6f
            val screenWidth = minOf(maxWidth, (screenHeightLimit - 58.dp).coerceAtLeast(0.dp) * aspect + 16.dp)
            val screenHeight = ((screenWidth - 16.dp).coerceAtLeast(0.dp) / aspect + 58.dp).coerceAtMost(screenHeightLimit)
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                screen(Modifier.width(screenWidth).height(screenHeight), true)
                Box(Modifier.fillMaxWidth().weight(1f)) { controls(true) }
            }
        }
    }
}

/** Static CRT glass/rim; all motion comes from the single visible game's simulation clock. */
@Composable
private fun ArcadeScreen(
    session: MechaArcadeSession,
    state: MechaState,
    score: Int,
    phase: MechaArcadePhase,
    load: SpriteLoad,
    cell: Int,
    drawRevision: () -> Int,
    compactBezel: Boolean,
    onCell: (Int) -> Unit,
    modifier: Modifier,
    playModifier: Modifier
) {
    val accent = Accent
    val canvas = CanvasColour
    val panel = PanelColour
    val rule = RuleColour
    val secondary = MaterialTheme.colorScheme.secondary
    val error = MaterialTheme.colorScheme.error
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val onPanel = MaterialTheme.colorScheme.onSurface
    val observedRun by session.run.collectAsState()
    val game = observedRun.ticket?.game
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier.background(panel, shape).border(4.dp, rule, shape).padding(if (compactBezel) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactBezel) 4.dp else 6.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (compactBezel) 20.dp else 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                game?.label ?: if (compactBezel) "Rivet" else "Rivet arcade",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (!compactBezel || game != null) {
                Text(if (phase == MechaArcadePhase.RUNNING) "$score" else phase.name.lowercase(), style = MaterialTheme.typography.labelMedium)
            }
        }
        Box(
            Modifier.fillMaxWidth().weight(1f)
                .border(3.dp, rule, RoundedCornerShape(14.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(canvas)
                .then(playModifier)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRevision()
                val flight = session.engine
                val scrap = session.scrap
                val circuit = session.circuit
                if (flight != null) {
                    for (i in 0 until MechaFlightEngine.PIPE_COUNT) {
                        val left = flight.pipeX(i) * size.width
                        val width = MechaFlightEngine.PIPE_WIDTH * size.width
                        if (left > size.width || left + width < 0) continue
                        val topEnd = (flight.gapY(i) - MechaFlightEngine.GAP_HEIGHT / 2) * size.height
                        val bottomStart = (flight.gapY(i) + MechaFlightEngine.GAP_HEIGHT / 2) * size.height
                        drawRect(secondary, Offset(left, 0f), Size(width, topEnd))
                        drawRect(secondary, Offset(left, bottomStart), Size(width, size.height - bottomStart))
                        drawRect(accent, Offset(left - 2.dp.toPx(), topEnd - 5.dp.toPx()), Size(width + 4.dp.toPx(), 5.dp.toPx()))
                        drawRect(accent, Offset(left - 2.dp.toPx(), bottomStart), Size(width + 4.dp.toPx(), 5.dp.toPx()))
                    }
                }
                if (scrap != null) {
                    for (i in 0 until MechaScrapEngine.DROP_COUNT) {
                        if (!scrap.active(i)) continue
                        val x = scrap.x(i) * size.width
                        val y = scrap.y(i) * size.height
                        if (scrap.junk(i)) {
                            drawLine(error, Offset(x - 5.dp.toPx(), y - 5.dp.toPx()), Offset(x + 5.dp.toPx(), y + 5.dp.toPx()), 3.dp.toPx())
                            drawLine(error, Offset(x + 5.dp.toPx(), y - 5.dp.toPx()), Offset(x - 5.dp.toPx(), y + 5.dp.toPx()), 3.dp.toPx())
                        } else {
                            TOOL_GLYPHS[0].draw(this, accent, x.toInt() - 7, y.toInt() - 7, 2)
                        }
                    }
                    drawRect(
                        accent,
                        Offset((scrap.tray - MechaScrapEngine.TRAY_HALF) * size.width, MechaScrapEngine.TRAY_Y * size.height),
                        Size(MechaScrapEngine.TRAY_HALF * 2 * size.width, 5.dp.toPx())
                    )
                }
                if (circuit != null) drawCircuit(circuit, cell, accent, rule, secondary, onAccent, onPanel)
                if (load is SpriteLoad.Ready) {
                    val scale = max(1, (size.height * (if (circuit != null) 0.22f else 0.19f) / MECHA_FRAME).roundToInt())
                    val side = MECHA_FRAME * scale
                    val botX = when {
                        flight != null -> MechaFlightEngine.BOT_X
                        scrap != null -> scrap.tray
                        else -> 0.5f
                    }
                    val botY = when {
                        flight != null -> flight.y
                        scrap != null -> 0.90f
                        circuit != null -> 0.89f
                        else -> 0.5f
                    }
                    val reaction = (flight != null && flight.velocity < -0.15f) || (circuit != null && (circuit.reactionNanos > 0 || circuit.pairs == 6))
                    drawMechaFrame(
                        load.layers,
                        if (state.motion && reaction) 7 else 0,
                        (botX * size.width - side / 2).roundToInt(),
                        (botY * size.height - side / 2).roundToInt(),
                        scale,
                        mechaMaterialPhase(session.elapsedNanos / 1_000_000, state.motion)
                    )
                }
                drawLine(rule, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
            }
            if (session.circuit != null) key(observedRun.revision) { CircuitTouchTargets(session.circuit!!, onCell) }
            if (load is SpriteLoad.Failed) Text(load.reason, Modifier.align(Alignment.Center).padding(8.dp), color = error)
            if (load is SpriteLoad.Loading) Text("Loading companion…", Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.fillMaxWidth().height(if (compactBezel) 14.dp else 22.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(accent, 3.dp.toPx(), Offset(7.dp.toPx(), size.height / 2))
                val slitWidth = 32.dp.toPx()
                val slitLeft = size.width - slitWidth - 4.dp.toPx()
                repeat(4) { slit ->
                    val top = if (compactBezel) (2 + slit * 3).dp.toPx() else (3 + slit * 4).dp.toPx()
                    drawLine(rule, Offset(slitLeft, top), Offset(slitLeft + slitWidth, top), 2.dp.toPx())
                }
            }
            if (!compactBezel) {
                Text(
                    "Rivet",
                    Modifier.align(Alignment.CenterStart).padding(start = 18.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun DrawScope.drawCircuit(board: MechaCircuitEngine, cell: Int, accent: Color, rule: Color, matched: Color, onAccent: Color, onPanel: Color) {
    val width = size.width / 4
    val height = size.height * 0.78f / 3
    for (index in 0 until MechaCircuitEngine.CELLS) {
        val left = (index % 4) * width + 3.dp.toPx()
        val top = (index / 4) * height + 3.dp.toPx()
        val cardSize = Size(width - 6.dp.toPx(), height - 6.dp.toPx())
        drawRect(if (index == cell) accent else rule, Offset(left, top), cardSize)
        val ink = if (index == cell) onAccent else onPanel
        if (board.revealed(index)) {
            val glyph = TOOL_GLYPHS[board.symbol(index)]
            val scale = max(1, (minOf(cardSize.width, cardSize.height) / 10).toInt())
            glyph.draw(
                this,
                if (board.matched(index) && index != cell) matched else ink,
                (left + (cardSize.width - glyph.width * scale) / 2).toInt(),
                (top + (cardSize.height - glyph.height * scale) / 2).toInt(),
                scale
            )
        } else {
            drawRect(ink, Offset(left + cardSize.width / 2 - 2.dp.toPx(), top + cardSize.height / 2 - 2.dp.toPx()), Size(4.dp.toPx(), 4.dp.toPx()))
        }
    }
}

/** Separate semantic tap targets expose each card without making a second controller focus system. */
@Composable
private fun CircuitTouchTargets(board: MechaCircuitEngine, onCell: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxSize(0.78f)) {
        repeat(3) { row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                repeat(4) { column ->
                    val index = row * 4 + column
                    val label = if (board.revealed(index)) TOOL_NAMES[board.symbol(index)] else "Hidden tool"
                    Box(
                        Modifier.weight(1f).fillMaxSize()
                            .pointerInput(index, onCell) { detectTapGestures { onCell(index) } }
                            .semantics {
                                contentDescription = "Card ${index + 1}, $label"
                                stateDescription = if (board.matched(index)) "Matched" else if (board.revealed(index)) "Face up" else "Face down"
                                onClick("Turn card") {
                                    onCell(index)
                                    true
                                }
                            }
                    )
                }
            }
        }
    }
}
