package io.github.esko997.sortie

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.esko997.sortie.keyboard.RemoteKeyboardOutput
import io.github.esko997.sortie.shell.ScreenMover
import kotlinx.coroutines.CoroutineScope

/** A visible touch keyboard's output to the other display. Moving or hiding it cancels pending input to the old target. */
@Stable
internal class KeyboardWindow(internal val mover: ScreenMover, private val scope: CoroutineScope) {
    val status get() = mover.status

    var target by mutableStateOf<Int?>(null)
        private set

    var output by mutableStateOf<RemoteKeyboardOutput?>(null)
        private set

    private var started = false
    private var linked: Int? = null

    internal fun start() {
        started = true
        connect()
    }

    internal fun stop() {
        started = false
        connect()
    }

    internal fun retarget(display: Int?) {
        target = display
        connect()
    }

    private fun connect() {
        val wanted = if (started) target else null
        if (wanted == linked) return
        output?.close()
        output = wanted?.let { RemoteKeyboardOutput(mover, it, scope) }
        linked = wanted
    }
}

/**
 * A touch-only QWERTY window. Android normally focuses only one display, so this window must never take input focus from
 * the app it types into. Physical controller input and the selected Android IME remain with that app, unchanged.
 */
class QwertyKeyboardActivity : BuiltinActivity(BuiltinApp.QWERTY_KEYBOARD) {
    private val session by lazy { KeyboardWindow((application as SortieApp).graph.screenMover, lifecycleScope) }
    override val keyboard: KeyboardWindow get() = session

    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = retarget()

        override fun onDisplayRemoved(displayId: Int) = retarget()

        override fun onDisplayChanged(displayId: Int) = retarget()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        super.onCreate(savedInstanceState)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displays, Handler(Looper.getMainLooper()))
        retarget()
    }

    override fun onStart() {
        super.onStart()
        retarget()
        session.start()
    }

    override fun onResume() {
        super.onResume()
        // Starting even a non-focusable activity changes Android's focused display. Hand it back without raising,
        // moving or restarting the destination app; the same anchor is used by the companion and switcher.
        if (!isFinishing) session.target?.let { (application as SortieApp).graph.switcher.anchor(it) }
    }

    override fun onStop() {
        session.stop()
        super.onStop()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displays)
        session.stop()
        super.onDestroy()
    }

    override fun leaving() = session.stop()

    private fun retarget() {
        if (!isDestroyed) session.retarget(BuiltinApps.otherDisplay(this, displayId()))
    }
}
