package io.github.esko997.sortie.shell

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.esko997.sortie.ui.Hotkeys

/**
 * The app switcher as overlay windows (`TYPE_APPLICATION_OVERLAY`, one per display) instead of activities, so the running
 * games stay visible and running underneath. Needs the draw-over-apps appop (`appops set <pkg> SYSTEM_ALERT_WINDOW allow`);
 * without it [available] is false and the activity-based switcher is used. The window on [focusDisplay] is focusable and gets
 * the controller; the other one is touch only. Keys: D-pad/A go to Compose focus inside the window, Y/Select through
 * [Hotkeys] like everywhere else, B closes.
 */
class SwitcherOverlay(private val context: Context) {
    private class Window(
        val displayId: Int,
        val manager: WindowManager,
        val view: View,
        val owner: Owner,
        val params: WindowManager.LayoutParams,
        val controller: Boolean
    )

    private val windows = mutableMapOf<Int, Window>()

    val available: Boolean get() = Settings.canDrawOverlays(context)
    val shown: Boolean get() = windows.isNotEmpty()

    /**
     * Shows [content] on every display in [displayIds]; the window on [focusDisplay] takes the controller. B closes through
     * [onClose]; Y/Select are mapped with [Hotkeys.keyFor] and handed to [onHotkey] (not the global bus: Sortie's own screens
     * may be composed underneath and must not react).
     */
    fun show(
        displayIds: List<Int>,
        focusDisplay: Int,
        onClose: () -> Unit,
        onHotkey: (Hotkeys.Key) -> Unit,
        content: @Composable (displayId: Int, controller: Boolean) -> Unit
    ) {
        hide()
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        for (id in displayIds) {
            val display = dm.getDisplay(id) ?: continue
            val controller = id == focusDisplay
            runCatching {
                val wc = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                val wm = wc.getSystemService(WindowManager::class.java)
                val owner = Owner()
                // A key listener never fires while the AndroidComposeView child has the focus; intercept in the root's dispatch.
                val root = object : FrameLayout(wc) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (!controller || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return super.dispatchKeyEvent(event)
                        return when (event.keyCode) {
                            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                                onClose()
                                true
                            }
                            else -> Hotkeys.keyFor(event.keyCode)?.let { key ->
                                onHotkey(key)
                                true
                            } ?: super.dispatchKeyEvent(event)
                        }
                    }
                }
                root.setViewTreeLifecycleOwner(owner)
                root.setViewTreeSavedStateRegistryOwner(owner)
                val compose = ComposeView(wc).apply { setContent { content(id, controller) } }
                root.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        (if (controller) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
                    PixelFormat.TRANSLUCENT
                ).apply { title = "Sortie switcher" }
                wm.addView(root, params)
                owner.start()
                windows[id] = Window(id, wm, root, owner, params, controller)
            }.onFailure { Log.w(TAG, "overlay on display $id", it) }
        }
    }

    /**
     * The touch-only window must take input focus while its app picker is open, or the IME attaches and is dropped at once.
     * [displayId] null restores every window to its controller policy.
     */
    fun allowTextInput(displayId: Int?) {
        for (w in windows.values) {
            val focusable = w.controller || w.displayId == displayId
            val notFocusable = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            val flags = if (focusable) w.params.flags and notFocusable.inv() else w.params.flags or notFocusable
            if (flags == w.params.flags) continue
            w.params.flags = flags
            runCatching { w.manager.updateViewLayout(w.view, w.params) }.onFailure { Log.w(TAG, "focusable ${w.displayId}", it) }
        }
    }

    fun hide() {
        for (w in windows.values) {
            runCatching { w.manager.removeViewImmediate(w.view) }.onFailure { Log.w(TAG, "remove overlay ${w.displayId}", it) }
            w.owner.stop()
        }
        windows.clear()
    }

    /** Lifecycle + saved-state owner for a ComposeView that lives in a window of its own, outside any Activity. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        init {
            savedState.performRestore(null as Bundle?)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private const val TAG = "SwitcherOverlay"
    }
}
