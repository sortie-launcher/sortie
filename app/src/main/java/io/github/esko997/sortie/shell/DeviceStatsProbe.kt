package io.github.esko997.sortie.shell

/**
 * The device-stats reads that need the shell user: `/proc/stat` (apps may not read it), per-core CPU clocks, the Adreno
 * GPU's load and clock, the CPU and GPU thermal zones and the AYN Thor's fan tachometer. One `sh` per poll that uses only
 * builtins (`read`, `case`, globs), so no further processes are started; every read is optional, and a node that is missing
 * or refuses the read (some thermal zones answer EINVAL) prints nothing. Output, one fact per line, then `end`:
 *
 * ```
 * stat cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> <steal> …
 * freq <kHz>                 one per online core
 * gpu_busy <n> %
 * gpu_clk <Hz>
 * temp <zone type> <milli-°C>  zones whose type starts with cpu or gpu
 * fan <rpm>
 * end
 * ```
 */
internal object DeviceStatsProbe {
    /** The probe as the app runs it. */
    val SCRIPT: String = script("")

    /** [root] prefixes every path, so tests can run the real script in a local `sh` against a fake `/proc` and `/sys`. */
    fun script(root: String): String = "{ " +
        "read -r l < $root/proc/stat && echo \"stat \$l\"; " +
        "for f in $root/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do read -r v < \$f && echo \"freq \$v\"; done; " +
        "read -r v < $root/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage && echo \"gpu_busy \$v\"; " +
        "read -r v < $root/sys/class/kgsl/kgsl-3d0/gpuclk && echo \"gpu_clk \$v\"; " +
        "for z in $root/sys/class/thermal/thermal_zone*; do read -r t < \$z/type || continue; " +
        "case \$t in cpu*|gpu*) read -r v < \$z/temp && echo \"temp \$t \$v\";; esac; done; " +
        "read -r v < $root/sys/class/gpio5_pwm2/speed && echo \"fan \$v\"; " +
        "} 2>/dev/null; echo end"

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
        val fanRpm: Int? = null
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
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "").trim()
            when (key) {
                "stat" -> cpu = parseCpuTimes(rest) ?: cpu
                "freq" -> rest.toLongOrNull()?.takeIf { it > 0 }?.let { maxKhz = maxOf(maxKhz ?: 0L, it) }
                "gpu_busy" -> gpuPercent = rest.substringBefore('%').trim().toIntOrNull()?.takeIf { it in 0..100 } ?: gpuPercent
                "gpu_clk" -> gpuHz = rest.toLongOrNull()?.takeIf { it > 0 } ?: gpuHz
                "temp" -> {
                    val type = rest.substringBefore(' ')
                    val celsius = celsius(rest.substringAfter(' ', "")) ?: continue
                    when {
                        type.startsWith("cpu") -> cpuTemp = maxOf(cpuTemp ?: celsius, celsius)
                        type.startsWith("gpu") -> gpuTemp = maxOf(gpuTemp ?: celsius, celsius)
                    }
                }
                "fan" -> fan = rest.toIntOrNull()?.takeIf { it >= 0 } ?: fan
            }
        }
        return Reading(cpu, maxKhz, gpuPercent, gpuHz, cpuTemp, gpuTemp, fan)
    }

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
