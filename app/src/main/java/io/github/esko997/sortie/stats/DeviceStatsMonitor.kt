package io.github.esko997.sortie.stats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.shell.ScreenMover
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** One poll for a Perf view: the readings with their details, and the history up to and including them. */
class PerfFrame(val stats: DeviceStats, val history: StatsHistory.Snapshot)

/**
 * Polls device stats while at least one surface collects them, and not at all otherwise: surfaces collect through
 * [whileShown] or [perfWhileShown], so with nothing shown there is no timer, no shell and no work. One batched shell probe
 * per poll through [ScreenMover] (Shizuku) plus two app-side reads (RAM from ActivityManager, the battery temperature from
 * the sticky battery broadcast) that work everywhere. The value resets to null when the last surface goes, so a returning
 * surface never shows numbers from minutes ago.
 *
 * While a Perf view is shown, polls come every [perfIntervalSeconds] and run the detail probe (every core, every thermal zone);
 * a Perf view that opens during a longer wait cuts it short. Every poll goes into a [StatsHistory] that belongs to the
 * polling coroutine, so the history exists only while some stats surface is shown and is dropped with the poller.
 */
class DeviceStatsMonitor internal constructor(
    private val mover: ScreenMover,
    private val intervalSeconds: () -> Int,
    private val perfIntervalSeconds: () -> Int,
    /** RAM and battery temperature, readable without the shell. */
    private val appStats: () -> DeviceStats,
    scope: CoroutineScope,
    /** Monotonic milliseconds for the history's time axis. */
    private val clock: () -> Long,
    /** Waits between polls; tests replace it to drive polls one by one. */
    private val sleep: suspend (Long) -> Unit
) {
    constructor(context: Context, mover: ScreenMover, intervalSeconds: () -> Int, perfIntervalSeconds: () -> Int) : this(
        mover,
        intervalSeconds,
        perfIntervalSeconds,
        appStatsOf(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        SystemClock::elapsedRealtime,
        { delay(it) }
    )

    /** One poll: the readings, and (only for polls made while a Perf view was shown) the history snapshot. */
    private class Poll(val stats: DeviceStats, val perf: PerfFrame?)

    /** Perf views being shown right now; above zero the poller runs fast and in detail. */
    private val perfViews = MutableStateFlow(0)

    private val polls: StateFlow<Poll?> = flow {
        var previous: DeviceStatsProbe.CpuTimes? = null
        val history = StatsHistory()
        while (true) {
            val perf = perfViews.value > 0
            val shell = mover.read(if (perf) DeviceStatsProbe.DETAIL_SCRIPT else DeviceStatsProbe.SCRIPT)?.let(DeviceStatsProbe::parse)
            val cpuPercent = DeviceStatsProbe.cpuPercent(previous, shell?.cpu)
            previous = shell?.cpu
            val stats = appStats().copy(
                cpuPercent = cpuPercent,
                cpuMaxKhz = shell?.cpuMaxKhz,
                gpuPercent = shell?.gpuPercent,
                gpuHz = shell?.gpuHz,
                cpuTempC = shell?.cpuTempC,
                gpuTempC = shell?.gpuTempC,
                fanRpm = shell?.fanRpm,
                coreKhz = shell?.cores?.takeIf { perf && it.isNotEmpty() },
                zones = shell?.zones?.takeIf { perf && it.isNotEmpty() }
            )
            history.add(clock(), stats)
            emit(Poll(stats, if (perf) PerfFrame(stats, history.snapshot()) else null))
            val cpuPending = cpuPercent == null && shell?.cpu != null
            pause(nextPollDelayMs(intervalSeconds(), cpuPending, perfIntervalSeconds().takeIf { perf }), perf)
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    /** Waits [ms]; a slow wait ends early when a Perf view opens, so it fills at once instead of after up to ten seconds. */
    private suspend fun pause(ms: Long, perf: Boolean) {
        if (perf) return sleep(ms)
        coroutineScope {
            val nap = launch { sleep(ms) }
            val wake = launch { perfViews.first { it > 0 } }
            select {
                nap.onJoin {}
                wake.onJoin {}
            }
            coroutineContext.cancelChildren()
        }
    }

    /**
     * The readings while [lifecycle] is resumed and [displayLit] says the surface's display is on, else null (and
     * unsubscribed). Switching is driven by the lifecycle's state flow and the display callbacks, not by recomposition:
     * Android stops a window's frames when it stops or its screen goes off, so a gate read during composition would never run
     * again to let go.
     */
    fun whileShown(lifecycle: Lifecycle, displayLit: Flow<Boolean>): Flow<DeviceStats?> = gated(lifecycle, displayLit) {
        polls.map { it?.stats }
    }

    /**
     * Like [whileShown], for a Perf view: while shown it counts as one, so polls come at the Perf interval with details, and
     * each carries the history. Null until the first such poll.
     */
    fun perfWhileShown(lifecycle: Lifecycle, displayLit: Flow<Boolean>): Flow<PerfFrame?> = gated(lifecycle, displayLit) {
        flow {
            perfViews.update { it + 1 }
            try {
                emitAll(polls.map { it?.perf })
            } finally {
                perfViews.update { it - 1 }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> gated(lifecycle: Lifecycle, displayLit: Flow<Boolean>, shown: () -> Flow<T?>): Flow<T?> =
        combine(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.RESUMED) }, displayLit) { resumed, lit -> resumed && lit }
            .distinctUntilChanged()
            .flatMapLatest { if (it) shown() else flowOf(null) }

    private companion object {
        fun appStatsOf(context: Context): () -> DeviceStats {
            val activityManager = context.getSystemService(ActivityManager::class.java)
            return {
                val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
                DeviceStats(
                    ramUsedBytes = (memory.totalMem - memory.availMem).takeIf { memory.totalMem > 0 },
                    ramTotalBytes = memory.totalMem.takeIf { it > 0 },
                    batteryTempC = batteryTempC(context)
                )
            }
        }

        /** Tenths of a degree in the sticky ACTION_BATTERY_CHANGED; reading it registers nothing. */
        fun batteryTempC(context: Context): Int? {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            return if (tenths == Int.MIN_VALUE) null else (tenths / 10.0).roundToInt().takeIf { it in -40..150 }
        }
    }
}
