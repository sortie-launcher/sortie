package io.github.esko997.sortie.smoke

import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
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

        shell("input keyevent KEYCODE_BUTTON_X")
        awaitNavigationOn(PRIMARY)
        await("the primary display's companion closed") { tasks().none { it.component == COMPANION } }
        await("the companion back on display $second") {
            tasks().any { it.component == SECONDARY_HOME && it.display == second && it.visible } && !device.hasObject(homeHeader(second))
        }
        await("the controller back on the primary display") { focusedDisplay() == PRIMARY }
        await("no lingering AnchorActivity") { tasks().none { it.component == ANCHOR } }
    }

    /**
     * X again as soon as the controller has followed navigation down: the companion that comes back below must hand it up
     * again. It used to skip that for 1.5 s after any hand-off (meant only for the resume its own anchor causes), so the
     * controller stayed on the companion's screen and further presses went nowhere.
     */
    @Test
    fun quickSecondSwapLeavesTheControllerWithNavigation() {
        val first = pressX()
        // past the moment X itself starts navigation below: the companion's hand-off has landed when the focus stays there
        awaitSteady("the controller following navigation to display $second", 300, pollMs = 50) { focusedDisplay() == second }
        val apart = pressX() - first
        // the end state is checked whatever the timing; only a slow runner's press gap makes the run not count as a repeat check
        awaitNavigationOn(PRIMARY)
        await("the controller back with navigation on the primary display (presses $apart ms apart)") { focusedDisplay() == PRIMARY }
        assumeTrue("presses $apart ms apart: too slow to land inside the companion's old 1.5 s window, not a quick swap", apart < 1_500)
    }

    /**
     * X twice within half a second: wherever navigation ends up, it is alone and has the controller. A slow runner can space
     * the presses further apart; the end state must still be right, the pair is tried once more, and the test is skipped
     * (not failed) when the runner cannot press twice that fast.
     */
    @Test
    fun doubleSwapEndsWithTheControllerOnNavigation() {
        val gaps = mutableListOf<Long>()
        while (gaps.size < 2) {
            val first = pressX()
            SystemClock.sleep(350)
            val apart = pressX() - first
            gaps += apart
            awaitSteady("one navigation task holding the controller, a companion on the other display (presses $apart ms apart)", 2_000) {
                val main = mainTasks().singleOrNull()
                main != null && main.visible && focusedDisplay() == main.display &&
                    tasks().any { (it.component == SECONDARY_HOME || it.component == COMPANION) && it.display != main.display && it.visible }
            }
            await("no lingering AnchorActivity") { tasks().none { it.component == ANCHOR } }
            if (apart <= 500) return
        }
        assumeTrue("presses ${gaps.joinToString(" and ")} ms apart: this runner cannot press X twice within 500 ms", false)
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
        // open nothing. Down walks to the first article (numbered "01"), one press at a time: each press must visibly move the
        // focus or scroll before the next is sent. Blind presses queue up while the app is busy (a slow runner) and are then
        // handled in one burst that runs straight past the articles, so A landed on the games filter.
        var presses = 0
        while (!firstArticleFocused()) {
            assertTrue("no article after $presses presses; it shows ${texts(PRIMARY)}", presses++ < 40)
            val before = contentsState()
            shell("input keyevent KEYCODE_DPAD_DOWN")
            await("Down $presses to move the focus or scroll", timeoutMs = 5_000, detail = { "it shows ${texts(PRIMARY)}" }) {
                contentsState().let { it != null && it != before }
            }
        }
        // A opens it. Pressed once more only if, 5 s later, the contents are still up with the first article focused: the press
        // was lost (once the contents leave, a second A would act inside the article instead).
        val contentsShown = By.pkg(PKG).text("shelf").displayId(PRIMARY)
        shell("input keyevent KEYCODE_BUTTON_A")
        if (device.wait(Until.gone(contentsShown), 5_000) != true && firstArticleFocused()) shell("input keyevent KEYCODE_BUTTON_A")
        awaitText("reader menu", "an article")
        assertEquals(PRIMARY, mainTasks().single().display)
    }

    /**
     * What a Down on the contents can change: the focused entry, and where every text is (a scroll inside a passage taller
     * than the screen moves its lines but not its clipped bounds). Null while no entry is focused or a node went stale.
     */
    private fun contentsState(): String? = runCatching {
        val focused = device.findObject(By.pkg(PKG).displayId(PRIMARY).focused(true)) ?: return@runCatching null
        val lines = device.findObjects(By.pkg(PKG).displayId(PRIMARY).textContains("")).map { "${it.text.take(12)}@${it.visibleBounds.top}" }
        "${focused.visibleBounds.toShortString()} $lines"
    }.getOrNull()

    private fun firstArticleFocused(): Boolean = device.hasObject(By.pkg(PKG).displayId(PRIMARY).focused(true).clickable(true).hasDescendant(By.text("01")))

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

    /** Like [await], but [condition] must hold on every poll for [steadyMs]: transient states mid-transition do not count. */
    private fun awaitSteady(what: String, steadyMs: Long, timeoutMs: Long = 20_000, pollMs: Long = 250, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var since: Long? = null
        while (true) {
            val now = SystemClock.uptimeMillis()
            since = if (condition()) since ?: now else null
            if (since != null && now - since >= steadyMs) return
            if (now > end) fail("Timed out after $timeoutMs ms waiting for $what to hold for $steadyMs ms; ${describe()}")
            SystemClock.sleep(pollMs)
        }
    }

    /**
     * X, straight to the focused display as the controller would send it; returns the press's event time. Injected directly
     * (no shell `input` process, no UiAutomator idle wait) so presses can be timed to a few milliseconds.
     */
    private fun pressX(): Long {
        val automation = instrumentation.getUiAutomation(Configurator.getInstance().uiAutomationFlags) // UiDevice's connection
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(t, t, action, KeyEvent.KEYCODE_BUTTON_X, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            // not synchronous: during a swap the focused display may wait seconds for its window, which would skew the timing
            assertTrue("X was not injected", automation.injectInputEvent(event, false))
        }
        return t
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
