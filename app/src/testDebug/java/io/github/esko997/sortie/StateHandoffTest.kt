package io.github.esko997.sortie

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The in-memory saved state a screen move hands from the closing instance to the one it starts (navigation X, built-in move). */
@RunWith(RobolectricTestRunner::class)
class StateHandoffTest {
    private var now = 1_000L
    private val handoff = StateHandoff { now }

    private fun state(v: Int) = Bundle().apply { putInt("v", v) }

    @Test
    fun onlyTheStartedInstanceTakesTheStateAndOnlyOnce() {
        val started = handoff.offer(Intent(), state(7))
        // an unrelated start (a Home press, a system recreation's intent) gets nothing and leaves the state waiting
        assertNull(handoff.take(Intent()))
        assertEquals(7, handoff.take(started)!!.getInt("v"))
        assertFalse(started.hasExtra(StateHandoff.EXTRA))
        assertNull(handoff.take(started))
    }

    @Test
    fun aLaterMoveReplacesTheSlotAndAnOldOneExpires() {
        val first = handoff.offer(Intent(), state(1))
        now += 5
        val second = handoff.offer(Intent(), state(2))
        assertNull(handoff.take(first))
        assertEquals(2, handoff.take(second)!!.getInt("v"))

        val late = handoff.offer(Intent(), state(3))
        now += StateHandoff.TTL_MS + 1
        assertNull(handoff.take(late))
    }
}
