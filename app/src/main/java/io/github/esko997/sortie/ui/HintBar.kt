package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.mecha.ui.mechaObstacle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller hints wrap as whole button/action pairs, never as narrow columns of letters. In a built-in app's own window
 * ([LocalWindowActions]; not in its dialogs) the bar ends with that window's Other screen and Close, for touch. A RuleColour
 * along its top edge sets the bar off from the content above it.
 */
@Composable
fun HintBar(vararg hints: Pair<String, String>, modifier: Modifier = Modifier) {
    val window = LocalWindowActions.current?.takeIf { it.root === LocalView.current.rootView }
    val rule = RuleColour
    val bar = modifier.fillMaxWidth()
        .background(BarColour)
        .drawBehind { drawRect(rule, size = Size(size.width, 1.dp.toPx())) }
        .padding(horizontal = 14.dp, vertical = 6.dp)
    if (window == null) {
        Hints(hints, bar)
        return
    }
    Row(bar, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Hints(hints, Modifier.weight(1f))
        WindowActionChips(window)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hints(hints: Array<out Pair<String, String>>, modifier: Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hints.forEach { (button, action) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                KeyCap(button)
                Text(action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * A controller button as every hint shows it: its name on a small raised cap, at least as wide as it is tall so single letters
 * read as buttons. Opaque enough to hold its contrast over artwork in the browse bar's fade.
 */
@Composable
private fun KeyCap(button: String) {
    Text(
        button,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.clip(KeyCapShape).background(KeyCapColour).widthIn(min = 20.dp).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private val KeyCapShape = RoundedCornerShape(5.dp)

/**
 * Browsing chrome stays in the layout, so a focused final row is never hidden under the hints. Compact mode keeps the
 * common commands on one line; Controls retains every command without a tall footer. A light fade leaves the backdrop
 * visible. Each hint's label (not the free rest of its share of the bar) and Controls are obstacles the companion keeps
 * clear of.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseHintBar(vararg hints: Pair<String, String>, modifier: Modifier = Modifier, compact: Boolean = false) {
    val barKey = currentCompositeKeyHash
    val boundary = remember(barKey) { "browse:$barKey" }
    val canvas = CanvasColour
    val fade = remember(canvas) { Brush.verticalGradient(listOf(Color.Transparent, canvas.copy(alpha = 0.4f))) }
    val bar = modifier.fillMaxWidth().background(fade)
    if (!compact) {
        FlowRow(
            bar.padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            hints.forEachIndexed { i, (button, action) -> BrowseHint("$boundary:hint:$i", button, action) }
        }
        return
    }
    var controls by remember { mutableStateOf(false) }
    val opener = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val controlInteraction = remember { MutableInteractionSource() }
    val controlsFocused by controlInteraction.collectIsFocusedAsState()
    val controlsPressed by controlInteraction.collectIsPressedAsState()
    var restoreFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(controls, windowFocused) {
        if (!controls && restoreFocus && windowFocused) {
            restoreFocus = false
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            opener.requestFocus()
        }
    }
    BoxWithConstraints(bar) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val width = maxWidth / fontScale
        val count = when {
            width >= 620.dp -> 3
            width >= 380.dp -> 2
            width >= 240.dp -> 1
            else -> 0
        }
        val essentials = listOf("A", "B", "Y", "Select").mapNotNull { key -> hints.firstOrNull { it.first == key } }.take(count)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // the hints sit together at the start, so the bar's free stretch before Controls is one open run (the companion's
            // walkway on Home)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                essentials.forEachIndexed { i, (button, action) -> BrowseHint("$boundary:hint:$i", button, action) }
            }
            TextButton(
                onClick = { controls = true },
                interactionSource = controlInteraction,
                shape = ControlsShape,
                modifier = Modifier.focusRequester(opener).heightIn(min = 48.dp)
                    .focusGlow(controlsFocused, ControlsShape, pressed = controlsPressed)
                    .mechaObstacle("$boundary:controls", interactive = true)
            ) { Text("Controls", style = MaterialTheme.typography.labelLarge, color = Accent, maxLines = 1) }
        }
    }
    if (controls) {
        BrowseControls(hints.toList()) {
            restoreFocus = it
            controls = false
        }
    }
}

/** The compact bar's Controls button: its touch ripple and its controller glow share this shape. */
private val ControlsShape = RoundedCornerShape(10.dp)

/** The Controls dialog: the same card as the context sheets. */
private val DialogShape = RoundedCornerShape(16.dp)

/** Each bar owns its label boundaries, including while outgoing and incoming screens overlap. */
@Composable
private fun BrowseHint(id: String, button: String, action: String) {
    Row(Modifier.mechaObstacle(id), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyCap(button)
        Text(
            action,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BrowseControls(hints: List<Pair<String, String>>, onDismiss: (Boolean) -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 72.dp.toPx() }
    val close = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
    Dialog(onDismissRequest = { onDismiss(controller) }) {
        val inputMode = LocalInputModeManager.current
        val keyboard = inputMode.inputMode == InputMode.Keyboard
        SideEffect { controller = keyboard }
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().heightIn(max = maxHeight)
                .clip(DialogShape).background(PanelColour).border(1.dp, RuleColour, DialogShape).padding(16.dp)
                .onPreviewKeyEvent { event ->
                    val direction = when (event.key) {
                        Key.DirectionDown, Key.PageDown -> 1
                        Key.DirectionUp, Key.PageUp -> -1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (event.type == KeyEventType.KeyDown) {
                        scope.launch {
                            scroll.scrollTo((scroll.value + (step * direction).toInt()).coerceIn(0, scroll.maxValue))
                        }
                    }
                    true
                },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Controls", style = MaterialTheme.typography.titleLarge)
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Tap or pull down the header strip to reveal it. With the controller, press Up from the first row.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                hints.forEach { (button, action) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.widthIn(min = 72.dp)) { KeyCap(button) }
                        Text(action, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
            }
            FocusRow(onClick = {
                onDismiss(inputMode.inputMode == InputMode.Keyboard)
            }, modifier = Modifier.focusRequester(close).onFocusChanged { focused = it.isFocused }) {
                Text("Close", color = Accent)
            }
        }
        LaunchedEffect(Unit) {
            repeat(20) {
                if (focused) return@LaunchedEffect
                @OptIn(ExperimentalComposeUiApi::class)
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { close.requestFocus() }
                delay(40)
            }
        }
    }
}
