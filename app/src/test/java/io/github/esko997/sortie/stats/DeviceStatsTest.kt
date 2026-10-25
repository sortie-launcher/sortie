package io.github.esko997.sortie.stats

import android.view.Display
import io.github.esko997.sortie.ui.displayLit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatsTest {
    /** The Thor sample: 15621188 kB total, 9543892 kB available, cpuss-0 36.8 °C, gpuss-0 35.5 °C, fan 3900. */
    private val thor = DeviceStats(
        cpuPercent = 12,
        cpuMaxKhz = 1_785_600,
        gpuPercent = 1,
        gpuHz = 401_000_000,
        ramUsedBytes = (15_621_188L - 9_543_892L) * 1024,
        ramTotalBytes = 15_621_188L * 1024,
        cpuTempC = 37,
        gpuTempC = 36,
        batteryTempC = 24,
        fanRpm = 3900
    )

    @Test
    fun overlayLineGroupsByPart() {
        assertEquals(
            "CPU 12% 1.8 GHz 37°C · GPU 1% 401 MHz 36°C · RAM 6.2/16 GB · Battery 24°C · Fan 3900 rpm",
            StatsText.line(thor)
        )
    }

    @Test
    fun withoutShizukuOnlyRamAndBatteryShow() {
        val app = DeviceStats(ramUsedBytes = thor.ramUsedBytes, ramTotalBytes = thor.ramTotalBytes, batteryTempC = 24)
        assertEquals("RAM 6.2/16 GB · Battery 24°C", StatsText.line(app))
        assertEquals(listOf(StatCell("RAM", "6.2G", "of 16G")), StatsText.usageCells(app))
        assertEquals(listOf(StatCell("BAT", "24°C")), StatsText.heatCells(app))
    }

    @Test
    fun nothingReadIsAnEmptyLine() {
        assertEquals("", StatsText.line(DeviceStats()))
        assertTrue(StatsText.usageCells(DeviceStats()).isEmpty())
        assertTrue(StatsText.heatCells(DeviceStats()).isEmpty())
    }

    @Test
    fun stripsSplitLoadAndHeat() {
        assertEquals(
            listOf(StatCell("CPU", "12%", "1.8 GHz"), StatCell("GPU", "1%", "401 MHz"), StatCell("RAM", "6.2G", "of 16G")),
            StatsText.usageCells(thor)
        )
        assertEquals(
            listOf(StatCell("CPU", "37°C"), StatCell("GPU", "36°C"), StatCell("BAT", "24°C"), StatCell("FAN", "3900", "rpm")),
            StatsText.heatCells(thor)
        )
    }

    @Test
    fun firstSampleHasClocksButNoLoadYet() {
        // CPU load needs a second sample: the clock alone is not shown as a CPU cell, the line still carries it
        val first = thor.copy(cpuPercent = null, gpuPercent = null)
        assertEquals(listOf(StatCell("RAM", "6.2G", "of 16G")), StatsText.usageCells(first))
        assertEquals("CPU 1.8 GHz 37°C · GPU 401 MHz 36°C · RAM 6.2/16 GB · Battery 24°C · Fan 3900 rpm", StatsText.line(first))
    }

    @Test
    fun numberFormats() {
        assertEquals("0.6 GHz", StatsText.ghz(595_200))
        assertEquals("220 MHz", StatsText.mhz(220_000_000))
        assertEquals("0.8", StatsText.gb(812_000_000))
        assertEquals("9.9", StatsText.gb(9_940_000_000))
        assertEquals("12", StatsText.gb(12_400_000_000))
        assertEquals("-3°C", StatsText.celsius(-3))
    }

    @Test
    fun modeCyclesAndUnknownIsOff() {
        assertEquals(DeviceStatsMode.OFF, DeviceStatsMode.of("off"))
        assertEquals(DeviceStatsMode.OFF, DeviceStatsMode.of("sideways"))
        assertEquals(
            listOf(DeviceStatsMode.COMPANION, DeviceStatsMode.OVERLAY, DeviceStatsMode.BOTH, DeviceStatsMode.OFF),
            generateSequence(DeviceStatsMode.OFF.next()) { if (it == DeviceStatsMode.OFF) null else it.next() }.toList()
        )
        assertTrue(DeviceStatsMode.BOTH.companion && DeviceStatsMode.BOTH.overlay)
        assertFalse(DeviceStatsMode.OFF.companion || DeviceStatsMode.OFF.overlay)
        for (mode in DeviceStatsMode.entries) assertEquals(mode, DeviceStatsMode.of(mode.id))
    }

    @Test
    fun intervalIsOneOfTheOffered() {
        assertEquals(5, statsInterval(5))
        assertEquals(5, statsInterval(0))
        assertEquals(5, statsInterval(7))
        assertEquals(listOf(5, 10, 2), listOf(2, 5, 10).map(::nextStatsInterval))
        assertEquals(10, nextStatsInterval(3)) // an unknown value reads as the default 5
    }

    @Test
    fun pollDelayShortensOnlyForTheSecondCpuSample() {
        assertEquals(CPU_WARMUP_MS, nextPollDelayMs(10, cpuPending = true))
        assertEquals(10_000L, nextPollDelayMs(10, cpuPending = false))
        assertEquals(2_000L, nextPollDelayMs(2, cpuPending = false))
        assertEquals(5_000L, nextPollDelayMs(1, cpuPending = false))
    }

    @Test
    fun perfViewPollsAtItsOwnInterval() {
        assertEquals(1_000L, nextPollDelayMs(10, cpuPending = false, perfSeconds = 1))
        assertEquals(2_000L, nextPollDelayMs(10, cpuPending = false, perfSeconds = 2))
        assertEquals(2_000L, nextPollDelayMs(5, cpuPending = false, perfSeconds = 7)) // unknown reads as the default 2
        assertEquals(CPU_WARMUP_MS, nextPollDelayMs(10, cpuPending = true, perfSeconds = 2))
        assertEquals(listOf(2, 1), listOf(1, 2).map(::nextPerfInterval))
        assertEquals(CompanionView.PERF, CompanionView.of("perf"))
        assertEquals(CompanionView.COMPANION, CompanionView.of("bogus"))
        assertEquals(CompanionView.COMPANION, CompanionView.PERF.next())
    }

    @Test
    fun onlyALitDisplayShowsStats() {
        assertTrue(displayLit(Display.STATE_ON))
        assertTrue(displayLit(Display.STATE_UNKNOWN))
        assertFalse(displayLit(Display.STATE_OFF))
        assertFalse(displayLit(Display.STATE_DOZE))
        assertFalse(displayLit(Display.STATE_DOZE_SUSPEND))
    }
}
