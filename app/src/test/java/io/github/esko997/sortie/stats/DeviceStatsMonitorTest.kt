package io.github.esko997.sortie.stats

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.esko997.sortie.shell.DeviceCaptures
import io.github.esko997.sortie.shell.DeviceStatsProbe
import io.github.esko997.sortie.shell.ScreenMover
import io.github.esko997.sortie.shell.ShellExec
import io.github.esko997.sortie.shell.SortieContext
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * The polling gate with no Compose at all, as on a device whose window stopped drawing: only the lifecycle and the display
 * state change, and the shell probe must stop and restart on those events alone. Polls are driven one by one: the monitor's
 * wait between polls blocks on [ticks] until the test releases it.
 */
class DeviceStatsMonitorTest {
    private val probes = AtomicInteger()
    private val shell = object : ShellExec {
        override fun exec(cmd: String): String {
            if (cmd == DeviceStatsProbe.SCRIPT) probes.incrementAndGet()
            return "0\n" + DeviceCaptures.text("thor/device-stats/probe-1.txt")
        }

        override fun restorePinnedTask(rootTaskId: Int): String = "0\n"
    }
    private val ticks = Channel<Unit>()
    private val waiting = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val monitor = DeviceStatsMonitor(ScreenMover(SortieContext(), shell, emptySet()), { 2 }, { DeviceStats(batteryTempC = 24) }, scope) {
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

    /** What the surface shows: written by the collecting coroutine, as the Compose state is. */
    private val shown = MutableStateFlow<DeviceStats?>(null)

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
