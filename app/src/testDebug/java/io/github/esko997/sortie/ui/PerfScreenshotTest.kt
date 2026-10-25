package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.esko997.sortie.shell.DeviceCaptures
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.stats.DeviceStats
import io.github.esko997.sortie.stats.PerfFrame
import io.github.esko997.sortie.stats.StatsHistory
import io.github.esko997.sortie.testing.DMG
import io.github.esko997.sortie.testing.RP_NOVA
import io.github.esko997.sortie.testing.THOR_BOTTOM
import io.github.esko997.sortie.testing.THOR_TOP
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots of the Perf page with fixed stats: the Thor's detail probe (cores, every zone) as the newest poll, after
 * two minutes of polls every 2 s whose numbers follow fixed waves (a game loading, then playing). The overlay's controller
 * window on every device; the companion screen's Perf mode on the Thor's bottom screen. Goldens live with the others
 * (app/src/testDebug/screenshots/<device>/, docs/HARDENING.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class PerfScreenshotTest(private val device: String) {
    @Test
    fun overlayPerfPage() = capture("perf-overlay") {
        // the overlay's scrim over a dark game
        Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
            Surface(Modifier.fillMaxSize(), color = Color(0xDD0B0D10), contentColor = MaterialTheme.colorScheme.onSurface) {
                OverlayPerfPage(FIXTURE, controller = true, onApps = {})
            }
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    protected fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("$device/$name.png", OPTIONS) { SortieTheme(content) }
    }

    companion object {
        private val OPTIONS = RoborazziOptions(
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f)
        )

        /** Sixty polls 2 s apart; the last one is the Thor capture with its details. */
        val FIXTURE: PerfFrame = run {
            val probe = DeviceStatsProbe.parse(DeviceCaptures.text("thor/device-stats/probe-detail.txt"))
            val history = StatsHistory()
            var last = DeviceStats()
            for (i in 0 until 60) {
                val wave = sin(i / 6.0)
                val loading = i < 15
                last = DeviceStats(
                    cpuPercent = (if (loading) 70 + 20 * wave else 35 + 12 * wave).roundToInt(),
                    // the last one (i = 59) is the capture's fastest core
                    cpuMaxKhz = if (loading) 2_841_600L else if (i % 4 == 3) 1_843_200L else 2_419_200L,
                    gpuPercent = (if (loading) 10 + 5 * wave else 62 + 20 * wave).roundToInt(),
                    gpuHz = if (loading) 401_000_000L else 680_000_000L,
                    ramUsedBytes = 6_100_000_000L + i * 12_000_000L,
                    ramTotalBytes = 11_600_000_000L,
                    cpuTempC = 38 + i / 4,
                    gpuTempC = 36 + i / 5,
                    batteryTempC = 27 + i / 30,
                    fanRpm = if (i < 20) 0 else 1_800 + i * 20,
                    coreKhz = probe.cores,
                    zones = probe.zones
                )
                history.add(i * 2_000L, last)
            }
            PerfFrame(last, history.snapshot())
        }
    }
}

@Config(qualifiers = THOR_TOP)
class ThorTopPerfScreenshotTest : PerfScreenshotTest("thor-top")

@Config(qualifiers = THOR_BOTTOM)
class ThorBottomPerfScreenshotTest : PerfScreenshotTest("thor-bottom") {
    @Test
    fun companionPerf() = capture("perf-companion") {
        Box(Modifier.fillMaxSize().background(Ink)) { CompanionPerfPage(FIXTURE) }
    }

    /** The end of the page: the per-core clocks and the Thor's thermal zones, grouped. */
    @Test
    fun perfDetails() = capture("perf-details") {
        Box(Modifier.fillMaxSize().background(Ink)) {
            PerfPage(FIXTURE, Modifier.fillMaxSize(), PaddingValues(16.dp), rememberLazyListState(1_000)) {}
        }
    }
}

@Config(qualifiers = DMG)
class DmgPerfScreenshotTest : PerfScreenshotTest("dmg")

@Config(qualifiers = RP_NOVA)
class RpNovaPerfScreenshotTest : PerfScreenshotTest("rp-nova")
