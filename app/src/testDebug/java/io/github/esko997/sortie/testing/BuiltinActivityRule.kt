package io.github.esko997.sortie.testing

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.BuiltinActivity
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.BuiltinApps
import io.github.esko997.sortie.data.magazine.ReaderLocation
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * One of Sortie's built-in apps ([app]) under a Robolectric [ActivityController], started the way [BuiltinApps.open] starts it
 * ([BuiltinApps.intent]). Nothing runs until [open]: a test usually lets the navigation ([SortieActivityRule]) index the
 * fixture first, as the launcher always has on a device. Order it inside the compose rule, like [SortieActivityRule].
 */
class BuiltinActivityRule(private val app: BuiltinApp) : ExternalResource() {
    private var started: ActivityController<out BuiltinActivity>? = null

    val controller: ActivityController<out BuiltinActivity> get() = checkNotNull(started) { "open() the built-in first" }

    val activity: BuiltinActivity get() = controller.get()

    /** Cold-starts the app, at [opened] when given (a game page's "Featured in"), or delivers that open to the running one. */
    fun open(opened: ReaderLocation? = null) {
        val intent = BuiltinApps.intent(ApplicationProvider.getApplicationContext(), app, opened)
        if (started == null) {
            @Suppress("UNCHECKED_CAST") // the enum's activity class is a BuiltinActivity
            started = Robolectric.buildActivity(app.activity as Class<BuiltinActivity>, intent).setup()
        } else {
            newIntent(intent)
        }
    }

    /** What Android delivers to the running singleTask instance when the app is opened again. */
    fun newIntent(intent: Intent) {
        controller.newIntent(intent)
    }

    /** A configuration change or process-memory recreation: the activity is rebuilt from its saved instance state. */
    fun recreate() {
        controller.recreate()
    }

    /** Tears down from wherever the test left the activity (a test may have stopped it to put it behind something). */
    override fun after() {
        val c = started ?: return
        val state = c.get().lifecycle.currentState
        if (state == Lifecycle.State.DESTROYED) return
        if (state.isAtLeast(Lifecycle.State.RESUMED)) c.pause()
        if (state.isAtLeast(Lifecycle.State.STARTED)) c.stop()
        c.destroy()
    }
}
