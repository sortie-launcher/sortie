package io.github.esko997.sortie.shell

import kotlin.system.exitProcess

/** Runs inside Shizuku's user-service process (uid shell). Only ever used for `cmd activity …` and `input …`. */
class ShellService : IShellService.Stub() {
    override fun exec(cmd: String): String {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return "${p.waitFor()}\n$out"
    }

    override fun destroy() {
        exitProcess(0)
    }
}
