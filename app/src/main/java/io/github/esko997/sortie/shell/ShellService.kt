package io.github.esko997.sortie.shell

import android.graphics.Rect
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

    override fun destroy() {
        exitProcess(0)
    }
}
