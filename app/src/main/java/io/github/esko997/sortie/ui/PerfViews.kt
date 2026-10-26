package io.github.esko997.sortie.ui

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.stats.PerfFrame
import io.github.esko997.sortie.stats.PerfMetric
import io.github.esko997.sortie.stats.Sparkline
import io.github.esko997.sortie.stats.StatsText
import io.github.esko997.sortie.stats.ZoneGroup
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Perf page of the switcher overlay, in either window. The controller window scrolls it with the D-pad (the page holds
 * the focus); B, L1 or R1 go back to the app list through the overlay's key handling ([SwitcherHost]), and the "Apps" chip
 * does it by touch.
 */
@Composable
fun OverlayPerf(controller: Boolean, onApps: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    OverlayPerfPage(livePerfFrame(graph), controller, onApps)
}

/** [OverlayPerf] for a given [frame]. */
@Composable
fun OverlayPerfPage(frame: PerfFrame?, controller: Boolean, onApps: () -> Unit) {
    val list = rememberLazyListState()
    val title: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Performance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            PerfChip("Apps", onApps)
        }
    }
    if (!controller) {
        PerfPage(frame, Modifier.fillMaxSize(), PaddingValues(16.dp), list, title)
        return
    }
    ControllerPerfPage(frame, list, title, "↑↓" to "scroll", "B" to "apps", "R1" to "apps")
}

/**
 * The Performance app (Apps, the pickers): the same page in its own window on either screen, polled only while that window
 * is in front on a lit display ([livePerfFrame]). The D-pad scrolls it and B closes it; the window's hint bar offers moving it
 * to the other screen and closing it by touch ([LocalWindowActions]).
 */
@Composable
fun PerformanceApp() {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    ControllerPerfPage(
        livePerfFrame(graph),
        rememberLazyListState(),
        { Text("Performance", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp)) },
        "↑↓" to "scroll",
        "B" to "close"
    )
}

/** [PerfPage] holding the controller focus, scrolled by the D-pad, over a hint bar of [hints]. */
@Composable
private fun ControllerPerfPage(frame: PerfFrame?, list: LazyListState, title: @Composable () -> Unit, vararg hints: Pair<String, String>) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 120.dp.toPx() }
    val inputMode = LocalInputModeManager.current
    // The window may get its focus a little after the page appears (as the app list's cards do): keep asking until it holds.
    LaunchedEffect(Unit) {
        repeat(50) {
            if (focused) return@LaunchedEffect
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { requester.requestFocus() }
            delay(40)
        }
    }
    Column(Modifier.fillMaxSize()) {
        PerfPage(
            frame,
            Modifier.weight(1f)
                .focusRequester(requester)
                .onFocusChanged { focused = it.hasFocus }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || !focused) return@onPreviewKeyEvent false
                    val by = when (e.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> -step
                        KeyEvent.KEYCODE_DPAD_DOWN -> step
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { list.animateScrollBy(by) }
                    true
                }
                .focusable(),
            PaddingValues(16.dp),
            list,
            title
        )
        HintBar(*hints)
    }
}

/**
 * The companion screen's Perf mode (`user.companionView`): the same page, laid out around the panel's corner controls. The
 * title sits between the battery and the bell; the end of the page scrolls clear of the dock and '+' below.
 */
@Composable
fun CompanionPerf() {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    CompanionPerfPage(livePerfFrame(graph))
}

/** [CompanionPerf] for a given [frame]. */
@Composable
fun CompanionPerfPage(frame: PerfFrame?) {
    PerfPage(frame, Modifier.fillMaxSize(), PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 76.dp)) {
        Box(Modifier.fillMaxWidth().height(44.dp).padding(bottom = 4.dp), contentAlignment = Alignment.Center) {
            Text("Performance", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Large numbers with a two-minute sparkline each, then the details: every core's clock and every thermal zone, grouped. A
 * metric the device never reported in the window gets no tile; the details appear once the first detailed poll is in.
 * Stateless: everything comes from [frame], so it renders the same for the same frame (screenshot tests). A lazy list, so a
 * poll recomposes only what is on screen: the Thor reports some 60 zones, mostly below the fold.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerfPage(
    frame: PerfFrame?,
    modifier: Modifier,
    contentPadding: PaddingValues,
    list: LazyListState = rememberLazyListState(),
    title: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val side = contentPadding.calculateLeftPadding(LayoutDirection.Ltr) + contentPadding.calculateRightPadding(LayoutDirection.Ltr)
        val columns = ((maxWidth - side) / TILE_MIN_WIDTH).toInt().coerceIn(2, 5)
        val metrics = remember(frame) { frame?.let { f -> PerfMetric.entries.filter(f.history::has) }.orEmpty() }
        val groups = remember(frame?.stats?.zones) { frame?.stats?.zones?.let(ZoneGroup::of).orEmpty() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding) {
            item(key = "title") { title() }
            if (frame == null) {
                item(key = "reading") { Text("Reading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                return@LazyColumn
            }
            // rows of equal weights rather than a FlowRow of computed widths, which rounding can push onto the next line
            itemsIndexed(metrics.chunked(columns), key = { i, _ -> "tiles-$i" }) { i, row ->
                Row(Modifier.padding(top = if (i > 0) TILE_GAP else 0.dp), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    for (m in row) MetricTile(frame, m, Modifier.weight(1f))
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (PerfMetric.CPU_LOAD !in metrics && frame.stats.zones == null) {
                item(key = "shizuku") {
                    Text(
                        "CPU, GPU, temperatures and the fan need Shizuku (Settings → Diagnostics).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
            frame.stats.coreKhz?.let { cores ->
                item(key = "cores") {
                    Column {
                        SectionLabel("Core clocks · GHz")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in cores) Chip("cpu${c.core}", String.format(Locale.US, "%.2f", c.khz / 1_000_000.0))
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) {
                item(key = "zones") { SectionLabel("Thermal zones · °C") }
                items(groups, key = { "zone-${it.name}" }) { ZoneRow(it, Modifier.padding(bottom = 8.dp)) }
            }
        }
    }
}

/** One metric: its name, the current value large with its unit, and the line of the last two minutes under it. */
@Composable
private fun MetricTile(frame: PerfFrame, metric: PerfMetric, modifier: Modifier) {
    val values = frame.history.values(metric)
    val line = remember(frame, metric) {
        Sparkline.of(frame.history.times, values, Sparkline.range(metric, values, frame.stats))
    }
    val now = metric.read(frame.stats)
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.clip(shape).background(Slate).border(1.dp, Hairline, shape).padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {}
    ) {
        Text(metric.label, style = MaterialTheme.typography.labelMedium, color = Amber.copy(alpha = 0.85f), maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // fixed-width digits: a reading that changes every poll keeps its place instead of shuffling sideways
            Text(
                now?.let(metric::big) ?: "—",
                style = LocalTextStyle.current.merge(Tabular),
                fontSize = 28.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                metric.unit(frame.stats),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        SparklineView(line, Modifier.fillMaxWidth().height(30.dp))
    }
}

/** Figures of equal width, for numbers that change in place. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** The line, broken where a poll had no value, over a faint baseline; the newest point is a dot at the right edge. */
@Composable
private fun SparklineView(line: Sparkline, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawLine(Ink, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1f)
        val path = Path()
        var drawing = false
        var last: Offset? = null
        for (i in line.xs.indices) {
            val y = line.ys[i]
            if (y.isNaN()) {
                drawing = false
                continue
            }
            val p = Offset(line.xs[i] * w, h - y * h)
            if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            drawing = true
            last = p
        }
        drawPath(path, Amber, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        last?.let { drawCircle(Amber, radius = 2.5.dp.toPx(), center = it) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/** A small label over a value, for the per-core clocks and the zones. */
@Composable
private fun Chip(label: String, value: String) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Slate).padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 11.sp, color = Amber.copy(alpha = 0.8f), maxLines = 1)
        Text(
            value,
            style = LocalTextStyle.current.merge(Tabular),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** A zone group: its name and hottest reading, then each zone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoneRow(group: ZoneGroup, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (group.name == ZoneGroup.OTHER) group.name else "${group.name} · up to ${StatsText.celsius(group.hottest)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (z in group.zones) Chip(group.short(z), z.celsius.toString())
        }
    }
}

/** A small pill that opens or leaves the Perf page; it glows while it has the controller focus and sinks while A is held. */
@Composable
fun PerfChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    var held by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .confirmHeld { held = it }
            .focusGlow(interaction.collectIsFocusedAsState().value, shape, pressed = held)
            .clip(shape)
            .background(Slate)
            .clickable(interactionSource = interaction, indication = PressVeil, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Amber)
    }
}

/** Below this a tile's large number and unit start to crowd; the page uses as many columns as fit, two to five. */
private val TILE_MIN_WIDTH = 140.dp

private val TILE_GAP = 8.dp
