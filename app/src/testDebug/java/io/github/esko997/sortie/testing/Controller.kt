package io.github.esko997.sortie.testing

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A game controller as Android delivers it: key events into the focused window (the activity, or a dialog over it), through the
 * view hierarchy and Compose focus, and on to the activity's own key handling (Sortie's hotkeys) when nothing consumes them.
 * Buttons nothing handles are delivered again as their fallback key from Android's generic controller key map (Generic.kcm),
 * as the input dispatcher does on the devices: A as DPAD_CENTER (a click), B as BACK, Select as MENU.
 *
 * Robolectric does not run ViewRootImpl's input stages, so this does the one thing they do that matters here: the first D-pad
 * press after touch only leaves touch mode, which moves view focus into the window (FOCUS_DOWN) and is consumed. Windows that
 * open afterwards (dialogs) start out of touch mode, as they would on the device.
 */
class Controller {
    private var touchMode = true

    fun press(vararg codes: Int) = codes.forEach(::press)

    private fun press(code: Int) {
        val root = keyWindow()
        if (root.isInTouchMode) {
            if (!touchMode) {
                leaveTouchMode(root) // a window opened after the controller was in use
            } else if (code in DPAD) {
                leaveTouchMode(root)
                touchMode = false
                idle()
                return
            }
        }
        fun send(action: Int, key: Int): Boolean {
            val t = SystemClock.uptimeMillis()
            val handled = root.dispatchKeyEvent(KeyEvent(t, t, action, key, 0))
            idle()
            return handled
        }
        // Android's generic controller key map: a button nothing handled is delivered again as its fallback key
        val handled = send(KeyEvent.ACTION_DOWN, code)
        val fallback = FALLBACK[code]?.takeUnless { handled }
        fallback?.let { send(KeyEvent.ACTION_DOWN, it) }
        send(KeyEvent.ACTION_UP, code)
        fallback?.let { send(KeyEvent.ACTION_UP, it) }
    }

    /** The text (or content description) of the node with the controller focus in the window that gets key input. */
    fun focusedLabel(): String? {
        val owner = composeRoots(keyWindow()).firstOrNull() ?: return null
        fun find(node: SemanticsNode): SemanticsNode? =
            node.takeIf { it.config.getOrNull(SemanticsProperties.Focused) == true } ?: node.children.firstNotNullOfOrNull(::find)
        val node = find(owner.semanticsOwner.rootSemanticsNode) ?: return null
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
        return description ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
    }

    private fun idle() = InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    /** The window that gets key input: the newest focusable window of the highest layer, as Robolectric's own injection picks. */
    private fun keyWindow(): View {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        fun list(name: String) = global.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(global) as List<*>
        val roots = list("mRoots")
        val params = list("mParams").map { it as WindowManager.LayoutParams }
        val i = params.indices.filter { params[it].flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0 }
            .maxWithOrNull(compareBy({ params[it].type }, { it })) ?: error("no window takes key input")
        return roots[i]!!.javaClass.getMethod("getView").invoke(roots[i]) as View
    }

    private fun leaveTouchMode(root: View) {
        val impl = View::class.java.getMethod("getViewRootImpl").invoke(root) ?: return
        impl.javaClass.getDeclaredMethod("ensureTouchModeLocally", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(impl, false)
    }

    companion object {
        const val UP = KeyEvent.KEYCODE_DPAD_UP
        const val DOWN = KeyEvent.KEYCODE_DPAD_DOWN
        const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
        const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT
        const val A = KeyEvent.KEYCODE_BUTTON_A
        const val B = KeyEvent.KEYCODE_BUTTON_B
        const val SELECT = KeyEvent.KEYCODE_BUTTON_SELECT
        const val R2 = KeyEvent.KEYCODE_BUTTON_R2
        private val DPAD = setOf(UP, DOWN, LEFT, RIGHT)
        private val FALLBACK = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_MENU
        )
    }
}

private fun composeRoots(view: View): List<ViewRootForTest> = when (view) {
    is ViewRootForTest -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { composeRoots(view.getChildAt(it)) }
    else -> emptyList()
}
