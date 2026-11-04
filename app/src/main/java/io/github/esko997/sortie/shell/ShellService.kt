package io.github.esko997.sortie.shell

import android.content.Context
import android.graphics.Rect
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess

/** Runs task and input operations inside Shizuku's user-service process (uid shell). */
class ShellService : IShellService.Stub() {
    override fun exec(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "${p.waitFor()}\n$out"
    }

    /** Exit PiP and clear its small bounds atomically, retaining the existing player task and activity. */
    override fun restorePinnedTask(rootTaskId: Int): String = try {
        val service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val roots = Class.forName("android.app.IActivityTaskManager").getMethod("getAllRootTaskInfos").invoke(service) as List<*>
        val taskInfo = Class.forName("android.app.TaskInfo")
        val id = taskInfo.getField("taskId")
        val root = roots.firstOrNull { id.getInt(it) == rootTaskId }
            ?: throw IllegalArgumentException("Task $rootTaskId no longer exists")
        if (taskInfo.getMethod("getWindowingMode").invoke(root) == 2) {
            // WindowOrganizer is a hidden shell API; the ordinary app process cannot perform this transaction.
            val token = taskInfo.getField("token").get(root)
            val tokenType = Class.forName("android.window.WindowContainerToken")
            val transactionType = Class.forName("android.window.WindowContainerTransaction")
            val transaction = transactionType.getConstructor().newInstance()
            transactionType.getMethod("setBounds", tokenType, Rect::class.java).invoke(transaction, token, null)
            transactionType.getMethod("setWindowingMode", tokenType, Int::class.javaPrimitiveType).invoke(transaction, token, 1)
            transactionType.getMethod("setActivityWindowingMode", tokenType, Int::class.javaPrimitiveType).invoke(transaction, token, 0)
            val organizerType = Class.forName("android.window.WindowOrganizer")
            organizerType.getMethod("applyTransaction", transactionType).invoke(organizerType.getConstructor().newInstance(), transaction)
        }
        "0\n"
    } catch (e: Exception) {
        "-1\n${e.cause?.message ?: e.message}"
    }

    /** The system input service, looked up once. */
    private val inputService: IBinder by lazy {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, Context.INPUT_SERVICE) as IBinder
    }

    /**
     * The hidden `IInputManager.injectInputEvent`, looked up once (the shell user holds INJECT_EVENTS). Each event waits until
     * the dispatcher has handed it to a window or given up, so a key nothing took fails the batch instead of passing as typed.
     */
    private val injectInputEvent: (KeyEvent) -> Boolean by lazy {
        val manager = Class.forName("android.hardware.input.IInputManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, inputService)
        val inject = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val injectOne: (KeyEvent) -> Boolean = { event -> inject.invoke(manager, event, INJECT_WAIT_FOR_RESULT) as Boolean }
        injectOne
    }

    private val keys = KeyInjector(
        focusedDisplays = { inputDump()?.let { KeyInjector.focusedWindowDisplays(it) } },
        dispatch = { injectInputEvent(it) }
    )

    override fun injectKeyEvents(displayId: Int, events: Array<KeyEvent>): String = keys.inject(displayId, events)

    /**
     * `dumpsys input` read in this process (the shell user holds DUMP), or null if it does not finish within [DUMP_TIMEOUT_MS].
     * The dump is one-way: the input service writes into the pipe while this thread reads, so a large dump cannot block it.
     */
    private fun inputDump(): String? {
        val (source, sink) = ParcelFileDescriptor.createPipe()
        try {
            sink.use { inputService.dumpAsync(it.fileDescriptor, emptyArray()) }
            val input = FileInputStream(source.fileDescriptor)
            val poll = StructPollfd().apply {
                fd = source.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            val deadline = SystemClock.uptimeMillis() + DUMP_TIMEOUT_MS
            while (true) {
                val left = deadline - SystemClock.uptimeMillis()
                if (left <= 0 || Os.poll(arrayOf(poll), left.toInt()) == 0) return null
                val read = input.read(buffer)
                if (read < 0) return out.toString(Charsets.UTF_8.name())
                out.write(buffer, 0, read)
            }
        } finally {
            source.close()
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT. */
        const val INJECT_WAIT_FOR_RESULT = 1
        const val DUMP_TIMEOUT_MS = 1_000L
    }
}

/**
 * Injects batches of key presses into the focused window of one display, as a hardware keyboard on that display would type
 * them. Every event is restamped to now and has the batch's display set explicitly: input dispatch then picks that display's
 * focused window, which may differ from the display holding the controller.
 *
 * A batch is only sent while the input dispatcher has a focused window on that display ([focusedDisplays], read once per
 * batch). Without one (stock Android gives a window focus on the top-focused display only, unless the build enables
 * per-display focus), a key would wait there for a window and end in an ANR for the app shown, so the batch is refused before
 * anything is injected. Batches never interleave and never leave a key held down: after a failure every key the batch still
 * holds is released, most recent press first (modifiers last), with the batch's own release where it has one. Events are
 * never logged — they are what the user types.
 */
internal class KeyInjector(
    /** Displays whose focused window takes keys now, or null if that cannot be told. */
    private val focusedDisplays: () -> Set<Int>?,
    private val dispatch: (KeyEvent) -> Boolean
) {
    /** `0\n` once every event was dispatched, else `-1\n<why>`; nothing after a failed event is typed. */
    @Synchronized
    fun inject(displayId: Int, events: Array<KeyEvent>): String {
        if (displayId < 0) return "-1\nNo display to type on"
        if (events.any { it.action != KeyEvent.ACTION_DOWN && it.action != KeyEvent.ACTION_UP }) {
            return "-1\nOnly key presses and releases can be typed"
        }
        val focused = try {
            focusedDisplays()
        } catch (e: Exception) {
            null
        }
        if (focused == null) return "-1\nCouldn't tell whether display $displayId takes keys, so nothing was typed"
        if (displayId !in focused) return "-1\nThe app on display $displayId doesn't have input focus, so nothing was typed"
        val held = LinkedHashMap<Int, KeyEvent>()
        for ((i, event) in events.withIndex()) {
            val failure = try {
                if (send(event, displayId, held)) null else "Nothing on display $displayId accepted the key"
            } catch (e: Exception) {
                val cause = (e as? InvocationTargetException)?.targetException ?: e
                cause.message ?: cause.javaClass.simpleName
            }
            if (failure != null) {
                releaseHeld(displayId, held, events.asList().subList(i + 1, events.size))
                return "-1\n$failure"
            }
        }
        releaseHeld(displayId, held, emptyList())
        return "0\n"
    }

    /** Injects [event] at the current time on [displayId], keeping a release's down time paired with its press. */
    private fun send(event: KeyEvent, displayId: Int, held: MutableMap<Int, KeyEvent>): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = event.action == KeyEvent.ACTION_DOWN
        val source = event.source.takeIf { it != InputDevice.SOURCE_UNKNOWN } ?: InputDevice.SOURCE_KEYBOARD
        val stamped = KeyEvent(
            if (down) now else held[event.keyCode]?.downTime ?: now,
            now,
            event.action,
            event.keyCode,
            0,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            source
        )
        setDisplayId.invoke(stamped, displayId)
        if (!dispatch(stamped)) return false
        if (down) held[event.keyCode] = stamped else held.remove(event.keyCode)
        return true
    }

    /**
     * Releases every key still held, most recent press first, using the release in [rest] (the batch not yet sent) where
     * there is one so it keeps its meta state. Best effort: the display may have stopped taking keys.
     */
    private fun releaseHeld(displayId: Int, held: MutableMap<Int, KeyEvent>, rest: List<KeyEvent>) {
        for (press in held.values.toList().asReversed()) {
            val release = rest.firstOrNull { it.action == KeyEvent.ACTION_UP && it.keyCode == press.keyCode } ?: KeyEvent(
                press.downTime,
                press.eventTime,
                KeyEvent.ACTION_UP,
                press.keyCode,
                0,
                0,
                press.deviceId,
                press.scanCode,
                press.flags,
                press.source
            )
            runCatching { send(release, displayId, held) }
        }
        held.clear()
    }

    companion object {
        /** KeyEvent's hidden `setDisplayId`; events made in the app carry no display. Found on first use, failing that batch. */
        private val setDisplayId by lazy { KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

        private val FOCUSED_WINDOW_RX = Regex("""^displayId=(-?\d+),""")

        /**
         * The displays listed under the input dispatcher's `FocusedWindows:` in `dumpsys input` (one indented
         * `displayId=<id>, name='<window>'` line each, or `FocusedWindows: <none>`); null when the dump has no such section.
         */
        internal fun focusedWindowDisplays(dump: String): Set<Int>? {
            val lines = dump.lines()
            val header = lines.indexOfFirst { it.trimStart().startsWith("FocusedWindows:") }
            if (header < 0) return null
            if (lines[header].substringAfter("FocusedWindows:").trim() == "<none>") return emptySet()
            return lines.drop(header + 1)
                .asSequence()
                .map { FOCUSED_WINDOW_RX.find(it.trim()) }
                .takeWhile { it != null }
                .mapNotNull { it?.groupValues?.get(1)?.toInt() }
                .toSet()
        }
    }
}
