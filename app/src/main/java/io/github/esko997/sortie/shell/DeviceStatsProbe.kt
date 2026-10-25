package io.github.esko997.sortie.shell

import io.github.esko997.sortie.stats.CoreClock
import io.github.esko997.sortie.stats.ThermalZone

/**
 * The device-stats reads that need the shell user: `/proc/stat` (apps may not read it), per-core CPU clocks, the Adreno
 * GPU's load and clock, the CPU and GPU thermal zones and the AYN Thor's fan tachometer. One `sh` per poll that uses only
 * builtins (`read`, `case`, globs, parameter expansion), so no further processes are started; every read is optional, and a
 * node that is missing or refuses the read (some thermal zones answer EINVAL) prints nothing. Output, one fact per line,
 * then `end`:
 *
 * ```
 * stat cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> <steal> …
 * freq cpu<n> <kHz>          one per online core
 * gpu_busy <n> %
 * gpu_clk <Hz>
 * temp <zone type> <milli-°C>  zones whose type starts with cpu or gpu ([DETAIL_SCRIPT]: every zone)
 * fan <rpm>
 * end
 * ```
 *
 * The detail probe costs about as much as the plain one (0.12 against 0.10 s of system time on the Thor, 67 zones against 35):
 * most of the time goes into the CPU and GPU zones both read.
 */
internal object DeviceStatsProbe {
    /** The probe as the app runs it while only the stats strips or the overlay line are shown. */
    val SCRIPT: String = script("")

    /** The probe while a Perf view is shown: every thermal zone, for its details section. */
    val DETAIL_SCRIPT: String = script("", detail = true)

    /** [root] prefixes every path, so tests can run the real script in a local `sh` against a fake `/proc` and `/sys`. */
    fun script(root: String, detail: Boolean = false): String = "{ " +
        "read -r l < $root/proc/stat && echo \"stat \$l\"; " +
        "for f in $root/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do read -r v < \$f && " +
        "c=\${f%/cpufreq/*} && echo \"freq \${c##*/} \$v\"; done; " +
        "read -r v < $root/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage && echo \"gpu_busy \$v\"; " +
        "read -r v < $root/sys/class/kgsl/kgsl-3d0/gpuclk && echo \"gpu_clk \$v\"; " +
        "for z in $root/sys/class/thermal/thermal_zone*; do read -r t < \$z/type || continue; " +
        (if (detail) "$ZONE_READ; " else "case \$t in cpu*|gpu*) $ZONE_READ;; esac; ") + "done; " +
        "read -r v < $root/sys/class/gpio5_pwm2/speed && echo \"fan \$v\"; " +
        "} 2>/dev/null; echo end"

    private const val ZONE_READ = "read -r v < \$z/temp && echo \"temp \$t \$v\""

    /** Cumulative jiffies from the first line of `/proc/stat`: [idle] includes iowait, [total] everything but guest time. */
    data class CpuTimes(val idle: Long, val total: Long)

    /** What one probe saw; null where the device has no such node or the reading made no sense. */
    data class Reading(
        val cpu: CpuTimes? = null,
        /** The fastest core's current clock. */
        val cpuMaxKhz: Long? = null,
        val gpuPercent: Int? = null,
        val gpuHz: Long? = null,
        /** Hottest zone of each kind, in whole °C. */
        val cpuTempC: Int? = null,
        val gpuTempC: Int? = null,
        val fanRpm: Int? = null,
        /** Each core's clock in core order; empty from an older probe that printed no core names. */
        val cores: List<CoreClock> = emptyList(),
        /** Every zone printed that reads as a temperature, in the kernel's order. */
        val zones: List<ThermalZone> = emptyList()
    )

    /**
     * Parses the probe's output (the shell service's `<exit code>` line may lead; it is not a fact and is skipped). Lines it
     * does not understand are ignored, so a partial reply still yields whatever it holds.
     */
    fun parse(output: String): Reading {
        var cpu: CpuTimes? = null
        var maxKhz: Long? = null
        var gpuPercent: Int? = null
        var gpuHz: Long? = null
        var cpuTemp: Int? = null
        var gpuTemp: Int? = null
        var fan: Int? = null
        val cores = ArrayList<CoreClock>()
        val zones = ArrayList<ThermalZone>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "").trim()
            when (key) {
                "stat" -> cpu = parseCpuTimes(rest) ?: cpu
                "freq" -> {
                    // `cpu3 1651200`, or a bare `1651200` from a probe without core names
                    val khz = rest.substringAfterLast(' ').toLongOrNull()?.takeIf { it > 0 } ?: continue
                    maxKhz = maxOf(maxKhz ?: 0L, khz)
                    val core = rest.substringBefore(' ', "").removePrefix("cpu").toIntOrNull()
                    if (core != null) cores += CoreClock(core, khz)
                }
                "gpu_busy" -> gpuPercent = rest.substringBefore('%').trim().toIntOrNull()?.takeIf { it in 0..100 } ?: gpuPercent
                "gpu_clk" -> gpuHz = rest.toLongOrNull()?.takeIf { it > 0 } ?: gpuHz
                "temp" -> {
                    val type = rest.substringBefore(' ')
                    val celsius = celsius(rest.substringAfter(' ', "")) ?: continue
                    when {
                        type.startsWith("cpu") -> cpuTemp = maxOf(cpuTemp ?: celsius, celsius)
                        type.startsWith("gpu") -> gpuTemp = maxOf(gpuTemp ?: celsius, celsius)
                    }
                    if (isTemperature(type) && celsius > -40) zones += ThermalZone(type, celsius)
                }
                "fan" -> fan = rest.toIntOrNull()?.takeIf { it >= 0 } ?: fan
            }
        }
        cores.sortBy { it.core }
        return Reading(cpu, maxKhz, gpuPercent, gpuHz, cpuTemp, gpuTemp, fan, cores, zones)
    }

    /**
     * Qualcomm also lists limits and battery gauges as thermal zones: `*-bcl-lvl*` and `*-ibat-lvl*` (current-limit levels),
     * `vbat` (millivolts) and `socd` (charge). They are not temperatures, so the details leave them out. A zone that reads
     * -40 °C (an unplugged wireless or charger sensor) is left out too.
     */
    private fun isTemperature(type: String): Boolean = NOT_TEMPERATURE.none { it in type }

    private val NOT_TEMPERATURE = listOf("bcl", "ibat", "vbat", "socd", "-lvl")

    /** `cpu  user nice system idle iowait irq softirq steal [guest guest_nice]`; guest time is already inside user. */
    private fun parseCpuTimes(line: String): CpuTimes? {
        val fields = line.split(' ').filter { it.isNotEmpty() }
        if (fields.firstOrNull() != "cpu" || fields.size < 5) return null
        val n = fields.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        return CpuTimes(idle = n[3] + n.getOrElse(4) { 0L }, total = n.sum())
    }

    /**
     * Thermal zones report milli-°C; a few drivers report whole °C. Anything outside -40…150 °C is a zone in error (a
     * disabled sensor's -273000, an unpopulated ADC's huge value) and is dropped.
     */
    private fun celsius(value: String): Int? {
        val v = value.trim().toLongOrNull() ?: return null
        val c = if (v >= 1000 || v <= -1000) Math.round(v / 1000.0).toInt() else v.toInt()
        return c.takeIf { it in -40..150 }
    }

    /**
     * Busy share of all CPUs between two samples, 0–100; null for the first sample, or when the counters went backwards
     * (a CPU came online with fresh counters, or a reboot) or did not move.
     */
    fun cpuPercent(previous: CpuTimes?, current: CpuTimes?): Int? {
        if (previous == null || current == null) return null
        val total = current.total - previous.total
        val idle = current.idle - previous.idle
        if (total <= 0 || idle < 0 || idle > total) return null
        return Math.round((total - idle) * 100.0 / total).toInt()
    }
}
