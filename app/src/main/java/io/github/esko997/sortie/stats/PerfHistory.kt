package io.github.esko997.sortie.stats

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** How far back the Perf view's sparklines reach. */
const val HISTORY_WINDOW_MS = 120_000L

/** Room for [HISTORY_WINDOW_MS] at the fastest cadence (1 s), with the first second's CPU warm-up poll to spare. */
const val HISTORY_CAPACITY = 128

/**
 * A Perf view's numbers, in the order the page shows them. [read] picks the value from one poll (null where the device does
 * not report it); [big] and [unit] format it as the tile's large number and its small unit.
 */
enum class PerfMetric(val label: String, val read: (DeviceStats) -> Float?) {
    CPU_LOAD("CPU load", { it.cpuPercent?.toFloat() }),
    CPU_CLOCK("CPU clock", { it.cpuMaxKhz?.toFloat() }),
    GPU_LOAD("GPU load", { it.gpuPercent?.toFloat() }),
    GPU_CLOCK("GPU clock", { it.gpuHz?.toFloat() }),
    RAM("RAM", { it.ramUsedBytes?.toFloat() }),
    CPU_TEMP("CPU temp", { it.cpuTempC?.toFloat() }),
    GPU_TEMP("GPU temp", { it.gpuTempC?.toFloat() }),
    BATTERY_TEMP("Battery temp", { it.batteryTempC?.toFloat() }),
    FAN("Fan", { it.fanRpm?.toFloat() });

    /** The large number for [v]: `37`, `1.8`, `401`, `6.2`, `3900`. */
    fun big(v: Float): String = when (this) {
        CPU_CLOCK -> String.format(Locale.US, "%.2f", v / 1_000_000f)
        GPU_CLOCK -> Math.round(v / 1_000_000f).toString()
        RAM -> StatsText.gb(v.toLong()).orEmpty()
        else -> Math.round(v).toString()
    }

    /** The unit beside it; RAM's names the total, `of 16 GB`. */
    fun unit(latest: DeviceStats): String = when (this) {
        CPU_LOAD, GPU_LOAD -> "%"
        CPU_CLOCK -> "GHz"
        GPU_CLOCK -> "MHz"
        RAM -> latest.ramTotalBytes?.let { "of ${StatsText.gb(it)} GB" } ?: "GB"
        CPU_TEMP, GPU_TEMP, BATTERY_TEMP -> "°C"
        FAN -> "rpm"
    }
}

/**
 * The last [capacity] polls, one float per [PerfMetric] (NaN where the poll had no such reading), oldest overwritten first.
 * Lives inside the monitor's polling coroutine and only as long as it runs, so it exists exactly while some stats surface is
 * shown; written and read from that one coroutine, so it needs no lock. The Perf view gets immutable [Snapshot]s.
 */
class StatsHistory(private val capacity: Int = HISTORY_CAPACITY) {
    private val times = LongArray(capacity)
    private val values = Array(PerfMetric.entries.size) { FloatArray(capacity) }
    private var next = 0
    private var size = 0

    val count: Int get() = size

    fun add(timeMs: Long, stats: DeviceStats) {
        times[next] = timeMs
        for (m in PerfMetric.entries) values[m.ordinal][next] = m.read(stats) ?: Float.NaN
        next = (next + 1) % capacity
        size = minOf(size + 1, capacity)
    }

    /** The polls within [windowMs] of the newest, oldest first. */
    fun snapshot(windowMs: Long = HISTORY_WINDOW_MS): Snapshot {
        if (size == 0) return Snapshot(LongArray(0), Array(PerfMetric.entries.size) { FloatArray(0) })
        val newest = times[(next - 1 + capacity) % capacity]
        var n = 0
        while (n < size && newest - times[(next - 1 - n + 2 * capacity) % capacity] <= windowMs) n++
        val first = (next - n + capacity) % capacity
        val t = LongArray(n) { times[(first + it) % capacity] }
        val v = Array(values.size) { m -> FloatArray(n) { values[m][(first + it) % capacity] } }
        return Snapshot(t, v)
    }

    /** Polled times and each metric's values, oldest first; the arrays are never written after construction. */
    class Snapshot(val times: LongArray, private val values: Array<FloatArray>) {
        fun values(metric: PerfMetric): FloatArray = values[metric.ordinal]

        /** Whether any poll in the window had [metric]: a metric the device never reports gets no tile. */
        fun has(metric: PerfMetric): Boolean = values[metric.ordinal].any { !it.isNaN() }
    }
}

/** A sparkline's vertical range. */
data class SparkRange(val min: Float, val max: Float)

/**
 * Sparkline geometry: one point per poll in the window, x and y both 0–1 (y up; NaN where the poll had no value, which breaks
 * the line). The newest poll sits at the right edge and the window's start at the left, so a short history fills from the right.
 */
class Sparkline(val xs: FloatArray, val ys: FloatArray) {
    companion object {
        fun of(times: LongArray, values: FloatArray, range: SparkRange, windowMs: Long = HISTORY_WINDOW_MS): Sparkline {
            val end = times.lastOrNull() ?: return Sparkline(FloatArray(0), FloatArray(0))
            val start = end - windowMs
            val span = range.max - range.min
            val xs = FloatArray(times.size) { ((times[it] - start).toFloat() / windowMs).coerceIn(0f, 1f) }
            val ys = FloatArray(values.size) { i ->
                val v = values[i]
                if (v.isNaN()) Float.NaN else if (span <= 0f) 0.5f else ((v - range.min) / span).coerceIn(0f, 1f)
            }
            return Sparkline(xs, ys)
        }

        /**
         * The range a metric's line is drawn against. Loads use the full 0–100 %; RAM the device's total; clocks and the fan
         * zero to the highest value seen (the fan at least 1,000 rpm, so a spinning-down fan does not look like a full one);
         * temperatures the values seen with 2 °C to spare and at least 10 °C tall, so sensor noise stays flat.
         */
        fun range(metric: PerfMetric, values: FloatArray, latest: DeviceStats): SparkRange {
            var lo = Float.POSITIVE_INFINITY
            var hi = Float.NEGATIVE_INFINITY
            for (v in values) {
                if (v.isNaN()) continue
                lo = minOf(lo, v)
                hi = maxOf(hi, v)
            }
            if (lo > hi) return SparkRange(0f, 1f)
            return when (metric) {
                PerfMetric.CPU_LOAD, PerfMetric.GPU_LOAD -> SparkRange(0f, 100f)
                PerfMetric.RAM -> SparkRange(0f, latest.ramTotalBytes?.toFloat()?.takeIf { it > 0f } ?: max(hi, 1f))
                PerfMetric.CPU_CLOCK, PerfMetric.GPU_CLOCK -> SparkRange(0f, max(hi, 1f))
                PerfMetric.FAN -> SparkRange(0f, max(hi, 1_000f))
                PerfMetric.CPU_TEMP, PerfMetric.GPU_TEMP, PerfMetric.BATTERY_TEMP -> {
                    var low = floor(lo) - 2f
                    var high = ceil(hi) + 2f
                    if (high - low < MIN_TEMP_SPAN) {
                        val grow = (MIN_TEMP_SPAN - (high - low)) / 2f
                        low -= grow
                        high += grow
                    }
                    SparkRange(low, high)
                }
            }
        }

        private const val MIN_TEMP_SPAN = 10f
    }
}

/** Thermal zones that belong together (`cpuss-0` … `cpuss-3`), with the hottest; [name] `Other` gathers the single zones. */
data class ZoneGroup(val name: String, val zones: List<ThermalZone>) {
    val hottest: Int get() = zones.maxOf { it.celsius }

    /** A zone's short label inside its group: the part after the group's name (`cpuss-2` → `2`); the full type in Other. */
    fun short(zone: ThermalZone): String = if (name == OTHER) zone.type else zone.type.removePrefix(name).trimStart('-', '_').ifEmpty { zone.type }

    companion object {
        const val OTHER = "Other"

        /**
         * Groups by the type without its last numeric part (`cpu-1-7` → `cpu-1`, `gpuss-3` → `gpuss`, `camera-0` → `camera`);
         * a group of one joins Other. CPU groups first, then GPU, then the rest alphabetically, Other last; zones keep
         * their order inside a group.
         */
        fun of(zones: List<ThermalZone>): List<ZoneGroup> {
            val byKey = LinkedHashMap<String, MutableList<ThermalZone>>()
            for (z in zones) byKey.getOrPut(key(z.type)) { ArrayList() } += z
            val (groups, singles) = byKey.entries.partition { it.value.size > 1 }
            val sorted = groups.map { ZoneGroup(it.key, it.value) }.sortedWith(compareBy({ rank(it.name) }, { it.name }))
            val other = singles.flatMap { it.value }
            return if (other.isEmpty()) sorted else sorted + ZoneGroup(OTHER, other)
        }

        private val NUMBERED = Regex("[-_]\\d+$")

        private fun key(type: String): String = NUMBERED.find(type)?.let { type.substring(0, it.range.first) } ?: type

        private fun rank(name: String): Int = when {
            name.startsWith("cpu") -> 0
            name.startsWith("gpu") -> 1
            else -> 2
        }
    }
}
