package io.github.esko997.sortie.shell

import io.github.esko997.sortie.shell.DeviceStatsProbe.CpuTimes
import io.github.esko997.sortie.shell.DeviceStatsProbe.Reading
import io.github.esko997.sortie.stats.CoreClock
import io.github.esko997.sortie.stats.ThermalZone
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device-stats probe against output recorded on the Thor and the DMG, and the real script against a fake `/proc` + `/sys`. */
class DeviceStatsProbeTest {
    private fun capture(path: String) = DeviceStatsProbe.parse(DeviceCaptures.text(path))

    @Test
    fun thorCaptureReadsEveryMetric() {
        val r = capture("thor/device-stats/probe-1.txt")
        assertEquals(CpuTimes(idle = 793673907L + 2947574, total = 70672140L + 6110550 + 65861196 + 793673907 + 2947574 + 12627641 + 3484244 + 21804), r.cpu)
        assertEquals(1843200L, r.cpuMaxKhz) // fastest of eight cores
        assertEquals(0, r.gpuPercent)
        assertEquals(401_000_000L, r.gpuHz)
        assertEquals(34, r.cpuTempC) // hottest cpu-* / cpuss-* zone, 34.4 °C
        assertEquals(33, r.gpuTempC) // hottest gpuss-* zone, 33.1 °C
        assertEquals(0, r.fanRpm) // the fan was stopped
    }

    @Test
    fun thorCapturesTwoSecondsApartGiveCpuLoad() {
        val first = capture("thor/device-stats/probe-1.txt").cpu
        val second = capture("thor/device-stats/probe-2.txt").cpu
        // 98 busy jiffies of 1765 between the two samples
        assertEquals(6, DeviceStatsProbe.cpuPercent(first, second))
    }

    @Test
    fun dmgCaptureHasNoFan() {
        val r = capture("dmg/device-stats/probe.txt")
        assertEquals(595200L, r.cpuMaxKhz)
        assertEquals(220_000_000L, r.gpuHz)
        assertEquals(32, r.cpuTempC)
        assertEquals(30, r.gpuTempC)
        assertNull(r.fanRpm)
    }

    @Test
    fun shellServiceExitLineAndUnknownLinesAreIgnored() {
        val r = DeviceStatsProbe.parse("0\nsomething else\nfan 3900\ngpu_busy 1 %\nend\n")
        assertEquals(Reading(gpuPercent = 1, fanRpm = 3900), r)
    }

    @Test
    fun missingGpuNodeLeavesGpuEmpty() {
        val r = DeviceStatsProbe.parse(
            "stat cpu  70653323 6109927 65843054 792552229 2947521 12621680 3482661 21784 0 0\nfreq 1785600\ntemp cpuss-0 36800\nend\n"
        )
        assertNull(r.gpuPercent)
        assertNull(r.gpuHz)
        assertNull(r.gpuTempC)
        assertEquals(37, r.cpuTempC)
    }

    @Test
    fun thermalZonesInErrorAreDropped() {
        val r = DeviceStatsProbe.parse(
            """
            temp cpuss-0 36800
            temp cpu-1-0 -273000
            temp cpu-1-1 999999
            temp cpu-1-2 n/a
            temp cpu-1-3
            temp gpuss-0 35500
            temp gpu-thermal 41
            end
            """.trimIndent()
        )
        assertEquals(37, r.cpuTempC) // the sane zone, not the disabled sensor or the unpopulated ADC
        assertEquals(41, r.gpuTempC) // a driver reporting whole degrees
    }

    @Test
    fun malformedValuesAreDropped() {
        val r = DeviceStatsProbe.parse("stat cpu 1 2 x 4\ngpu_busy 140 %\ngpu_clk 0\nfreq -5\nfan -1\nend\n")
        assertEquals(Reading(), r)
    }

    @Test
    fun cpuLoadNeedsTwoSamplesThatMoveForward() {
        val a = CpuTimes(idle = 900, total = 1000)
        assertNull(DeviceStatsProbe.cpuPercent(null, a)) // first sample
        assertNull(DeviceStatsProbe.cpuPercent(a, null)) // shell went away
        assertNull(DeviceStatsProbe.cpuPercent(a, a)) // no time passed
        assertNull(DeviceStatsProbe.cpuPercent(a, CpuTimes(idle = 100, total = 200))) // counters went back (reboot, CPU online)
        assertNull(DeviceStatsProbe.cpuPercent(a, CpuTimes(idle = 1200, total = 1100))) // more idle than time: inconsistent
        assertEquals(75, DeviceStatsProbe.cpuPercent(a, CpuTimes(idle = 925, total = 1100)))
        assertEquals(0, DeviceStatsProbe.cpuPercent(a, CpuTimes(idle = 1000, total = 1100)))
        assertEquals(100, DeviceStatsProbe.cpuPercent(a, CpuTimes(idle = 900, total = 1100)))
    }

    /** The script the app runs, in a local `sh`, against a fake tree with the failure cases the device shows. */
    @Test
    fun realScriptReadsWhatExistsAndSkipsWhatFails() {
        val root = fakeDevice()
        try {
            val out = run(DeviceStatsProbe.script(root.path))
            assertTrue(out, out.trimEnd().endsWith("end"))
            assertFalse(out, "battery" in out || "xo-therm" in out || "cpu-1-0" in out)
            assertEquals(
                Reading(
                    cpu = CpuTimes(idle = 792552229L + 2947521, total = 70653323L + 6109927 + 65843054 + 792552229 + 2947521 + 12621680 + 3482661 + 21784),
                    cpuMaxKhz = 1843200,
                    gpuPercent = 1,
                    gpuHz = 401_000_000,
                    cpuTempC = 37,
                    gpuTempC = 36,
                    fanRpm = 3900,
                    cores = listOf(CoreClock(0, 1555200), CoreClock(7, 1843200)),
                    zones = listOf(ThermalZone("cpuss-0", 37), ThermalZone("gpuss-0", 36))
                ),
                DeviceStatsProbe.parse(out)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /** The Perf view's probe reads every zone that answers, still skipping the failures. */
    @Test
    fun realDetailScriptReadsEveryZone() {
        val root = fakeDevice()
        try {
            val r = DeviceStatsProbe.parse(run(DeviceStatsProbe.script(root.path, detail = true)))
            assertEquals(
                listOf(ThermalZone("cpuss-0", 37), ThermalZone("gpuss-0", 36), ThermalZone("battery", 24), ThermalZone("xo-therm", 40)),
                r.zones
            )
            assertEquals(37, r.cpuTempC)
            assertEquals(listOf(CoreClock(0, 1555200), CoreClock(7, 1843200)), r.cores)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun thorDetailCaptureListsCoresAndTemperatureZonesOnly() {
        val r = capture("thor/device-stats/probe-detail.txt")
        assertEquals((0..7).toList(), r.cores.map { it.core })
        assertEquals(1843200L, r.cores.last().khz)
        assertEquals(1843200L, r.cpuMaxKhz)
        val types = r.zones.map { it.type }
        assertTrue(types.containsAll(listOf("cpuss-0", "gpuss-7", "battery", "xo-therm", "pm8550b_lite_tz", "ddr")))
        // limits and gauges listed as zones are not temperatures; the unplugged wireless sensors read -40 °C or less
        assertTrue(types.none { "bcl" in it || "ibat" in it || it == "vbat" || it == "socd" || it == "wireless" || it == "wls-therm" })
        assertEquals(26, r.zones.first { it.type == "battery" }.celsius)
        assertEquals(300, r.fanRpm)
    }

    @Test
    fun dmgAndNovaDetailCapturesParse() {
        val dmg = capture("dmg/device-stats/probe-detail.txt")
        assertEquals(8, dmg.cores.size)
        assertNull(dmg.fanRpm)
        assertTrue(dmg.zones.any { it.type == "skin-msm-therm" })
        val nova = capture("nova/device-stats/probe-detail.txt")
        assertEquals(8, nova.cores.size)
        assertEquals(0, nova.fanRpm) // the Nova has the same fan node, stopped
        assertTrue(nova.zones.isNotEmpty())
    }

    /** A fake `/proc` + `/sys` with the failure cases the devices show. */
    private fun fakeDevice(): File {
        val root = Files.createTempDirectory("stats-probe").toFile()
        root.put("proc/stat", "cpu  70653323 6109927 65843054 792552229 2947521 12621680 3482661 21784 0 0\ncpu0 1 2 3 4 5 6 7 8 0 0\n")
        root.put("sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "1555200\n")
        root.put("sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq", "1843200\n")
        File(root, "sys/devices/system/cpu/cpu3").mkdirs() // an offline core without cpufreq
        root.put("sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "1 %\n")
        root.put("sys/class/kgsl/kgsl-3d0/gpuclk", "401000000\n")
        root.zone(0, "cpuss-0", "36800")
        root.zone(1, "gpuss-0", "35500")
        root.zone(2, "battery", "24000") // not a CPU or GPU zone: only the detail probe reads it
        root.zone(3, "cpu-1-0", null).also { File(root, "sys/class/thermal/thermal_zone3/temp").mkdirs() } // read fails
        File(root, "sys/class/thermal/thermal_zone4").mkdirs() // no type at all
        root.zone(5, "xo-therm", "40000")
        root.put("sys/class/gpio5_pwm2/speed", "3900\n")
        return root
    }

    @Test
    fun realScriptOnABareDeviceStillEnds() {
        val root = Files.createTempDirectory("stats-probe-empty").toFile()
        try {
            val out = run(DeviceStatsProbe.script(root.path))
            assertEquals("end", out.trim())
            assertEquals(Reading(), DeviceStatsProbe.parse(out))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun appScriptReadsTheRealPaths() {
        assertTrue(DeviceStatsProbe.SCRIPT.startsWith("{ read -r l < /proc/stat"))
        assertTrue("/sys/class/gpio5_pwm2/speed" in DeviceStatsProbe.SCRIPT)
        assertTrue("case \$t in cpu*|gpu*)" in DeviceStatsProbe.SCRIPT)
        assertFalse("case \$t in" in DeviceStatsProbe.DETAIL_SCRIPT)
    }

    private fun File.put(path: String, text: String) {
        val f = File(this, path)
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun File.zone(n: Int, type: String, temp: String?) {
        put("sys/class/thermal/thermal_zone$n/type", "$type\n")
        if (temp != null) put("sys/class/thermal/thermal_zone$n/temp", "$temp\n")
    }

    private fun run(script: String): String {
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("probe timed out", p.waitFor(10, TimeUnit.SECONDS))
        assertEquals(out, 0, p.exitValue())
        return out
    }
}
