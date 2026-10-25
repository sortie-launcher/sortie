package io.github.esko997.sortie.ui

import java.util.Collections
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The companion's hero rotation with no Compose at all: only the shown state changes (the companion stops, or its screen goes
 * off), and the 20 s timer must stop and restart on that alone. Waits are driven one by one through [ticks].
 */
class HeroTickerTest {
    private val ticks = Channel<Unit>()
    private val waiting = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shown = MutableStateFlow(false)
    private val seen: MutableList<Int> = Collections.synchronizedList(ArrayList())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun rotatesOnlyWhileShown() = runBlocking {
        scope.launch {
            heroTicker(shown) { ms ->
                assertEquals(Selection.HERO_ROTATION_MS, ms)
                waiting.incrementAndGet()
                try {
                    ticks.receive()
                } finally {
                    waiting.decrementAndGet()
                }
            }.collect { seen += it }
        }
        // hidden from the start (composed before resume): no hero step and no timer
        delay(200)
        assertEquals(emptyList<Int>(), seen.toList())
        assertEquals(0, waiting.get())

        shown.value = true
        assertTrue(within { seen.toList() == listOf(0) && waiting.get() == 1 })
        ticks.send(Unit)
        assertTrue(within { seen.toList() == listOf(0, 1) && waiting.get() == 1 })

        // stopped or screen off: the timer goes with it
        shown.value = false
        assertTrue("timer outlived the companion", within { waiting.get() == 0 })
        assertFalse("a tick found a timer", ticks.trySend(Unit).isSuccess)
        delay(200)
        assertEquals(listOf(0, 1), seen.toList())

        // back in front: the same hero again (no jump), then the rotation resumes
        shown.value = true
        assertTrue(within { seen.toList() == listOf(0, 1, 1) && waiting.get() == 1 })
        ticks.send(Unit)
        assertTrue(within { seen.toList() == listOf(0, 1, 1, 2) })
    }

    private suspend fun within(condition: () -> Boolean): Boolean = withTimeoutOrNull(5_000) {
        while (!condition()) delay(10)
        true
    } ?: false
}
