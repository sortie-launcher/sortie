package io.github.esko997.sortie.testing

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.esko997.sortie.MainActivity
import org.junit.rules.ExternalResource
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * Sortie's MainActivity under a Robolectric [ActivityController], launched as from its launcher icon. The controller (rather than
 * ActivityScenario) is what lets a test deliver a Home intent the way Android does (pause, new intent, resume) and recreate the
 * activity with its saved state. Order it inside the compose rule (`createEmptyComposeRule()`), which must be watching before
 * the activity composes.
 */
class SortieActivityRule : ExternalResource() {
    lateinit var controller: ActivityController<MainActivity>
        private set

    val activity: MainActivity get() = controller.get()

    override fun before() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        controller = Robolectric.buildActivity(MainActivity::class.java, launch).setup()
    }

    override fun after() {
        if (!activity.isDestroyed) controller.pause().stop().destroy()
    }

    /** What the Home key delivers to Sortie while it is the home app and already on top: the HOME intent, again. */
    fun pressHome() {
        controller.newIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setClass(activity, MainActivity::class.java))
    }

    /** A configuration change or process-memory recreation: the activity is rebuilt from its saved instance state. */
    fun recreate() {
        controller.recreate()
    }
}
