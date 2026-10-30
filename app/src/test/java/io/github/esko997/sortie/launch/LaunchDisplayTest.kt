package io.github.esko997.sortie.launch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which display a game launch goes to: the profile's screen rule, unless the player picked the other screen. */
class LaunchDisplayTest {
    private val top = 0
    private val bottom = 4

    @Test
    fun aPickedScreenWinsOverEveryProfileRule() {
        for (rule in listOf("top", "bottom", "same")) {
            // navigation on top: the other screen is the bottom one, even for a top-screen emulator
            assertEquals(rule, bottom, launchDisplay(rule, bottom, top, bottom, navigationBottom = false, scoped = false))
            // navigation on the bottom: the other screen is the top one, even for a bottom-screen emulator
            assertEquals(rule, top, launchDisplay(rule, top, bottom, bottom, navigationBottom = true, scoped = false))
        }
    }

    @Test
    fun withoutAPickTheProfileRuleDecides() {
        assertEquals(top, launchDisplay("top", null, bottom, bottom, navigationBottom = true, scoped = false))
        assertEquals(bottom, launchDisplay("bottom", null, top, bottom, navigationBottom = false, scoped = false))
        assertNull("no second screen: the system places it", launchDisplay("bottom", null, top, null, navigationBottom = false, scoped = false))
    }

    @Test
    fun sameMeansTheTopScreenForANavigationConfiguredOnTheBottom() {
        assertEquals(top, launchDisplay("same", null, bottom, bottom, navigationBottom = true, scoped = false))
        assertEquals("a built-in's window keeps its screen", bottom, launchDisplay("same", null, bottom, bottom, navigationBottom = true, scoped = true))
        assertEquals("navigation configured on top", bottom, launchDisplay("same", null, bottom, bottom, navigationBottom = false, scoped = false))
        assertEquals("one screen", top, launchDisplay("same", null, top, null, navigationBottom = true, scoped = false))
    }
}
