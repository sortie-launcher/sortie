package io.github.esko997.sortie

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import io.github.esko997.sortie.shell.ScreenMover
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 4 smoke tests (docs/HARDENING.md, Testing): Sortie as HOME on an emulator with a simulated second screen. Run them with
 * tools/emulator/smoke.sh, which prepares a disposable device (overlay display, fixture card from tools/emulator/card, grants,
 * HOME) and refuses anything that is not an emulator. Every check reads what the system or the player sees: the task list
 * (`cmd activity stack list`), the focused display (`dumpsys input`) and the accessibility tree on a given display.
 *
 * Keys are injected the way the controller sends them, to whichever display has input focus, so a companion that fails to hand
 * the controller back shows up as a swap or Home press that never arrives.
 */
class DualScreenSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** The second screen, chosen like the app does: device.json's presentationIndex (0) among presentation displays. */
    private val second: Int by lazy {
        val dm = instrumentation.targetContext.getSystemService(DisplayManager::class.java)
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?.displayId
            ?: throw AssertionError("No presentation display: run tools/emulator/smoke.sh, which creates the overlay display")
    }

    @Before
    fun backToHome() {
        Configurator.getInstance().waitForIdleTimeout = 1_000 // Home's ambient background never goes idle for long
        shell("input keyevent KEYCODE_WAKEUP")
        // A failed test may leave navigation below: X there brings it back up (keys sent to that display explicitly).
        if (mainTasks().any { it.displayId == second && it.visible }) {
            shell("input -d $second keyevent KEYCODE_BUTTON_X")
            await("navigation back on the primary display") { mainTasks().any { it.displayId == Display.DEFAULT_DISPLAY } }
        }
        shell("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_HOME")
        // Home keeps the current route; Back walks it back to Home, where Back does nothing.
        var presses = 0
        while (presses++ < 6 && !device.hasObject(homeHeader(Display.DEFAULT_DISPLAY))) {
            shell("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
            SystemClock.sleep(700)
        }
        awaitNavigationOn(Display.DEFAULT_DISPLAY)
    }

    @Test
    fun homeShowsNavigationOnThePrimaryDisplay() {
        val main = mainTasks().single()
        assertEquals(Display.DEFAULT_DISPLAY, main.displayId)
        assertTrue("MainActivity task is not visible: ${describe()}", main.visible)
        await("the controller on the primary display") { focusedDisplay() == Display.DEFAULT_DISPLAY }
    }

    @Test
    fun companionFillsTheSecondDisplay() {
        await("SecondaryHomeActivity visible on display $second") {
            tasks().any { it.component == "$PKG/.SecondaryHomeActivity" && it.displayId == second && it.visible }
        }
        await("a Sortie window drawn on display $second") { device.hasObject(By.pkg(PKG).displayId(second)) }
        assertTrue("navigation must stay on the primary display: ${describe()}", mainTasks().none { it.displayId == second })
    }

    @Test
    fun swapMovesNavigationToTheOtherDisplayAndBack() {
        shell("input keyevent KEYCODE_BUTTON_X")
        awaitNavigationOn(second)
        await("CompanionActivity covering the primary display") {
            tasks().any { it.component == "$PKG/.CompanionActivity" && it.displayId == Display.DEFAULT_DISPLAY && it.visible }
        }
        await("the controller following navigation to display $second") { focusedDisplay() == second }

        shell("input keyevent KEYCODE_BUTTON_X")
        awaitNavigationOn(Display.DEFAULT_DISPLAY)
        await("the primary display's companion closed") { tasks().none { it.component == "$PKG/.CompanionActivity" } }
        await("SecondaryHomeActivity back on display $second") {
            tasks().count { it.component == "$PKG/.SecondaryHomeActivity" && it.displayId == second && it.visible } == 1
        }
        await("the controller back on the primary display") { focusedDisplay() == Display.DEFAULT_DISPLAY }
        await("no lingering AnchorActivity") { tasks().none { it.component == "$PKG/.AnchorActivity" } }
    }

    @Test
    fun homeFromAnotherAppReturnsToSortie() {
        shell("am start -W --display ${Display.DEFAULT_DISPLAY} -a android.settings.SETTINGS")
        await("Settings in front on the primary display") {
            val shown = tasks().filter { it.displayId == Display.DEFAULT_DISPLAY && it.visible }
            shown.any { it.pkg == SETTINGS } && shown.none { it.component == MAIN }
        }
        val before = mainTasks().single().id
        shell("input keyevent KEYCODE_HOME")
        awaitNavigationOn(Display.DEFAULT_DISPLAY)
        await("Settings hidden behind Sortie") { tasks().none { it.pkg == SETTINGS && it.displayId == Display.DEFAULT_DISPLAY && it.visible } }
        assertEquals("Home must return to the existing navigation task", before, mainTasks().single().id)
    }

    @Test
    fun backOnHomeStaysWhenSortieIsTheHomeApp() {
        val before = mainTasks().single()
        shell("input keyevent KEYCODE_BACK")
        SystemClock.sleep(SETTLE_MS)
        assertNavigationStays(before)
    }

    /**
     * Started from an icon or another launcher, as on a device where Sortie is not HOME, the navigation task is an ordinary
     * one, and Android 12+ moves an ordinary root task to the back on Back. A HOME task ignores that, which is why this
     * regression (docs/HARDENING.md, "Back on Home sent Sortie away") never showed on a device using Sortie as its HOME.
     */
    @Test
    fun backOnHomeStaysInAnOrdinaryTask() {
        val launcher = otherHomeActivity()
        assumeTrue("no other HOME app on this device", launcher != null)
        val launcherPackage = launcher!!.substringBefore('/')
        shell("cmd package set-home-activity $launcher")
        try {
            removeMainTasks()
            shell("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_HOME")
            await("the other launcher on the primary display") {
                tasks().any { it.pkg == launcherPackage && it.displayId == Display.DEFAULT_DISPLAY && it.visible }
            }
            shell("am start -W --display ${Display.DEFAULT_DISPLAY} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $MAIN")
            awaitNavigationOn(Display.DEFAULT_DISPLAY)
            val before = mainTasks().single()
            shell("input keyevent KEYCODE_BACK")
            SystemClock.sleep(SETTLE_MS)
            assertNavigationStays(before)
        } finally {
            shell("cmd package set-home-activity $MAIN")
            removeMainTasks()
            shell("am force-stop $launcherPackage") // its tasks would share Sortie's HOME root task and look visible with it
            shell("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_HOME")
            awaitNavigationOn(Display.DEFAULT_DISPLAY)
        }
    }

    /** Shelf → the issue it focuses → that issue's first article, with the controller only (A). */
    @Test
    fun magazineReaderOpensAnArticle() {
        val bundled = instrumentation.targetContext.assets.list("magazines").orEmpty()
        assumeTrue("built without -PsortieMagazinesDir: no magazines to read", "catalog.json" in bundled)
        shell("am start -n $MAIN --es open magazines") // debug builds' route extra (MainActivity.handleIntent)
        awaitText("scrapbook", "the magazine shelf") // shelf hints: A open · Select scrapbook · B back
        shell("input keyevent KEYCODE_BUTTON_A")
        awaitText("shelf", "an issue's contents") // contents hints: A open · B shelf
        shell("input keyevent KEYCODE_BUTTON_A")
        awaitText("reader menu", "an article") // article hints: … Select reader menu · B contents
        assertEquals(Display.DEFAULT_DISPLAY, mainTasks().single().displayId)
    }

    // ---------- helpers ----------

    private fun assertNavigationStays(before: ScreenMover.RootTask) {
        val after = mainTasks()
        assertEquals("one navigation task after Back: ${describe()}", listOf(before.id), after.map { it.id })
        val top = tasks().firstOrNull { it.displayId == Display.DEFAULT_DISPLAY && it.visible }
        assertEquals("Back on Home must leave Sortie in front: ${describe()}", MAIN, top?.component)
        assertTrue("Home's header must still show after Back", device.hasObject(homeHeader(Display.DEFAULT_DISPLAY)))
    }

    /** Exactly one navigation task, visible on [display] and drawing Home's header there (the fixture card has 3 games). */
    private fun awaitNavigationOn(display: Int) {
        await("one visible MainActivity task on display $display") {
            mainTasks().let { it.size == 1 && it[0].displayId == display && it[0].visible }
        }
        await("Home (\"$GAME_COUNT\") drawn on display $display") { device.hasObject(homeHeader(display)) }
    }

    private fun homeHeader(display: Int): BySelector = By.pkg(PKG).text(GAME_COUNT).displayId(display)

    private fun awaitText(text: String, what: String) =
        await("$what (\"$text\") on the primary display") { device.hasObject(By.pkg(PKG).text(text).displayId(Display.DEFAULT_DISPLAY)) }

    /** Every task, top-most first per root task; components in short form (`pkg/.Activity`) whichever form the shell printed. */
    private fun tasks(): List<ScreenMover.RootTask> =
        ScreenMover.parseRootTasks(shell("cmd activity stack list")).map { it.copy(component = shortComponent(it.component)) }

    private fun mainTasks() = tasks().filter { it.component == MAIN }

    private fun removeMainTasks() {
        // leaf ids: Sortie's HOME task shares the HOME root task, and removing a root would take its other tasks too
        mainTasks().forEach { shell("am stack remove ${it.id}") }
        await("no MainActivity task") { mainTasks().isEmpty() }
    }

    private fun focusedDisplay(): Int? = Regex("FocusedDisplayId: (\\d+)").find(shell("dumpsys input"))?.groupValues?.get(1)?.toInt()

    /** A HOME activity of another app (the emulator's launcher), never Settings' FallbackHome. */
    private fun otherHomeActivity(): String? =
        shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().map { it.trim() }
            .filter { Regex("[\\w.]+/[\\w.$]+").matches(it) }
            .map(::shortComponent)
            .firstOrNull { !it.startsWith("$PKG/") && !it.startsWith("$SETTINGS/") }

    private fun shortComponent(component: String): String {
        val pkg = component.substringBefore('/')
        return "$pkg/" + component.substringAfter('/').let { if (it.startsWith("$pkg.")) it.removePrefix(pkg) else it }
    }

    private fun describe(): String = "tasks=" + tasks().joinToString { "${it.id}:${it.component}@${it.displayId}${if (it.visible) "+" else ""}" } +
        " focusedDisplay=${focusedDisplay()}"

    private fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (condition()) return
            if (SystemClock.uptimeMillis() > end) fail("Timed out after $timeoutMs ms waiting for $what; ${describe()}")
            SystemClock.sleep(250)
        }
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private companion object {
        const val PKG = "io.github.esko997.sortie"
        const val MAIN = "$PKG/.MainActivity"
        const val SETTINGS = "com.android.settings"
        const val GAME_COUNT = "3 games"
        const val SETTLE_MS = 2_000L
    }
}
