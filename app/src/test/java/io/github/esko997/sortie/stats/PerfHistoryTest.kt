package io.github.esko997.sortie.stats

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfHistoryTest {
    @Test
    fun ringBufferKeepsTheNewestPollsOldestFirst() {
        val h = StatsHistory(capacity = 4)
        for (i in 1..6) h.add(i * 1_000L, DeviceStats(cpuPercent = i * 10))
        assertEquals(4, h.count)
        val s = h.snapshot()
        assertArrayEquals(longArrayOf(3_000, 4_000, 5_000, 6_000), s.times)
        assertArrayEquals(floatArrayOf(30f, 40f, 50f, 60f), s.values(PerfMetric.CPU_LOAD), 0f)
    }

    @Test
    fun snapshotHoldsOnlyTheWindow() {
        val h = StatsHistory()
        for (t in 0..200 step 2) h.add(t * 1_000L, DeviceStats(fanRpm = t))
        val s = h.snapshot()
        assertEquals(80_000L, s.times.first()) // 200 s - 120 s
        assertEquals(200_000L, s.times.last())
        assertEquals(61, s.times.size)
    }

    @Test
    fun snapshotIsACopy() {
        val h = StatsHistory(capacity = 3)
        h.add(1_000, DeviceStats(gpuPercent = 5))
        val before = h.snapshot()
        h.add(2_000, DeviceStats(gpuPercent = 7))
        assertEquals(1, before.times.size)
        assertEquals(5f, before.values(PerfMetric.GPU_LOAD)[0])
    }

    @Test
    fun aMetricNeverReportedIsAbsentAndGapsAreNaN() {
        val h = StatsHistory()
        h.add(1_000, DeviceStats(cpuTempC = 40))
        h.add(2_000, DeviceStats())
        h.add(3_000, DeviceStats(cpuTempC = 42))
        val s = h.snapshot()
        assertTrue(s.has(PerfMetric.CPU_TEMP))
        assertFalse(s.has(PerfMetric.FAN))
        assertTrue(s.values(PerfMetric.CPU_TEMP)[1].isNaN())
    }

    @Test
    fun emptyHistoryHasNothing() {
        val s = StatsHistory().snapshot()
        assertEquals(0, s.times.size)
        assertTrue(PerfMetric.entries.none(s::has))
        assertEquals(0, Sparkline.of(s.times, s.values(PerfMetric.CPU_LOAD), SparkRange(0f, 100f)).xs.size)
    }

    @Test
    fun sparklineEndsAtTheRightEdgeAndScalesIntoItsRange() {
        val line = Sparkline.of(longArrayOf(60_000, 120_000, 180_000), floatArrayOf(0f, Float.NaN, 75f), SparkRange(0f, 100f))
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), line.xs, 1e-6f)
        assertEquals(0f, line.ys[0])
        assertTrue(line.ys[1].isNaN())
        assertEquals(0.75f, line.ys[2], 1e-6f)
        // values outside the range clamp, a flat range draws mid-height
        assertEquals(1f, Sparkline.of(longArrayOf(1), floatArrayOf(250f), SparkRange(0f, 100f)).ys[0])
        assertEquals(0.5f, Sparkline.of(longArrayOf(1), floatArrayOf(3f), SparkRange(3f, 3f)).ys[0])
    }

    @Test
    fun rangesPerMetric() {
        val stats = DeviceStats(ramTotalBytes = 16_000_000_000)
        assertEquals(SparkRange(0f, 100f), Sparkline.range(PerfMetric.CPU_LOAD, floatArrayOf(3f, 9f), stats))
        assertEquals(SparkRange(0f, 16_000_000_000f), Sparkline.range(PerfMetric.RAM, floatArrayOf(6e9f), stats))
        assertEquals(SparkRange(0f, 1_843_200f), Sparkline.range(PerfMetric.CPU_CLOCK, floatArrayOf(1_555_200f, Float.NaN, 1_843_200f), stats))
        assertEquals(SparkRange(0f, 1_000f), Sparkline.range(PerfMetric.FAN, floatArrayOf(0f, 300f), stats))
        assertEquals(SparkRange(0f, 3_900f), Sparkline.range(PerfMetric.FAN, floatArrayOf(3_900f), stats))
        // temperatures: the values seen with 2 °C to spare, grown to at least 10 °C around their middle
        assertEquals(SparkRange(27f, 37f), Sparkline.range(PerfMetric.CPU_TEMP, floatArrayOf(33f, 31f), stats))
        assertEquals(SparkRange(28f, 72f), Sparkline.range(PerfMetric.GPU_TEMP, floatArrayOf(30f, 70f), stats))
        // nothing seen: a harmless unit range
        assertEquals(SparkRange(0f, 1f), Sparkline.range(PerfMetric.BATTERY_TEMP, floatArrayOf(Float.NaN), stats))
    }

    @Test
    fun bigNumbersAndUnits() {
        val s = DeviceStats(ramTotalBytes = 11_600_000_000)
        assertEquals("1.84", PerfMetric.CPU_CLOCK.big(1_843_200f))
        assertEquals("401", PerfMetric.GPU_CLOCK.big(401_000_000f))
        assertEquals("6.2", PerfMetric.RAM.big(6_200_000_000f))
        assertEquals("of 12 GB", PerfMetric.RAM.unit(s))
        assertEquals("37", PerfMetric.CPU_TEMP.big(36.6f))
        assertEquals("°C", PerfMetric.BATTERY_TEMP.unit(s))
    }

    @Test
    fun zonesGroupByNameWithSinglesInOther() {
        val zones = listOf(
            ThermalZone("pa", 34),
            ThermalZone("cpuss-0", 34),
            ThermalZone("cpuss-1", 35),
            ThermalZone("cpu-1-0", 33),
            ThermalZone("cpu-1-1", 36),
            ThermalZone("gpuss-0", 33),
            ThermalZone("gpuss-1", 32),
            ThermalZone("camera-0", 34),
            ThermalZone("camera-1", 35),
            ThermalZone("xo-therm", 33),
            ThermalZone("battery", 26)
        )
        val groups = ZoneGroup.of(zones)
        assertEquals(listOf("cpu-1", "cpuss", "gpuss", "camera", ZoneGroup.OTHER), groups.map { it.name })
        assertEquals(36, groups[0].hottest)
        assertEquals(listOf("0", "1"), groups[0].zones.map(groups[0]::short))
        assertEquals(listOf("pa", "xo-therm", "battery"), groups.last().zones.map(groups.last()::short))
    }
}
