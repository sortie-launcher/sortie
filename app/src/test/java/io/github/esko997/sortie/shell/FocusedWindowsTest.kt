package io.github.esko997.sortie.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which displays take keys, read from the input dispatcher's state in `dumpsys input` (Android 13's FocusResolver format). */
class FocusedWindowsTest {
    private fun dump(focusedWindows: String) = """
        Input Dispatcher State:
          DispatchEnabled: true
          FocusedDisplayId: 2
          FocusedApplications:
            displayId=0, name='ActivityRecord{5e1c2a8 u0 com.limelight/.Game t12}', dispatchingTimeout=5000ms
            displayId=2, name='ActivityRecord{77ac0f1 u0 io.github.esko997.sortie/.BuiltinActivity t15}', dispatchingTimeout=5000ms
        $focusedWindows
          FocusRequests:
            displayId=0, name='<null>' result='NO_WINDOW'
            displayId=2, name='3f1a9c io.github.esko997.sortie/io.github.esko997.sortie.BuiltinActivity' result='OK'
          Windows:
            displayId=0, name='Window{5e1c2a8 u0 com.limelight/com.limelight.Game}'
    """.trimIndent()

    @Test
    fun onlyDisplaysWithAFocusedWindowTakeKeysNotThoseWithJustAFocusedApp() {
        // stock per-display focus off: Moonlight's display 0 has a focused app but no focused window
        val stock = dump(
            "  FocusedWindows:\n" +
                "    displayId=2, name='3f1a9c io.github.esko997.sortie/io.github.esko997.sortie.BuiltinActivity'"
        )
        assertEquals(setOf(2), KeyInjector.focusedWindowDisplays(stock))
        val perDisplay = dump(
            "  FocusedWindows:\n" +
                "    displayId=0, name='8c02 com.limelight/com.limelight.Game'\n" +
                "    displayId=2, name='3f1a9c io.github.esko997.sortie/io.github.esko997.sortie.BuiltinActivity'"
        )
        assertEquals(setOf(0, 2), KeyInjector.focusedWindowDisplays(perDisplay))
    }

    @Test
    fun noFocusedWindowAnywhereIsEmptyAndAMissingSectionIsUnknown() {
        assertEquals(emptySet<Int>(), KeyInjector.focusedWindowDisplays(dump("  FocusedWindows: <none>")))
        assertNull(KeyInjector.focusedWindowDisplays(dump("")))
        assertNull(KeyInjector.focusedWindowDisplays(""))
    }
}
