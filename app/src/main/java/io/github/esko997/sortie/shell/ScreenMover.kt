package io.github.esko997.sortie.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Moves a running app's root task between displays with `cmd activity display move-stack`, run as shell through Shizuku.
 * Everything is capability-gated: without Shizuku running and permitted, [status] says so and nothing else happens.
 */
class ScreenMover(private val context: Context) {
    enum class Status { UNAVAILABLE, NOT_RUNNING, PERMISSION_NEEDED, READY }

    data class RootTask(val id: Int, val displayId: Int, val component: String, val visible: Boolean)

    private val _status = MutableStateFlow(Status.UNAVAILABLE)
    val status: StateFlow<Status> = _status
    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult

    private var service: IShellService? = null
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false).processNameSuffix("shell").version(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShellService.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                _status.value = Status.NOT_RUNNING
                service = null
            }
            Shizuku.addRequestPermissionResultListener { _, result ->
                _status.value = if (result == PackageManager.PERMISSION_GRANTED) Status.READY else Status.PERMISSION_NEEDED
                if (result == PackageManager.PERMISSION_GRANTED) bind()
            }
        }.onFailure { Log.w(TAG, "shizuku listeners", it) }
        refresh()
    }

    fun refresh() {
        _status.value = runCatching {
            when {
                !Shizuku.pingBinder() -> Status.NOT_RUNNING
                Shizuku.isPreV11() -> Status.UNAVAILABLE
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.PERMISSION_NEEDED
                else -> Status.READY.also { bind() }
            }
        }.getOrElse {
            Log.w(TAG, "shizuku status", it)
            Status.UNAVAILABLE
        }
    }

    fun requestPermission() {
        runCatching { if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(REQUEST_CODE) }
    }

    private fun bind() {
        if (service != null) return
        runCatching { Shizuku.bindUserService(args, connection) }.onFailure { Log.w(TAG, "bind", it) }
    }

    private suspend fun exec(cmd: String): String = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext "-1\nshell service not bound"
        runCatching { svc.exec(cmd) }.getOrElse { "-1\n${it.message}" }
    }

    /** Parses `cmd activity stack list`: one RootTask line per task, then its first `taskId=` line for the component. */
    suspend fun rootTasks(): List<RootTask> {
        val out = exec("cmd activity stack list")
        val tasks = ArrayList<RootTask>()
        var current: Pair<Int, Int>? = null
        for (line in out.lineSequence()) {
            ROOT_RX.find(line)?.let {
                current = it.groupValues[1].toInt() to it.groupValues[2].toInt()
                return@let
            }
            val c = current ?: continue
            TASK_RX.find(line)?.let { m ->
                if (tasks.none { it.id == c.first }) tasks += RootTask(c.first, c.second, m.groupValues[1], m.groupValues[2] == "true")
            }
        }
        return tasks
    }

    /** Moves the top visible non-launcher app on [fromDisplay] to [toDisplay]. Returns a human-readable result. */
    suspend fun moveTopApp(fromDisplay: Int, toDisplay: Int): String {
        if (_status.value != Status.READY) return "Shizuku not ready (${_status.value})"
        val candidates = rootTasks().filter {
            it.displayId == fromDisplay && it.visible && !it.component.startsWith(context.packageName) && !isLauncher(it.component)
        }
        val task = candidates.firstOrNull() ?: return "no movable app on display $fromDisplay"
        val r = exec("cmd activity display move-stack ${task.id} $toDisplay")
        val msg = if (r.startsWith(
                "0"
            )
        ) {
            "moved ${task.component.substringBefore('/')} (task ${task.id}) to display $toDisplay"
        } else {
            "move failed: ${r.substringAfter('\n').trim()}"
        }
        _lastResult.value = msg
        Log.i(TAG, msg)
        return msg
    }

    private fun isLauncher(component: String): Boolean = LAUNCHER_HINTS.any { component.contains(it) }

    companion object {
        private const val TAG = "ScreenMover"
        private const val REQUEST_CODE = 7001
        private val ROOT_RX = Regex("""^\s*(?:RootTask|Stack) id=(\d+)\b.*\bdisplayId=(\d+)""")
        private val TASK_RX = Regex("""taskId=\d+: ([\w.]+/[\w.$]+).*visible=(true|false)""")
        private val LAUNCHER_HINTS = listOf("launcher", "Launcher", "cocoonshell", "ExternalDisplayActivity")
    }
}
