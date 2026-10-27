package io.github.esko997.sortie

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.esko997.sortie.shell.TaskSwitcher
import io.github.esko997.sortie.ui.CompanionScreen
import io.github.esko997.sortie.ui.DeviceStatsSides
import io.github.esko997.sortie.ui.SortieTheme
import io.github.esko997.sortie.ui.rememberSelectedTheme

/**
 * Sortie's home on the secondary display (SECONDARY_HOME). Android resolves it from the default HOME package, so whenever the
 * second screen is vacated (an emulator exits, Sortie's navigation leaves for the other screen) the system lands here rather
 * than on Launcher3 or on whatever another launcher left behind. Must stay `standard` launch mode: the framework refuses
 * singleTask/singleInstance secondary homes.
 */
class SecondaryHomeActivity : CompanionHost() {
    /** A Home press while the second screen had the controller lands here (singleTop): that is the app switcher's summons. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME) && intent.getBooleanExtra(TaskSwitcher.EXTRA_FROM_HOME_KEY, false)) {
            val graph = (application as SortieApp).graph
            graph.launcher.homeKeyPressed()
            val switcher = graph.switcher
            switcher.summon(myDisplayId(), switcher.homeTask(myDisplayId()))
        }
    }
}

/**
 * Hosts the companion panel. Android gives the controller to the display that was touched or that last started an activity,
 * so a companion window would steal it from the game (or from Sortie's navigation) every time it appears or is tapped. A
 * companion therefore hands the controller back to the other screen whenever it resumes or is touched, by way of
 * [io.github.esko997.sortie.shell.TaskSwitcher.anchor]. (Making the window non-focusable does not help: the display still
 * becomes the focused one and keys are dropped with an ANR.)
 */
abstract class CompanionHost : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Never from a companion that is closing: an X swap back to this screen closes the companion here before it is paused, and
     * a hand-off still pending from its last resume would then send the controller to the screen navigation just left.
     */
    private val handOff = Runnable { if (!keepController && !isFinishing) graph().switcher.handOff(myDisplayId()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SortieTheme(palette = rememberSelectedTheme()) { DeviceStatsSides { CompanionScreen(displayId = myDisplayId()) } } }
    }

    override fun onResume() {
        super.onResume()
        graph().switcher.homeResumed(this)
        if (graph().switcher.companionShouldHandOff(myDisplayId())) scheduleHandOff()
    }

    /**
     * While the companion's own app picker (text field, IME) is open the controller must stay here: a hand-off on every tap
     * would move display focus away and the keyboard would close as soon as it opened. Set by the screen; [releaseController]
     * hands the controller back when the picker closes.
     */
    @Volatile
    var keepController = false
        set(value) {
            field = value
            if (value) handler.removeCallbacks(handOff) // the tap that opened the picker has a hand-off pending
        }

    fun releaseController() {
        keepController = false
        scheduleHandOff()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!keepController && (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)) scheduleHandOff()
        return super.dispatchTouchEvent(ev)
    }

    /** Debounced: a resume followed by a tap, or a fast sequence of taps, anchors once, after the click has been handled. */
    private fun scheduleHandOff() {
        handler.removeCallbacks(handOff)
        handler.postDelayed(handOff, 250)
    }

    override fun onPause() {
        handler.removeCallbacks(handOff)
        super.onPause()
    }

    override fun onStop() {
        graph().switcher.homeStopped(this)
        super.onStop()
    }

    protected fun myDisplayId(): Int = display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun graph() = (application as SortieApp).graph
}
