package io.github.esko997.sortie.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Running-task operations as the shell user through Shizuku: list root tasks per display (`cmd activity stack list`), move one
 * between displays (`cmd activity display move-stack`), raise it on its display (`am task focus`) or close it (`am stack remove`).
 * Everything is capability-gated: without Shizuku running and permitted, [status] says so and nothing else happens.
 */
class ScreenMover(private val context: Context) {
    enum class Status { UNAVAILABLE, NOT_RUNNING, PERMISSION_NEEDED, READY }

    /** One leaf task: [id] is the task id, [rootId] the root task it lives in (the unit `display move-stack` moves). */
    data class RootTask(val rootId: Int, val id: Int, val displayId: Int, val component: String, val visible: Boolean, val pinned: Boolean) {
        val pkg: String get() = component.substringBefore('/')
    }

    val ready: Boolean get() = _status.value == Status.READY

    private val _status = MutableStateFlow(Status.UNAVAILABLE)
    val status: StateFlow<Status> = _status
    private val _lastResult = MutableStateFlow("")
    val lastResult: StateFlow<String> = _lastResult

    private var service: IShellService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
        .daemon(false).processNameSuffix("shell").version(2)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IShellService.Stub.asInterface(binder)
            scope.launch { sweepStaleLaunchers() }
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

    /** Parses `cmd activity stack list`: a RootTask line, then one `taskId=` line per leaf task in it (top-most first). */
    suspend fun rootTasks(): List<RootTask> = parseRootTasks(exec("cmd activity stack list"))

    /** Tasks that are apps the user can switch to: not Sortie, not a launcher, not a placeholder. Top-most first per display. */
    suspend fun appTasks(): List<RootTask> =
        if (!ready) emptyList() else rootTasks().filter { !it.component.startsWith(context.packageName) && !isLauncher(it.component) && '/' in it.component }

    /** Moves a whole root task (display move-stack works on root tasks only). */
    suspend fun moveTask(task: RootTask, toDisplay: Int): Boolean =
        restorePinnedTask(task) && ok(exec("cmd activity display move-stack ${task.rootId} $toDisplay"), "move ${task.rootId} -> $toDisplay")

    /** Restores a pinned player to fullscreen, then raises the task on its display; [AnchorActivity] handles key focus. */
    suspend fun focusTask(task: RootTask): Boolean = restorePinnedTask(task) && ok(exec("am task focus ${task.id}"), "focus ${task.id}")

    private suspend fun restorePinnedTask(task: RootTask): Boolean {
        if (!task.pinned) return true
        val reply = withContext(Dispatchers.IO) {
            runCatching { service?.restorePinnedTask(task.rootId) ?: "-1\nshell service not bound" }.getOrElse { "-1\n${it.message}" }
        }
        return ok(reply, "restore PiP ${task.id}")
    }

    /** `am stack remove` takes any task id, leaf tasks included (ActivityTaskManager.removeTask). */
    suspend fun removeTask(task: RootTask): Boolean = ok(exec("am stack remove ${task.id}"), "remove ${task.id}")

    /**
     * Removes tasks other launchers left behind (Cocoon, the stock Odin launcher …). They sit in the home root task under
     * Sortie, so when a game exits the system would resume one of them instead of starting HOME. Launcher3 is the system's
     * (secondary-display fallback home, quickstep recents, which crashes if its task is pulled away) and is never touched.
     * Run when the shell service connects, when Sortie comes to the foreground and right after every launch.
     */
    suspend fun sweepStaleLaunchers(): Int {
        if (!ready) return 0
        val stale = rootTasks().filter { isLauncher(it.component) && it.pkg != context.packageName && it.pkg != SYSTEM_LAUNCHER }
        return stale.count { removeTask(it) }.also { if (it > 0) Log.i(TAG, "removed $it stale launcher task(s): ${stale.map { t -> t.component }}") }
    }

    /** Opens the system notification shade (on whichever display the status bar serves). */
    fun expandNotifications() {
        scope.launch { ok(exec("cmd statusbar expand-notifications"), "expand notifications") }
    }

    fun sweepAsync() {
        scope.launch { sweepStaleLaunchers() }
    }

    /** Moves the top visible non-launcher app on [fromDisplay] to [toDisplay]. Returns a human-readable result. */
    suspend fun moveTopApp(fromDisplay: Int, toDisplay: Int): String {
        if (!ready) return "Shizuku not ready (${_status.value})"
        val task = appTasks().firstOrNull { it.displayId == fromDisplay && it.visible } ?: return "no movable app on display $fromDisplay"
        val msg = if (moveTask(task, toDisplay)) "moved ${task.pkg} (task ${task.id}) to display $toDisplay" else "move failed: ${_lastResult.value}"
        _lastResult.value = msg
        return msg
    }

    /** Shell service replies `<exit code>\n<output>`; anything but 0 is logged and surfaced through [lastResult]. */
    private fun ok(reply: String, what: String): Boolean {
        val success = reply.substringBefore('\n').trim() == "0"
        if (!success) _lastResult.value = "$what failed: ${reply.substringAfter('\n').trim()}"
        Log.i(TAG, if (success) what else _lastResult.value)
        return success
    }

    /**
     * Activities that serve as a HOME or SECONDARY_HOME (Launcher3, Cocoon, the Odin launcher, Settings' FallbackHome …) plus the
     * quickstep recents overlay. Matched by component: Settings also offers a home activity but its other screens are apps.
     */
    private val launcherComponents: Set<String> by lazy {
        val pm = context.packageManager
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMap { cat ->
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(cat),
                PackageManager.MATCH_ALL
            ).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        }.toSet()
    }

    private fun isLauncher(component: String): Boolean = component in launcherComponents || component.endsWith("RecentsActivity")

    companion object {
        private const val SYSTEM_LAUNCHER = "com.android.launcher3"
        private const val TAG = "ScreenMover"
        private const val REQUEST_CODE = 7001
        private val ROOT_RX = Regex("""^\s*(?:RootTask|Stack) id=(\d+)\b.*\bdisplayId=(\d+)""")
        private val TASK_RX = Regex("""taskId=(\d+): ([\w.]+/[\w.$]+).*visible=(true|false)""")

        internal fun parseRootTasks(output: String): List<RootTask> {
            val tasks = ArrayList<RootTask>()
            var current: Pair<Int, Int>? = null
            var pinned = false
            for (line in output.lineSequence()) {
                ROOT_RX.find(line)?.let {
                    current = it.groupValues[1].toInt() to it.groupValues[2].toInt()
                    pinned = false
                }
                if ("mWindowingMode=" in line) pinned = "mWindowingMode=pinned" in line
                val c = current ?: continue
                TASK_RX.find(line)?.let { m ->
                    tasks += RootTask(c.first, m.groupValues[1].toInt(), c.second, m.groupValues[2], m.groupValues[3] == "true", pinned)
                }
            }
            return tasks
        }
    }
}
