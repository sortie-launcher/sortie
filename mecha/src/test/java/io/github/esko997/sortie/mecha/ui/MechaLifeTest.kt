package io.github.esko997.sortie.mecha.ui

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.esko997.sortie.mecha.MechaSlot
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * His life runs only while his window is in front. The gate is driven by the lifecycle's own state flow and the display
 * callbacks, on virtual time here: no Compose frame is ever produced or awaited, so a stopped window (which draws no frames)
 * cannot keep anything of his running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MechaLifeTest {
    private class Window : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun nothingTicksWhileStoppedPausedOrOnADarkDisplay() = runTest {
        val window = Window()
        window.registry.currentState = Lifecycle.State.RESUMED
        val lit = MutableStateFlow(true)
        var ticks = 0
        var running = 0
        val job = launch {
            whileShown(mechaShown(window.lifecycle, lit)) {
                running++
                try {
                    while (true) {
                        delay(200)
                        ticks++
                    }
                } finally {
                    running--
                }
            }
        }
        advanceTimeBy(1_001)
        assertEquals(5, ticks)
        assertEquals(1, running)

        // paused (a dialog or another window in front)
        window.registry.currentState = Lifecycle.State.STARTED
        runCurrent()
        assertEquals(0, running)
        var before = ticks
        advanceTimeBy(10 * 60_000L)
        assertEquals(before, ticks)

        // stopped (backgrounded behind a game)
        window.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(before, ticks)

        // back in front
        window.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(401)
        assertEquals(before + 2, ticks)

        // the screen goes dark while the window stays resumed
        lit.value = false
        runCurrent()
        assertEquals(0, running)
        before = ticks
        advanceTimeBy(10 * 60_000L)
        assertEquals(before, ticks)

        // lit again but stopped: still nothing
        window.registry.currentState = Lifecycle.State.CREATED
        lit.value = true
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        assertEquals(before, ticks)
        assertEquals(0, running)
        job.cancel()
    }

    @Test
    fun idleLifeOnlyProducesChangesAndBreathesSlowerAsleep() {
        val idle = MechaIdle(Random(7))
        var last = MechaRig(0)
        var total = 0L
        repeat(200) {
            val (rig, ms) = idle.next(asleep = false)
            assertNotEquals(last, rig)
            assertTrue(rig.frame == 0 || rig.frame == 1)
            // the legs never move while idling: he stays on his floor
            assertEquals(0, rig.dy(MechaSlot.LEGS))
            last = rig
            total += ms
        }
        // about one change a second or less
        assertTrue(total / 200 >= MechaIdle.BEAT_MS / 2)
        repeat(10) {
            val (rig, ms) = idle.next(asleep = true)
            assertEquals(MechaRigs.ASLEEP.frame, rig.frame)
            assertEquals(MechaIdle.SLEEP_BEAT_MS, ms)
        }
    }
}
