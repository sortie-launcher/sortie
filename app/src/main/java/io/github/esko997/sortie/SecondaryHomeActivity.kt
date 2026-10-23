package io.github.esko997.sortie

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.esko997.sortie.ui.CompanionScreen
import io.github.esko997.sortie.ui.SortieTheme

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
        if (intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME)) (application as SortieApp).graph.switcher.summon(myDisplayId())
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
    private val handOff = Runnable { graph().switcher.handOff(myDisplayId()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SortieTheme { CompanionScreen(displayId = myDisplayId()) } }
    }

    override fun onResume() {
        super.onResume()
        if (!graph().switcher.anchoredRecently()) scheduleHandOff()
    }

    /**
     * While the companion's own app picker (text field, IME) is open the controller must stay here: a hand-off on every tap
     * would move display focus away and the keyboard would close as soon as it opened. Set by the screen; [releaseController]
     * hands the controller back when the picker closes.
     */
    @Volatile
    var keepController = false

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

    protected fun myDisplayId(): Int = display?.displayId ?: Display.DEFAULT_DISPLAY

    private fun graph() = (application as SortieApp).graph
}
