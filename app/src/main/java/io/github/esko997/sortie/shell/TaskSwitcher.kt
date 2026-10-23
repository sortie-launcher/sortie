package io.github.esko997.sortie.shell

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import io.github.esko997.sortie.AnchorActivity
import io.github.esko997.sortie.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sortie's own app switcher: the running app tasks per display, and the actions the switcher screens offer on them. One instance
 * for the process so the navigation screen (controller) and the companion screen (touch) see the same state. Everything that
 * touches tasks goes through [ScreenMover] (Shizuku); without it the switcher is simply empty and says so.
 */
class TaskSwitcher(private val context: Context, private val mover: ScreenMover, private val presentationIndex: () -> Int) {
    data class Entry(val task: ScreenMover.RootTask, val label: String, val icon: Drawable?)

    data class State(
        val open: Boolean = false,
        /** Open as overlay windows rather than as Sortie's screens. */
        val overlay: Boolean = false,
        val entries: List<Entry> = emptyList(),
        val busy: Boolean = false,
        val ready: Boolean = false
    ) {
        fun on(displayId: Int): List<Entry> = entries.filter { it.task.displayId == displayId }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Installed by the UI layer: shows the overlay with the controller on the given display, or returns false. Main thread. */
    var presenter: ((focusDisplay: Int) -> Boolean)? = null

    /** Installed by the UI layer: tears the overlay down. Main thread. */
    var dismisser: (() -> Unit)? = null

    fun open(overlay: Boolean = false) {
        _state.update { it.copy(open = true, overlay = overlay) }
        refresh()
    }

    fun close() {
        if (_state.value.overlay) dismisser?.invoke()
        _state.update { it.copy(open = false, overlay = false) }
    }

    fun refresh() = scope.launch { reload() }

    /** The configured second screen's display id, or null on a single-screen device. */
    fun secondaryDisplay(): Int? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(presentationIndex())?.displayId
    }

    /** The display that is not [displayId]: the second screen from the top one, the top one from anywhere else. */
    fun other(displayId: Int): Int? = if (displayId == TOP) secondaryDisplay() else TOP

    /** True when at least one switchable app is running; used to decide whether Home should land on the switcher. */
    suspend fun anyRunning(): Boolean = mover.ready && mover.appTasks().isNotEmpty()

    /**
     * Home pressed while something runs, on [focusDisplay]. With the draw-over-apps grant the switcher is an overlay on both
     * screens (games keep running underneath); otherwise Sortie's navigation comes to the top screen with the switcher route.
     * Nothing happens when nothing is running.
     */
    fun summon(focusDisplay: Int = TOP) = scope.launch {
        if (!anyRunning()) return@launch
        val presented = withContext(Dispatchers.Main) { presenter?.invoke(focusDisplay) ?: false }
        if (presented) return@launch
        open()
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(TOP)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SWITCHER, true)
        runCatching { context.startActivity(intent, options.toBundle()) }.onFailure { Log.w(TAG, "summon", it) }
    }

    /** Raises [entry] on its display and gives that display the controller. The overlay closes: the user chose what to play. */
    fun bringToFront(entry: Entry) = act(dismiss = true) { mover.focusTask(entry.task) && anchor(entry.task.displayId) }

    /** Moves [entry] to [displayId], raises it there and anchors the controller to that display. */
    fun moveTo(entry: Entry, displayId: Int) = act(dismiss = true) { mover.moveTask(entry.task, displayId) && mover.focusTask(entry.task) && anchor(displayId) }

    fun closeApp(entry: Entry) = act { mover.removeTask(entry.task) }

    /** Swaps the top app of each display: a → b and b → a in one go. */
    fun quickSwap(displayA: Int, displayB: Int) = act(dismiss = true) {
        val s = _state.value
        val a = s.on(displayA).firstOrNull()
        val b = s.on(displayB).firstOrNull()
        if (a == null && b == null) return@act false
        var ok = true
        if (a != null) ok = mover.moveTask(a.task, displayB) && ok
        if (b != null) ok = mover.moveTask(b.task, displayA) && ok
        if (ok) anchor(if (a != null) displayB else displayA)
        ok
    }

    /** Raises the top app of [displayId] back over Sortie's own activity (after a Home press) and anchors the controller there. */
    fun reveal(displayId: Int) = scope.launch {
        lock.withLock {
            reloadLocked()
            _state.value.on(displayId).firstOrNull()?.let { mover.focusTask(it.task) }
        }
        anchor(displayId)
    }

    /**
     * Key focus follows the display an activity was last started on, so starting (and immediately finishing) a transparent
     * activity on [displayId] hands the controller to whatever is on top there. Used after every switch and offered as
     * "re-anchor" for emulators whose own second-screen window stole the focus.
     */
    fun anchor(displayId: Int): Boolean = runCatching {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        val intent = Intent(context, AnchorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        lastAnchorAt = System.currentTimeMillis()
        context.startActivity(intent, options.toBundle())
        true
    }.getOrElse {
        Log.w(TAG, "anchor $displayId", it)
        false
    }

    /** When the last anchor was started; a companion resumed by our own anchor must not anchor back (two screens would ping-pong). */
    @Volatile
    var lastAnchorAt = 0L
        private set

    fun anchoredRecently(): Boolean = System.currentTimeMillis() - lastAnchorAt < 1500

    /** A companion screen giving the controller back to the other screen; nothing to do on a single-screen device. */
    fun handOff(fromDisplay: Int) {
        other(fromDisplay)?.let { anchor(it) }
    }

    private fun act(dismiss: Boolean = false, block: suspend () -> Boolean) = scope.launch {
        val ok = lock.withLock {
            _state.update { it.copy(busy = true) }
            runCatching { block() }.onFailure { Log.w(TAG, "switch", it) }.getOrDefault(false)
        }
        reload()
        if (dismiss && ok && _state.value.overlay) withContext(Dispatchers.Main) { close() }
    }

    private suspend fun reload() = lock.withLock { reloadLocked() }

    private suspend fun reloadLocked() {
        val ready = mover.ready
        val tasks = if (ready) mover.appTasks() else emptyList()
        val pm = context.packageManager
        val entries = tasks.map { t ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(t.pkg, 0)).toString() }.getOrDefault(t.pkg)
            val icon = runCatching { pm.getApplicationIcon(t.pkg) }.getOrNull()
            Entry(t, label, icon)
        }
        _state.update { it.copy(entries = entries, busy = false, ready = ready) }
    }

    companion object {
        private const val TAG = "Switcher"
        const val TOP = Display.DEFAULT_DISPLAY

        /** Intent extra on MainActivity: open the switcher route (set by [summon]). */
        const val EXTRA_SWITCHER = "switcher"
    }
}
