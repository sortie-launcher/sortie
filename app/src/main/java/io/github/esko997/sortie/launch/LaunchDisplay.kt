package io.github.esko997.sortie.launch

import android.view.Display

/**
 * The display a game launch goes to for its profile's [rule] ("top", "bottom" or "same"); null when it asks for the second
 * screen and there is none ([secondary] null), and the system places it. A display the player [picked] ("Play on the other
 * screen") wins over any rule. "same" always resolves to an explicit id: without one Android reuses the emulator's existing
 * task wherever it is (after a switcher move, on the other screen) instead of the screen the user is looking at. It is
 * [current], the asking window's display, except that a navigation launch on a dual-screen device configured with
 * [navigationBottom] goes to the top screen; a built-in's window ([scoped]) keeps its own screen.
 */
fun launchDisplay(rule: String, picked: Int?, current: Int, secondary: Int?, navigationBottom: Boolean, scoped: Boolean): Int? = when {
    picked != null -> picked
    rule == "top" -> Display.DEFAULT_DISPLAY
    rule == "bottom" -> secondary
    !scoped && secondary != null && navigationBottom -> Display.DEFAULT_DISPLAY
    else -> current
}
