package io.github.esko997.sortie

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * Saved instance state handed from an activity closing on one screen to the instance it starts on the other. Apps cannot move
 * their own task between displays, so a move is "finish here, start there"; the new instance takes the state as if it were a
 * recreation (back stack, rememberSaveable, view-model SavedStateHandle). Kept in process memory: no Binder size limit.
 * One slot per owner: the latest hand-off, until the instance it started takes it, and only briefly. Main thread.
 */
internal class StateHandoff(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private class Pending(val token: Long, val state: Bundle)

    private var pending: Pending? = null

    /** Keeps [state] for the instance [intent] is about to start; marks the intent with its token. */
    fun offer(intent: Intent, state: Bundle): Intent {
        val token = clock()
        pending = Pending(token, state)
        return intent.putExtra(EXTRA, token)
    }

    /** The state [offer] left for [intent]: only for that intent's token, and only within [TTL_MS]. Removes the token. */
    fun take(intent: Intent?): Bundle? {
        val token = intent?.getLongExtra(EXTRA, 0L) ?: 0L
        intent?.removeExtra(EXTRA)
        val p = pending ?: return null
        val expired = clock() - p.token > TTL_MS
        if (p.token == token || expired) pending = null
        return p.state.takeIf { p.token == token && !expired }
    }

    companion object {
        const val EXTRA = "handoff"
        const val TTL_MS = 10_000L
    }
}
