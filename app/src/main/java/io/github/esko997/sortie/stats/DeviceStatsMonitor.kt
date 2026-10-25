package io.github.esko997.sortie.stats

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.shell.ScreenMover
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * Polls device stats while at least one surface collects [stats], and not at all otherwise: the surfaces collect only while
 * resumed on a lit display ([statsPolling]), so with nothing shown there is no timer, no shell and no work. One batched
 * shell probe per poll through [ScreenMover] (Shizuku) plus two app-side reads (RAM from ActivityManager, the battery
 * temperature from the sticky battery broadcast) that work everywhere. The value resets to null when the last surface goes,
 * so a returning surface never shows numbers from minutes ago.
 */
class DeviceStatsMonitor(private val context: Context, private val mover: ScreenMover, private val intervalSeconds: () -> Int) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activityManager = context.getSystemService(ActivityManager::class.java)

    val stats: StateFlow<DeviceStats?> = flow {
        var previous: DeviceStatsProbe.CpuTimes? = null
        while (true) {
            val shell = mover.read(DeviceStatsProbe.SCRIPT)?.let(DeviceStatsProbe::parse)
            val cpuPercent = DeviceStatsProbe.cpuPercent(previous, shell?.cpu)
            previous = shell?.cpu
            val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
            emit(
                DeviceStats(
                    cpuPercent = cpuPercent,
                    cpuMaxKhz = shell?.cpuMaxKhz,
                    gpuPercent = shell?.gpuPercent,
                    gpuHz = shell?.gpuHz,
                    ramUsedBytes = (memory.totalMem - memory.availMem).takeIf { memory.totalMem > 0 },
                    ramTotalBytes = memory.totalMem.takeIf { it > 0 },
                    cpuTempC = shell?.cpuTempC,
                    gpuTempC = shell?.gpuTempC,
                    batteryTempC = batteryTempC(),
                    fanRpm = shell?.fanRpm
                )
            )
            delay(nextPollDelayMs(intervalSeconds(), cpuPending = cpuPercent == null && shell?.cpu != null))
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    /** Tenths of a degree in the sticky ACTION_BATTERY_CHANGED; reading it registers nothing. */
    private fun batteryTempC(): Int? {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else (tenths / 10.0).roundToInt().takeIf { it in -40..150 }
    }
}
