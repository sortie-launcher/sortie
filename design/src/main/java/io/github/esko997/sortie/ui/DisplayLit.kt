package io.github.esko997.sortie.ui

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether a display state draws: a display that is off or dozing (the whole device asleep, or the Thor's bottom screen
 * switched off) shows nothing, so nothing should be computed for it. On, VR and on-suspend draw; unknown (not reported yet)
 * is treated as on so a surface never stays blank by mistake.
 */
fun displayLit(state: Int): Boolean = when (state) {
    Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> false
    else -> true
}

/**
 * Whether [view]'s display is on, from display-change callbacks; on (unknown) while the view has no display yet. Driven by
 * the callbacks rather than by recomposition: a window whose screen went dark draws no frames, so a gate read during
 * composition would never run again to let go.
 */
fun displayLitFlow(view: View): Flow<Boolean> = callbackFlow {
    val display = view.display
    if (display == null) {
        send(true)
        awaitClose()
        return@callbackFlow
    }
    val manager = view.context.getSystemService(DisplayManager::class.java)
    val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display.displayId) trySend(displayLit(display.state))
        }
    }
    manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    send(displayLit(display.state))
    awaitClose { manager.unregisterDisplayListener(listener) }
}.distinctUntilChanged()
