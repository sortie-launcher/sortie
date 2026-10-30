package io.github.esko997.sortie.stats

import java.util.Locale

/**
 * One poll's readings; every field is optional and a surface shows only what is there. RAM and the battery temperature are
 * readable by any app; the rest needs the shell user (Shizuku) and nodes that only some devices have (see DeviceStatsProbe).
 */
data class DeviceStats(
    val cpuPercent: Int? = null,
    val cpuMaxKhz: Long? = null,
    val gpuPercent: Int? = null,
    val gpuHz: Long? = null,
    val ramUsedBytes: Long? = null,
    val ramTotalBytes: Long? = null,
    val cpuTempC: Int? = null,
    val gpuTempC: Int? = null,
    val batteryTempC: Int? = null,
    /** The fan's tachometer (AYN Thor); read as RPM, which the device does not label. */
    val fanRpm: Int? = null,
    /** Every online core's clock, by core number; read only while a Perf view is shown. */
    val coreKhz: List<CoreClock>? = null,
    /** Every thermal zone that reads as a temperature; read only while a Perf view is shown. */
    val zones: List<ThermalZone>? = null
)

/** One core's current clock; [core] is the kernel's CPU number (`cpu3` → 3). */
data class CoreClock(val core: Int, val khz: Long)

/** One thermal zone's reading, by the zone's `type` (`cpuss-0`, `xo-therm`, …). */
data class ThermalZone(val type: String, val celsius: Int)

/**
 * What the companion screen shows (`user.companionView`, dual-screen devices): its usual panel, the Perf view, or Auto: the
 * Perf view while a game started from Sortie is being played, else the usual panel.
 */
enum class CompanionView(val id: String, val label: String) {
    COMPANION("companion", "Companion"),
    AUTO("auto", "Auto"),
    PERF("perf", "Perf");

    fun next(): CompanionView = entries[(ordinal + 1) % entries.size]

    /**
     * The companion's main panel for this moment. [playing]: a session is open and the panel is its own (browsing another
     * game on the other screen takes the panel back); [game]: that session is a game's, not an app's or a shortcut's;
     * [nowPlayingChosen]: Auto's chip picked Now playing for this very session. Perf always shows Perf, Companion never does.
     */
    fun panel(playing: Boolean, game: Boolean, nowPlayingChosen: Boolean): CompanionPanel = when {
        this == PERF -> CompanionPanel.PERF
        !playing -> CompanionPanel.HERO
        this == AUTO && game -> if (nowPlayingChosen) CompanionPanel.AUTO_NOW_PLAYING else CompanionPanel.AUTO_PERF
        else -> CompanionPanel.NOW_PLAYING
    }

    companion object {
        fun of(id: String): CompanionView = entries.firstOrNull { it.id == id } ?: COMPANION
    }
}

/**
 * The companion's main panel ([CompanionView.panel]): the selected game or a rotating hero, the open session, or the Perf
 * view. The two Auto panels carry a chip that flips between them for the rest of the session.
 */
enum class CompanionPanel(val perf: Boolean) {
    HERO(false),
    NOW_PLAYING(false),
    PERF(true),
    AUTO_PERF(true),
    AUTO_NOW_PLAYING(false)
}

/** Where device stats are shown (`user.deviceStats`). Off by default: the strips narrow the companion panel. */
enum class DeviceStatsMode(val id: String, val label: String, val companion: Boolean, val overlay: Boolean) {
    OFF("off", "Off", false, false),
    COMPANION("companion", "Companion screen", true, false),
    OVERLAY("overlay", "Switcher overlay", false, true),
    BOTH("both", "Both", true, true);

    fun next(): DeviceStatsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(id: String): DeviceStatsMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** Seconds between polls the setting offers (`user.deviceStatsInterval`); anything else reads as the default. */
val STATS_INTERVALS = listOf(2, 5, 10)
const val DEFAULT_STATS_INTERVAL = 5

fun statsInterval(raw: Int): Int = if (raw in STATS_INTERVALS) raw else DEFAULT_STATS_INTERVAL

fun nextStatsInterval(raw: Int): Int = STATS_INTERVALS[(STATS_INTERVALS.indexOf(statsInterval(raw)) + 1) % STATS_INTERVALS.size]

/** Seconds between polls while a Perf view is shown (`user.perfInterval`); anything else reads as the default. */
val PERF_INTERVALS = listOf(1, 2)
const val DEFAULT_PERF_INTERVAL = 2

fun perfInterval(raw: Int): Int = if (raw in PERF_INTERVALS) raw else DEFAULT_PERF_INTERVAL

fun nextPerfInterval(raw: Int): Int = PERF_INTERVALS[(PERF_INTERVALS.indexOf(perfInterval(raw)) + 1) % PERF_INTERVALS.size]

/** CPU load needs two samples: right after the first one the next poll comes after [CPU_WARMUP_MS] instead of a full interval. */
const val CPU_WARMUP_MS = 1_000L

/** The wait before the next poll: the Perf interval while a Perf view is shown ([perfSeconds] not null), else the stats interval. */
fun nextPollDelayMs(intervalSeconds: Int, cpuPending: Boolean, perfSeconds: Int? = null): Long = when {
    cpuPending -> CPU_WARMUP_MS
    perfSeconds != null -> perfInterval(perfSeconds) * 1_000L
    else -> statsInterval(intervalSeconds) * 1_000L
}

/** One reading in a side strip: a small label, the value and an optional second line. */
data class StatCell(val label: String, val value: String, val sub: String? = null)

/** Short texts for the strips and the overlay row; null where the reading is missing. */
object StatsText {
    fun percent(p: Int?): String? = p?.let { "$it%" }

    fun ghz(khz: Long?): String? = khz?.let { String.format(Locale.US, "%.1f GHz", it / 1_000_000.0) }

    fun mhz(hz: Long?): String? = hz?.let { "${Math.round(it / 1_000_000.0)} MHz" }

    /** Decimal gigabytes, as Android's settings count RAM: one decimal below 10, whole above. */
    fun gb(bytes: Long?): String? = bytes?.let {
        val g = it / 1_000_000_000.0
        if (g < 10) String.format(Locale.US, "%.1f", g) else Math.round(g).toString()
    }

    fun celsius(c: Int?): String? = c?.let { "$it°C" }

    /** The companion's left strip: load. CPU and GPU with their clocks, then RAM used of total (`6.2G`, `of 16G`). */
    fun usageCells(s: DeviceStats): List<StatCell> = listOfNotNull(
        percent(s.cpuPercent)?.let { StatCell("CPU", it, ghz(s.cpuMaxKhz)) },
        percent(s.gpuPercent)?.let { StatCell("GPU", it, mhz(s.gpuHz)) },
        if (s.ramUsedBytes != null && s.ramTotalBytes != null) StatCell("RAM", "${gb(s.ramUsedBytes)}G", "of ${gb(s.ramTotalBytes)}G") else null
    )

    /** The right strip: heat. CPU, GPU and battery temperatures, then the fan. */
    fun heatCells(s: DeviceStats): List<StatCell> = listOfNotNull(
        celsius(s.cpuTempC)?.let { StatCell("CPU", it) },
        celsius(s.gpuTempC)?.let { StatCell("GPU", it) },
        celsius(s.batteryTempC)?.let { StatCell("BAT", it) },
        s.fanRpm?.let { StatCell("FAN", "$it", "rpm") }
    )

    /**
     * The overlay's single line, e.g. `CPU 12% 1.8 GHz 37°C · GPU 1% 401 MHz 36°C · RAM 6.2/16 GB · Battery 24°C · Fan 3900 rpm`;
     * a group without any reading is left out, empty when there is nothing at all.
     */
    fun line(s: DeviceStats): String {
        val ram = if (s.ramUsedBytes != null && s.ramTotalBytes != null) "RAM ${gb(s.ramUsedBytes)}/${gb(s.ramTotalBytes)} GB" else null
        return listOfNotNull(
            group("CPU", percent(s.cpuPercent), ghz(s.cpuMaxKhz), celsius(s.cpuTempC)),
            group("GPU", percent(s.gpuPercent), mhz(s.gpuHz), celsius(s.gpuTempC)),
            ram,
            group("Battery", celsius(s.batteryTempC)),
            group("Fan", s.fanRpm?.let { "$it rpm" })
        ).joinToString(" · ")
    }

    private fun group(label: String, vararg parts: String?): String? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" ", prefix = "$label ")
}
