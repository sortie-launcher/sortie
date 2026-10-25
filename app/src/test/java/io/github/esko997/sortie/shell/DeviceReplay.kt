package io.github.esko997.sortie.shell

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Recorded device output under `src/test/resources/device-captures/` (see the README there) and the fakes that replay it:
 * [ReplayShell] answers ScreenMover's shell commands from a capture and records what it issued; [PanelDumps.probe] runs the
 * real AYN panel probe script in a local `sh` against recorded `dumpsys` output.
 */
internal object DeviceCaptures {
    fun text(path: String): String =
        requireNotNull(javaClass.classLoader?.getResource("device-captures/$path")) { "no capture device-captures/$path" }.readText()

    /** The HOME/SECONDARY_HOME activities of `thor` or `dmg`, as the package manager would report them. */
    fun homes(device: String): Set<String> =
        text("$device/home-activities.txt").lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    /** `cmd activity stack list` from `<device>/<capture>/stack-list.txt`. */
    fun stackList(capture: String): String = text("$capture/stack-list.txt")
}

internal const val SORTIE = "io.github.esko997.sortie"
internal const val STACK_LIST = "cmd activity stack list"

/** Just enough Context for ScreenMover and TaskSwitcher on the JVM: everything else is the android.jar stub default. */
internal class SortieContext : ContextWrapper(null) {
    override fun getPackageName(): String = SORTIE
}

/**
 * Answers `cmd activity stack list` with [stackList] and the panel probe with [probes] in turn (the last one repeats);
 * every other command succeeds silently, as move-stack, task focus, stack remove and am crash do on the device.
 */
internal class ReplayShell(private val stackList: String, private val probes: List<() -> String> = emptyList()) : ShellExec {
    val issued: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var probesRun = 0

    override fun exec(cmd: String): String {
        issued += cmd
        return when (cmd) {
            STACK_LIST -> "0\n$stackList"
            ScreenMover.PANEL_PROBE -> probes[minOf(probesRun++, probes.lastIndex)]()
            else -> "0\n"
        }
    }

    override fun restorePinnedTask(rootTaskId: Int): String {
        issued += "restorePinnedTask $rootTaskId"
        return "0\n"
    }

    /** What the device was asked to change: everything but the reads (stack list, panel probe). */
    val actions: List<String> get() = synchronized(issued) { issued.filter { it != STACK_LIST && it != ScreenMover.PANEL_PROBE } }

    val probeCount: Int get() = synchronized(issued) { issued.count { it == ScreenMover.PANEL_PROBE } }
}

internal fun replayMover(device: String, stackList: String, probes: List<() -> String> = emptyList()): Pair<ScreenMover, ReplayShell> {
    val shell = ReplayShell(stackList, probes)
    return ScreenMover(SortieContext(), shell, DeviceCaptures.homes(device)) to shell
}

/** What the AYN panel probe reads: `pidof` and three `dumpsys` outputs. A null [pid] means the panel process is not running. */
internal data class PanelDumps(val pid: String?, val connectivity: String, val displays: String, val windows: String) {
    /**
     * Runs [ScreenMover.PANEL_PROBE] in `sh` with `pidof` and `dumpsys` replaced by scripts printing these dumps, and replies
     * as ShellService does (`<exit code>\n<output>`). A probe still running after [timeoutMs] fails the test; it and every
     * process it started are killed whatever the outcome. [stallMarker] makes `dumpsys connectivity` write its pid there
     * and hang instead of answering (to test the timeout).
     */
    fun probe(timeoutMs: Long = 30_000, stallMarker: File? = null): String {
        val dir = Files.createTempDirectory("panel-probe").toFile()
        var process: Process? = null
        try {
            pid?.let { File(dir, "pidof.txt").writeText("$it\n") }
            File(dir, "connectivity.txt").writeText(connectivity)
            File(dir, "displays.txt").writeText(displays)
            File(dir, "windows.txt").writeText(windows)
            script(dir, "pidof", "[ -f \"\$d/pidof.txt\" ] && exec cat \"\$d/pidof.txt\"\nexit 1")
            val stall = stallMarker?.let { "connectivity) echo \$\$ \$PPID > \"${it.absolutePath}\"; exec sleep 600 ;;" } ?: ""
            script(
                dir,
                "dumpsys",
                """
                case "${'$'}*" in
                  $stall
                  connectivity) exec cat "${'$'}d/connectivity.txt" ;;
                  "window displays") exec cat "${'$'}d/displays.txt" ;;
                  "window windows") exec cat "${'$'}d/windows.txt" ;;
                esac
                echo "unexpected dumpsys ${'$'}*" >&2
                exit 1
                """.trimIndent()
            )
            // Output goes to a file, not a pipe: reading a pipe to EOF would block on a hung probe before the bounded wait.
            val out = File(dir, "probe.out")
            process = ProcessBuilder("sh", "-c", ScreenMover.PANEL_PROBE).redirectErrorStream(true).redirectOutput(out).apply {
                environment()["PATH"] = "${dir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}"
            }.start()
            check(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { "panel probe still running after $timeoutMs ms: killed" }
            return "${process.exitValue()}\n${out.readText()}"
        } finally {
            process?.let(::killTree)
            dir.deleteRecursively()
        }
    }

    /** Kills [process] and its descendants, collected first: once the shell dies they are reparented out of reach. */
    private fun killTree(process: Process) {
        val root = HostProcesses.handle(process)
        val all = HostProcesses.descendants(root) + root
        all.forEach(HostProcesses::destroyForcibly)
        all.forEach(HostProcesses::awaitExit)
    }

    private fun script(dir: File, name: String, body: String) {
        File(dir, name).apply {
            writeText("#!/bin/sh\nd=\"${dir.absolutePath}\"\n$body\n")
            setExecutable(true)
        }
    }

    companion object {
        /** The Thor whose panel crashed (sortie-thor-crash): 213 leaked requests, an undrawn panel window holding display 4. */
        fun stuck(): PanelDumps = PanelDumps(
            DeviceCaptures.text("thor/panel-stuck/pidof.txt").trim(),
            DeviceCaptures.text("thor/panel-stuck/connectivity.txt"),
            DeviceCaptures.text("thor/panel-stuck/displays.txt"),
            DeviceCaptures.text("thor/panel-stuck/windows.txt")
        )
    }
}

/**
 * java.lang.ProcessHandle (JDK 9+) through reflection: unit tests compile against android.jar, which lacks it, but run on
 * the build's JDK 17.
 */
internal object HostProcesses {
    private val type = Class.forName("java.lang.ProcessHandle")

    fun handle(process: Process): Any = Process::class.java.getMethod("toHandle").invoke(process)!!

    fun descendants(handle: Any): List<Any> = (type.getMethod("descendants").invoke(handle) as java.util.stream.Stream<*>)
        .toArray().filterNotNull()

    fun destroyForcibly(handle: Any) {
        type.getMethod("destroyForcibly").invoke(handle)
    }

    fun awaitExit(handle: Any) {
        runCatching { (type.getMethod("onExit").invoke(handle) as java.util.concurrent.CompletableFuture<*>).get(5, TimeUnit.SECONDS) }
    }

    /** Whether the process [pid] is still running. */
    fun isAlive(pid: Long): Boolean {
        val found = type.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid) as java.util.Optional<*>
        return found.isPresent && type.getMethod("isAlive").invoke(found.get()) as Boolean
    }
}
