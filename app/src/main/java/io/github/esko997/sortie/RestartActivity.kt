package io.github.esko997.sortie

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Restarts Sortie's main process: runs in its own `:restart` process, kills the pid it was handed, starts MainActivity and
 * exits. Used once, on first run, when the all-files grant changes which card directory the stores must bind to (see
 * [SortieApp.relocateIfNeeded]). A new process is the only way to rebind every store; doing it from a sibling process avoids
 * relying on an alarm to come back, which Android may defer.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_PID = "pid"
    }
}
