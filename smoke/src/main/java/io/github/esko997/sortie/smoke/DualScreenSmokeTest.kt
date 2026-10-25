package io.github.esko997.sortie.smoke

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Tier 4 smoke tests (docs/HARDENING.md, Testing): Sortie as HOME on an emulator with a second screen. Run them with
 * tools/emulator/smoke.sh, which prepares a disposable emulator (second display, fixture card from tools/emulator/card, grants,
 * HOME) and refuses real devices. Every check reads what the system or the player sees: the task list
 * (`cmd activity stack list`), the focused display (`dumpsys input`) and the accessibility tree of a given display.
 *
 * Keys are injected the way the controller sends them, to whichever display has input focus, so a companion that fails to hand
 * the controller back shows up as a swap or Home press that never arrives.
 */
class DualScreenSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)

    /** The second screen, chosen like the app does: device.json's presentationIndex (0) among presentation displays. */
    private val second: Int by lazy {
        val dm = instrumentation.context.getSystemService(DisplayManager::class.java)
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()?.displayId
            ?: throw AssertionError("No presentation display: run tools/emulator/smoke.sh, which adds the second display")
    }

    @Before
    fun backToHome() {
        Configurator.getInstance().waitForIdleTimeout = 1_000 // Home's ambient background never goes idle for long
        shell("input keyevent KEYCODE_WAKEUP")
        // A failed test may leave navigation below: X there brings it back up (keys sent to that display explicitly).
        if (mainTasks().any { it.display == second && it.visible }) {
            shell("input -d $second keyevent KEYCODE_BUTTON_X")
            await("navigation back on the primary display") { mainTasks().any { it.display == PRIMARY && it.visible } }
        }
        shell("input -d $PRIMARY keyevent KEYCODE_HOME")
        // Home keeps the current route; Back walks it back to Home, where Back does nothing.
        var presses = 0
        while (presses++ < 6 && !device.hasObject(homeHeader(PRIMARY))) {
            shell("input -d $PRIMARY keyevent KEYCODE_BACK")
            SystemClock.sleep(700)
        }
        awaitNavigationOn(PRIMARY)
    }

    @Test
    fun homeShowsNavigationOnThePrimaryDisplay() {
        await("the controller on the primary display") { focusedDisplay() == PRIMARY }
    }

    @Test
    fun companionFillsTheSecondDisplay() {
        await("SecondaryHomeActivity on display $second") { tasks().any { it.component == SECONDARY_HOME && it.display == second && it.visible } }
        await("a Sortie window drawn on display $second") { device.hasObject(By.pkg(PKG).displayId(second)) }
        assertTrue("Home's header must not show on the companion's display: ${describe()}", !device.hasObject(homeHeader(second)))
    }

    @Test
    fun swapMovesNavigationToTheOtherDisplayAndBack() {
        shell("input keyevent KEYCODE_BUTTON_X")
        awaitNavigationOn(second)
        await("CompanionActivity covering the primary display") {
            tasks().any { it.component == COMPANION && it.display == PRIMARY && it.visible } && device.hasObject(By.pkg(PKG).displayId(PRIMARY))
        }
        await("the controller following navigation to display $second") { focusedDisplay() == second }
        // Paced like a person: a second X within 1.5 s of the companion's hand-off leaves the controller on the companion's
        // screen (TaskSwitcher.anchoredRecently suppresses the returning companion's hand-off; see docs/HARDENING.md, Testing).
        SystemClock.sleep(SETTLE_MS)

        shell("input keyevent KEYCODE_BUTTON_X")
        awaitNavigationOn(PRIMARY)
        await("the primary display's companion closed") { tasks().none { it.component == COMPANION } }
        await("the companion back on display $second") {
            tasks().any { it.component == SECONDARY_HOME && it.display == second && it.visible } && !device.hasObject(homeHeader(second))
        }
        await("the controller back on the primary display") { focusedDisplay() == PRIMARY }
        await("no lingering AnchorActivity") { tasks().none { it.component == ANCHOR } }
    }

    @Test
    fun homeFromAnotherAppReturnsToSortie() {
        val before = mainTasks().single().id
        shell("am start -W --display $PRIMARY -a android.settings.SETTINGS")
        await("Settings in front on the primary display") {
            val shown = tasks().filter { it.display == PRIMARY && it.visible }
            shown.any { it.pkg == SETTINGS } && shown.none { it.component == MAIN }
        }
        shell("input keyevent KEYCODE_HOME")
        awaitNavigationOn(PRIMARY)
        await("Settings hidden behind Sortie") { tasks().none { it.pkg == SETTINGS && it.display == PRIMARY && it.visible } }
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
            shell("input -d $PRIMARY keyevent KEYCODE_HOME")
            await("the other launcher on the primary display") { tasks().any { it.pkg == launcherPackage && it.display == PRIMARY && it.visible } }
            shell("am start -W --display $PRIMARY -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $MAIN")
            awaitNavigationOn(PRIMARY)
            val before = mainTasks().single()
            shell("input keyevent KEYCODE_BACK")
            SystemClock.sleep(SETTLE_MS)
            assertNavigationStays(before)
        } finally {
            shell("cmd package set-home-activity $MAIN")
            removeMainTasks()
            shell("am force-stop $launcherPackage") // its tasks would share Sortie's HOME root task and look visible with it
            shell("input -d $PRIMARY keyevent KEYCODE_HOME")
            awaitNavigationOn(PRIMARY)
        }
    }

    /** Shelf → the issue it focuses → that issue's first article, with the controller only (D-pad and A). */
    @Test
    fun magazineReaderOpensAnArticle() {
        val bundled = instrumentation.context.createPackageContext(PKG, 0).assets.list("magazines").orEmpty()
        assumeTrue("built without -PsortieMagazinesDir: no magazines to read", "catalog.json" in bundled)
        shell("am start -n $MAIN --es open magazines") // debug builds' route extra (MainActivity.handleIntent)
        awaitText("scrapbook", "the magazine shelf") // shelf hints: A open · Select scrapbook · B back
        shell("input keyevent KEYCODE_BUTTON_A")
        awaitText("shelf", "an issue's contents") // contents hints: A open · B shelf
        // Contents open on the masthead, then the editor's note: passages the D-pad scrolls through 72 dp at a time and that
        // open nothing. Down walks to the first entry that is a button, the first article.
        val focusedButton = By.pkg(PKG).displayId(PRIMARY).focused(true).clickable(true)
        var downs = 0
        while (downs++ < 40 && !device.hasObject(focusedButton)) {
            shell("input keyevent KEYCODE_DPAD_DOWN")
            SystemClock.sleep(300)
        }
        await("the first article focused in the contents", detail = { "it shows ${texts(PRIMARY)}" }) { device.hasObject(focusedButton) }
        shell("input keyevent KEYCODE_BUTTON_A")
        awaitText("reader menu", "an article") // article hints: … Select reader menu · B contents
        assertEquals(PRIMARY, mainTasks().single().display)
    }

    // ---------- helpers ----------

    /** One leaf task of `cmd activity stack list`; [visible] is its root task's, shared by every task in that root. */
    private data class Task(val root: Int, val id: Int, val display: Int, val component: String, val visible: Boolean) {
        val pkg: String get() = component.substringBefore('/')
    }

    private fun assertNavigationStays(before: Task) {
        assertEquals("one navigation task after Back: ${describe()}", listOf(before.id), mainTasks().map { it.id })
        val top = tasks().firstOrNull { it.display == PRIMARY && it.visible }
        assertEquals("Back on Home must leave Sortie in front: ${describe()}", MAIN, top?.component)
        assertTrue("Home's header must still show after Back", device.hasObject(homeHeader(PRIMARY)))
    }

    /** Exactly one navigation task, visible on [display] and drawing Home's header there (the fixture card has 3 games). */
    private fun awaitNavigationOn(display: Int) {
        await("one visible MainActivity task on display $display") { mainTasks().let { it.size == 1 && it[0].display == display && it[0].visible } }
        await("Home (\"$GAME_COUNT\") drawn on display $display") { device.hasObject(homeHeader(display)) }
    }

    private fun homeHeader(display: Int): BySelector = By.pkg(PKG).text(GAME_COUNT).displayId(display)

    private fun awaitText(text: String, what: String) =
        await("$what (\"$text\") on the primary display", detail = { "it shows ${texts(PRIMARY)}; ${describe()}" }) {
            device.hasObject(By.pkg(PKG).text(text).displayId(PRIMARY))
        }

    /** Sortie's texts on [display], for failure messages. */
    private fun texts(display: Int): List<String> =
        device.findObjects(By.pkg(PKG).displayId(display)).mapNotNull { it.text?.takeIf(String::isNotBlank) }.take(30)

    /** Every leaf task, top-most first; components in short form (`pkg/.Activity`) whichever form the shell printed. */
    private fun tasks(): List<Task> {
        val tasks = ArrayList<Task>()
        var root: Pair<Int, Int>? = null
        for (line in shell("cmd activity stack list").lineSequence()) {
            ROOT_LINE.find(line)?.let { root = it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            val (rootId, display) = root ?: continue
            TASK_LINE.find(line)?.let { m ->
                tasks += Task(rootId, m.groupValues[1].toInt(), display, shortComponent(m.groupValues[2]), m.groupValues[3] == "true")
            }
        }
        return tasks
    }

    private fun mainTasks() = tasks().filter { it.component == MAIN }

    private fun removeMainTasks() {
        // leaf ids: Sortie's HOME task shares the HOME root task, and removing a root would take its other tasks too
        mainTasks().forEach { shell("am stack remove ${it.id}") }
        await("no MainActivity task") { mainTasks().isEmpty() }
    }

    private fun focusedDisplay(): Int? = Regex("FocusedDisplayId: (\\d+)").find(shell("dumpsys input"))?.groupValues?.get(1)?.toInt()

    /** A HOME activity of another app (the emulator's launcher), never Settings' FallbackHome. */
    private fun otherHomeActivity(): String? = shell("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
        .lineSequence().map { it.trim() }
        .filter { COMPONENT.matches(it) }
        .map(::shortComponent)
        .firstOrNull { !it.startsWith("$PKG/") && !it.startsWith("$SETTINGS/") }

    private fun shortComponent(component: String): String {
        val pkg = component.substringBefore('/')
        return "$pkg/" + component.substringAfter('/').let { if (it.startsWith("$pkg.")) it.removePrefix(pkg) else it }
    }

    private fun describe(): String = "tasks=" + tasks().joinToString { "${it.id}:${it.component}@${it.display}${if (it.visible) "+" else ""}" } +
        " focusedDisplay=${focusedDisplay()}"

    private fun await(what: String, timeoutMs: Long = 20_000, detail: () -> String = ::describe, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) fail("Timed out after $timeoutMs ms waiting for $what; ${detail()}")
            SystemClock.sleep(250)
        }
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private companion object {
        const val PRIMARY = Display.DEFAULT_DISPLAY
        const val PKG = "io.github.esko997.sortie"
        const val MAIN = "$PKG/.MainActivity"
        const val SECONDARY_HOME = "$PKG/.SecondaryHomeActivity"
        const val COMPANION = "$PKG/.CompanionActivity"
        const val ANCHOR = "$PKG/.AnchorActivity"
        const val SETTINGS = "com.android.settings"
        const val GAME_COUNT = "3 games"
        const val SETTLE_MS = 2_000L
        val ROOT_LINE = Regex("""^\s*RootTask id=(\d+)\b.*\bdisplayId=(\d+)""")
        val TASK_LINE = Regex("""taskId=(\d+): ([\w.]+/[\w.$]+).*visible=(true|false)""")
        val COMPONENT = Regex("""[\w.]+/[\w.$]+""")
    }
}
