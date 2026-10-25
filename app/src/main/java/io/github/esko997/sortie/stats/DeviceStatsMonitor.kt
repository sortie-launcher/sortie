package io.github.esko997.sortie.stats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.Lifecycle
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.shell.ScreenMover
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Polls device stats while at least one surface collects [stats], and not at all otherwise: surfaces collect through
 * [whileShown], so with nothing shown there is no timer, no shell and no work. One batched shell probe per poll through
 * [ScreenMover] (Shizuku) plus two app-side reads (RAM from ActivityManager, the battery temperature from the sticky battery
 * broadcast) that work everywhere. The value resets to null when the last surface goes, so a returning surface never shows
 * numbers from minutes ago.
 */
class DeviceStatsMonitor internal constructor(
    private val mover: ScreenMover,
    private val intervalSeconds: () -> Int,
    /** RAM and battery temperature, readable without the shell. */
    private val appStats: () -> DeviceStats,
    scope: CoroutineScope,
    /** Waits between polls; tests replace it to drive polls one by one. */
    private val sleep: suspend (Long) -> Unit
) {
    constructor(context: Context, mover: ScreenMover, intervalSeconds: () -> Int) : this(
        mover,
        intervalSeconds,
        appStatsOf(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { delay(it) }
    )

    val stats: StateFlow<DeviceStats?> = flow {
        var previous: DeviceStatsProbe.CpuTimes? = null
        while (true) {
            val shell = mover.read(DeviceStatsProbe.SCRIPT)?.let(DeviceStatsProbe::parse)
            val cpuPercent = DeviceStatsProbe.cpuPercent(previous, shell?.cpu)
            previous = shell?.cpu
            emit(
                appStats().copy(
                    cpuPercent = cpuPercent,
                    cpuMaxKhz = shell?.cpuMaxKhz,
                    gpuPercent = shell?.gpuPercent,
                    gpuHz = shell?.gpuHz,
                    cpuTempC = shell?.cpuTempC,
                    gpuTempC = shell?.gpuTempC,
                    fanRpm = shell?.fanRpm
                )
            )
            sleep(nextPollDelayMs(intervalSeconds(), cpuPending = cpuPercent == null && shell?.cpu != null))
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    /**
     * [stats] while [lifecycle] is resumed and [displayLit] says the surface's display is on, else null (and unsubscribed).
     * Switching is driven by the lifecycle's state flow and the display callbacks, not by recomposition: Android stops a
     * window's frames when it stops or its screen goes off, so a gate read during composition would never run again to let go.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun whileShown(lifecycle: Lifecycle, displayLit: Flow<Boolean>): Flow<DeviceStats?> =
        combine(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.RESUMED) }, displayLit) { resumed, lit -> resumed && lit }
            .distinctUntilChanged()
            .flatMapLatest { shown -> if (shown) stats else flowOf(null) }

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
