package io.github.esko997.sortie

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.Log
import io.github.esko997.sortie.data.magazine.ReaderLocation
import java.util.concurrent.atomic.AtomicLong

/**
 * Sortie's built-in apps: screens that run as ordinary app tasks of their own, beside Sortie's navigation and whatever plays on
 * the other screen, rather than as navigation routes. Each has exactly one task ([activity] is singleTask with its own affinity),
 * a name for the switcher, and can be opened, reopened and moved on either screen without resetting what it shows.
 */
enum class BuiltinApp(val label: String, internal val activity: Class<out Activity>) {
    MAGAZINES("Magazines", MagazinesActivity::class.java),
    PERFORMANCE("Performance", PerformanceActivity::class.java);

    /** The task's root component as `cmd activity stack list` prints it (`package/class`). */
    internal fun component(packageName: String): String = "$packageName/${activity.name}"

    companion object {
        /** The built-in app whose task has [component] as its root, or null for any other component. */
        internal fun of(packageName: String, component: String): BuiltinApp? = entries.firstOrNull { it.component(packageName) == component }
    }
}

/** One "open this" request to a built-in app: [serial] tells two requests for the same place apart. */
data class BuiltinRequest(val opened: ReaderLocation, val serial: Long)

object BuiltinApps {
    private const val TAG = "BuiltinApps"
    private const val EXTRA_OPENED = "builtin.opened"
    private const val EXTRA_ISSUE = "builtin.issue"
    private const val EXTRA_ARTICLE = "builtin.article"
    private const val EXTRA_DISPLAY = "builtin.display"

    /** Last serial handed out: from the boot clock, so a reader restored after process death never takes a new request for one it followed. */
    private val serials = AtomicLong()

    private fun nextSerial(): Long = serials.updateAndGet { last -> maxOf(last + 1, SystemClock.elapsedRealtimeNanos()) }

    /**
     * Opens [app] on [displayId], or brings its task there: a running instance on that display comes to the front, one on the
     * other display moves over with its state (see [BuiltinActivity.relocate]), and none starts fresh. [opened] asks the
     * magazine reader for a place. Needs no Shizuku or root: it only starts Sortie's own activity. False when [displayId] is
     * not a display or the start was refused.
     */
    fun open(context: Context, app: BuiltinApp, displayId: Int, opened: ReaderLocation? = null): Boolean {
        if (!exists(context, displayId)) return false
        BuiltinActivity.running(app)?.let { live ->
            if (live.displayId() != displayId) return live.relocate(displayId, opened)
        }
        return start(context, intent(context, app, opened, displayId), displayId)
    }

    /** The configured other screen from [fromDisplayId]: the second screen from the top one, the top one from the second; null on one screen. */
    fun otherDisplay(context: Context, fromDisplayId: Int): Int? =
        (context.applicationContext as SortieApp).graph.switcher.other(fromDisplayId)?.takeIf { it != fromDisplayId && exists(context, it) }

    /**
     * The intent [app]'s activity is started with (tests build their activity from it). [displayId]: the display [open] asked
     * for, which a task Android brings forward elsewhere moves to on resume.
     */
    internal fun intent(context: Context, app: BuiltinApp, opened: ReaderLocation? = null, displayId: Int? = null): Intent {
        val intent = Intent(context, app.activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (opened != null) intent.putExtra(EXTRA_OPENED, true).putExtra(EXTRA_ISSUE, opened.issue).putExtra(EXTRA_ARTICLE, opened.article)
        displayId?.let { intent.putExtra(EXTRA_DISPLAY, it) }
        return intent
    }

    /** The open request [intent] carries, once: the extras are removed so a later look at the intent does not repeat it. */
    internal fun takeRequest(intent: Intent?): BuiltinRequest? {
        if (intent?.getBooleanExtra(EXTRA_OPENED, false) != true) return null
        val opened = ReaderLocation(intent.getStringExtra(EXTRA_ISSUE), intent.getStringExtra(EXTRA_ARTICLE))
        intent.removeExtra(EXTRA_OPENED)
        intent.removeExtra(EXTRA_ISSUE)
        intent.removeExtra(EXTRA_ARTICLE)
        return BuiltinRequest(opened, nextSerial())
    }

    /** The display [open] asked for, once; null when the intent did not come from [open]. */
    internal fun takeDisplay(intent: Intent?): Int? {
        if (intent?.hasExtra(EXTRA_DISPLAY) != true) return null
        return intent.getIntExtra(EXTRA_DISPLAY, -1).also { intent.removeExtra(EXTRA_DISPLAY) }.takeIf { it >= 0 }
    }

    internal fun exists(context: Context, displayId: Int): Boolean = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) != null

    internal fun start(context: Context, intent: Intent, displayId: Int): Boolean = runCatching {
        context.applicationContext.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
    }.onFailure { Log.w(TAG, "start ${intent.component?.className} on display $displayId", it) }.isSuccess
}
