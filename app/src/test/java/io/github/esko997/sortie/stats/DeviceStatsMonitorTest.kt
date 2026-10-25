package io.github.esko997.sortie.stats

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.ShellExec
import io.github.esko997.sortie.shell.SortieContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The polling gate and cadence with no Compose at all, as on a device whose window stopped drawing: only the lifecycle, the
 * display state and the surfaces' subscriptions change, and the shell probe must stop, restart and change pace on those
 * events alone. Polls are driven one by one: the monitor's wait between polls blocks on [ticks] until the test releases it.
 */
class DeviceStatsMonitorTest {
    private val probes = AtomicInteger()
    private val details = AtomicInteger()

    /** CPU counters that move forward on every probe, so every poll after the first has a load and no warm-up wait. */
    private val shell = object : ShellExec {
        override fun exec(cmd: String): String {
            val detail = cmd == DeviceStatsProbe.DETAIL_SCRIPT
            if (!detail && cmd != DeviceStatsProbe.SCRIPT) return "0\n"
            val k = probes.incrementAndGet()
            if (detail) details.incrementAndGet()
            return "0\nstat cpu  ${100L * k} 0 0 ${900L * k} 0 0 0 0\nfreq cpu0 1555200\nfreq cpu7 1843200\ntemp cpuss-0 33700\n" +
                (if (detail) "temp xo-therm 33397\ntemp battery 26000\n" else "") + "fan 300\nend\n"
        }

        override fun restorePinnedTask(rootTaskId: Int): String = "0\n"
    }
    private val ticks = Channel<Unit>()
    private val waiting = AtomicInteger()
    private val delays: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val now = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitor = DeviceStatsMonitor(
        ScreenMover(SortieContext(), shell, emptySet()),
        intervalSeconds = { 5 },
        perfIntervalSeconds = { 2 },
        appStats = { DeviceStats(batteryTempC = 24) },
        scope = scope,
        clock = { now.addAndGet(1_000) }
    ) { ms ->
        delays += ms
        waiting.incrementAndGet()
        try {
            ticks.receive()
        } finally {
            waiting.decrementAndGet()
        }
    }
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val lit = MutableStateFlow(true)

    /** What the surfaces show: written by the collecting coroutines, as the Compose state is. */
    private val shown = MutableStateFlow<DeviceStats?>(null)
    private val perf = MutableStateFlow<PerfFrame?>(null)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun probeRunsOnlyWhileResumedOnALitDisplay() = runBlocking {
        owner.registry.currentState = Lifecycle.State.RESUMED
        val surface = scope.launch { monitor.whileShown(owner.lifecycle, lit).collect { shown.value = it } }

        awaitPolling(probes = 1)
        assertNotNull(shown.value)
        assertEquals(24, shown.value?.batteryTempC)
        ticks.send(Unit)
        awaitPolling(probes = 2)

        // stopped (Home, another app in front): no frame is drawn, the lifecycle event alone must stop the probe
        owner.registry.currentState = Lifecycle.State.CREATED
        awaitIdle(probes = 2)

        owner.registry.currentState = Lifecycle.State.RESUMED
        awaitPolling(probes = 3)

        // paused but visible (a dialog or the other screen in front) counts as hidden too
        owner.registry.currentState = Lifecycle.State.STARTED
        awaitIdle(probes = 3)
        owner.registry.currentState = Lifecycle.State.RESUMED
        awaitPolling(probes = 4)

        // the screen goes off while the surface stays resumed (the overlay window, the Thor's bottom screen switched off)
        lit.value = false
        awaitIdle(probes = 4)
        lit.value = true
        awaitPolling(probes = 5)

        // the surface leaves (its effect is cancelled): the last subscriber is gone, so is the poller
        surface.cancel()
        assertTrue("poller outlived its last surface", within { waiting.get() == 0 })
        delay(300)
        assertEquals(5, probes.get())
        assertEquals(0, details.get())
    }

    @Test
    fun aPerfViewSpeedsPollingUpAndAddsDetailsOnlyWhileShown() = runBlocking {
        owner.registry.currentState = Lifecycle.State.RESUMED
        val strips = scope.launch { monitor.whileShown(owner.lifecycle, lit).collect { shown.value = it } }
        awaitPolling(probes = 1)
        assertEquals(listOf(CPU_WARMUP_MS), delays.toList()) // the CPU's second sample
        ticks.send(Unit)
        awaitPolling(probes = 2)
        assertEquals(5_000L, delays.last()) // the stats interval
        assertNull(shown.value?.zones)

        // a Perf view opens in the middle of a 5 s wait: the wait ends at once, the next poll is detailed and carries history
        val page = scope.launch { monitor.perfWhileShown(owner.lifecycle, lit).collect { perf.value = it } }
        awaitPolling(probes = 3)
        assertEquals(1, details.get())
        assertEquals(2_000L, delays.last()) // the Perf interval
        val frame = within { perf.value != null }.let { perf.value }
        assertNotNull(frame)
        assertEquals(3, frame!!.history.times.size) // the two earlier polls, kept since the strips started, and this one
        assertEquals(listOf("cpuss-0", "xo-therm", "battery"), frame.stats.zones?.map { it.type })
        assertEquals(listOf(0, 7), frame.stats.coreKhz?.map { it.core })
        assertTrue(frame.history.has(PerfMetric.CPU_LOAD))
        ticks.send(Unit)
        awaitPolling(probes = 4)
        assertEquals(2, details.get())
        assertEquals(2_000L, delays.last())

        // the Perf view goes, the strips stay: back to plain probes at the stats interval
        page.cancelAndJoin() // its subscription is gone before the next poll looks
        ticks.send(Unit)
        awaitPolling(probes = 5)
        assertEquals(2, details.get())
        assertEquals(5_000L, delays.last())

        // hidden (stopped): no poller and no history; shown again (the Perf view alone), the history starts over
        owner.registry.currentState = Lifecycle.State.CREATED
        awaitIdle(probes = 5)
        strips.cancel()
        perf.value = null
        val again = scope.launch { monitor.perfWhileShown(owner.lifecycle, lit).collect { perf.value = it } }
        owner.registry.currentState = Lifecycle.State.RESUMED
        assertTrue(within { perf.value != null && waiting.get() == 1 })
        assertEquals(6, probes.get())
        assertEquals(3, details.get())
        assertEquals(1, perf.value!!.history.times.size)
        again.cancel()
        assertTrue("poller outlived its last surface", within { waiting.get() == 0 })
    }

    /** The probe has run [probes] times and the poller is waiting for the next tick; the surface shows a reading. */
    private suspend fun awaitPolling(probes: Int) {
        assertTrue("probe never reached $probes runs (at ${this.probes.get()})", within { this.probes.get() == probes && waiting.get() == 1 })
        assertTrue("surface shows nothing", within { shown.value != null })
    }

    /** The poller has gone (nothing waits for a tick), the surface shows nothing, and the probe count stays at [probes]. */
    private suspend fun awaitIdle(probes: Int) {
        assertTrue("poller still running", within { waiting.get() == 0 })
        assertTrue("surface still shows stats", within { shown.value == null })
        assertFalse("a tick found a poller", ticks.trySend(Unit).isSuccess)
        delay(300)
        assertEquals(probes, this.probes.get())
        assertEquals(0, waiting.get())
        assertNull(shown.value)
    }

    private suspend fun within(condition: () -> Boolean): Boolean = withTimeoutOrNull(5_000) {
        while (!condition()) delay(10)
        true
    } ?: false
}
