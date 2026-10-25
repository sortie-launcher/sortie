package io.github.esko997.sortie.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.stats.CompanionView
import io.github.esko997.sortie.stats.DeviceStats
import io.github.esko997.sortie.stats.DeviceStatsMode
import io.github.esko997.sortie.stats.PerfFrame
import io.github.esko997.sortie.stats.StatCell
import io.github.esko997.sortie.stats.StatsText
import io.github.esko997.sortie.stats.displayLit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The companion screen with device stats down both sides when `user.deviceStats` asks for them: load (CPU, GPU, RAM) in a
 * thin strip on the left, heat (temperatures, fan) on the right. The panel is narrowed by the strips rather than covered, so
 * its corner controls move in with it. Strips keep their width while a reading is missing; cells appear as readings arrive.
 * [content] stays at the same place in the composition whether the strips are shown or not, so toggling the setting on the
 * other screen keeps the panel's state.
 */
@Composable
fun DeviceStatsSides(content: @Composable () -> Unit) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    // the Perf companion shows all of it, larger: no strips beside it
    val on = rememberStatsMode(graph).companion && rememberCompanionView(graph) != CompanionView.PERF
    Row(Modifier.fillMaxSize().background(Ink)) {
        val stats = if (on) liveDeviceStats(graph) else null
        if (on) StatsStrip(stats?.let(StatsText::usageCells).orEmpty(), dividerAtEnd = true)
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        if (on) StatsStrip(stats?.let(StatsText::heatCells).orEmpty(), dividerAtEnd = false)
    }
}

/** One line of device stats for the switcher overlay's header; nothing unless `user.deviceStats` includes the overlay. */
@Composable
fun DeviceStatsHeader(modifier: Modifier = Modifier) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    if (!rememberStatsMode(graph).overlay) return
    val stats = liveDeviceStats(graph) ?: return
    val line = remember(stats) { StatsText.line(stats) }
    if (line.isEmpty()) return
    Text(
        line,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Composable
private fun rememberStatsMode(graph: SortieApp.Graph): DeviceStatsMode {
    val flow = remember(graph) { graph.user.data.map { DeviceStatsMode.of(it.deviceStats) }.distinctUntilChanged() }
    return flow.collectAsState(DeviceStatsMode.of(graph.user.data.value.deviceStats)).value
}

/** What the companion screen shows (`user.companionView`), live. */
@Composable
fun rememberCompanionView(graph: SortieApp.Graph): CompanionView {
    val flow = remember(graph) { graph.user.data.map { CompanionView.of(it.companionView) }.distinctUntilChanged() }
    return flow.collectAsState(CompanionView.of(graph.user.data.value.companionView)).value
}

/**
 * The monitor's readings while this surface is resumed on a lit display, else null. The collecting coroutine follows the
 * lifecycle's state flow and the display's callbacks itself ([io.github.esko997.sortie.stats.DeviceStatsMonitor.whileShown]):
 * a stopped window or a dark screen draws no frames, so nothing may wait for a recomposition to stop the polling.
 */
@Composable
private fun liveDeviceStats(graph: SortieApp.Graph): DeviceStats? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var stats by remember { mutableStateOf<DeviceStats?>(null) }
    LaunchedEffect(lifecycle, view) {
        graph.deviceStats.whileShown(lifecycle, displayLitFlow(view)).collect { stats = it }
    }
    return stats
}

/**
 * The monitor's Perf frames while this surface is resumed on a lit display, else null: like [liveDeviceStats], gated by the
 * lifecycle and display events, and while collected it makes the monitor poll at the Perf interval with details.
 */
@Composable
fun livePerfFrame(graph: SortieApp.Graph): PerfFrame? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var frame by remember { mutableStateOf<PerfFrame?>(null) }
    LaunchedEffect(lifecycle, view) {
        graph.deviceStats.perfWhileShown(lifecycle, displayLitFlow(view)).collect { frame = it }
    }
    return frame
}

/** Whether [view]'s display is on, from display-change callbacks; on (unknown) while the view has no display yet. */
private fun displayLitFlow(view: View): Flow<Boolean> = callbackFlow {
    val display = view.display
    if (display == null) {
        send(true)
        awaitClose()
        return@callbackFlow
    }
    val manager = view.context.getSystemService(DisplayManager::class.java)
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display.displayId) trySend(displayLit(display.state))
        }
    }
    manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    send(displayLit(display.state))
    awaitClose { manager.unregisterDisplayListener(listener) }
}.distinctUntilChanged()

/** A thin column of [cells], centred vertically, with a hairline on the side facing the panel. */
@Composable
private fun StatsStrip(cells: List<StatCell>, dividerAtEnd: Boolean) {
    Column(
        Modifier
            .fillMaxHeight()
            .width(STRIP_WIDTH)
            .drawBehind {
                val x = if (dividerAtEnd) size.width - 0.5f else 0.5f
                drawLine(Slate, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            },
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (cell in cells) {
            Column(Modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                StripText(cell.label, 9.sp, Amber.copy(alpha = 0.8f))
                StripText(cell.value, 13.sp, MaterialTheme.colorScheme.onSurface, FontWeight.Medium)
                cell.sub?.let { StripText(it, 9.sp, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StripText(text: String, size: TextUnit, color: Color, weight: FontWeight? = null) {
    Text(text, fontSize = size, color = color, fontWeight = weight, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

/** Wide enough for `1.8 GHz` under `100%`; the panel keeps the rest. */
private val STRIP_WIDTH = 52.dp
