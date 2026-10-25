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
     * as ShellService does (`<exit code>\n<output>`).
     */
    fun probe(): String {
        val dir = Files.createTempDirectory("panel-probe").toFile()
        try {
            pid?.let { File(dir, "pidof.txt").writeText("$it\n") }
            File(dir, "connectivity.txt").writeText(connectivity)
            File(dir, "displays.txt").writeText(displays)
            File(dir, "windows.txt").writeText(windows)
            script(dir, "pidof", "[ -f \"\$d/pidof.txt\" ] && exec cat \"\$d/pidof.txt\"\nexit 1")
            script(
                dir,
                "dumpsys",
                """
                case "${'$'}*" in
                  connectivity) exec cat "${'$'}d/connectivity.txt" ;;
                  "window displays") exec cat "${'$'}d/displays.txt" ;;
                  "window windows") exec cat "${'$'}d/windows.txt" ;;
                esac
                echo "unexpected dumpsys ${'$'}*" >&2
                exit 1
                """.trimIndent()
            )
            val process = ProcessBuilder("sh", "-c", ScreenMover.PANEL_PROBE).redirectErrorStream(true).apply {
                environment()["PATH"] = "${dir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}"
            }.start()
            val out = process.inputStream.bufferedReader().readText()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "probe timed out" }
            return "${process.exitValue()}\n$out"
        } finally {
            dir.deleteRecursively()
        }
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
