package io.github.esko997.sortie.shell

import android.app.Activity
import android.app.ActivityManager
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
        val ready: Boolean = false,
        /** A display an app was just moved away from: its carousel offers every running window as the replacement. */
        val freed: Int? = null,
        /** The display the user is picking an installed app for (the app picker is shown instead of the carousels). */
        val picking: Int? = null
    ) {
        fun on(displayId: Int): List<Entry> = entries.filter { it.task.displayId == displayId }

        /**
         * What [displayId]'s carousel shows and whether choosing an entry brings it here (true) or just raises it (false): the
         * display's own apps normally; every running window when it was just freed; the other screen's apps when it is empty.
         */
        fun offers(displayId: Int): Pair<List<Entry>, Boolean> {
            val here = on(displayId)
            return when {
                displayId == freed -> entries to true
                here.isEmpty() -> entries.filter { it.task.displayId != displayId } to true
                else -> here to false
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Shows the overlay; [homeTask] is the Sortie surface Home covered, or null to reveal the previous external app. Main thread. */
    var presenter: ((focusDisplay: Int, homeTask: Int?) -> Boolean)? = null

    /** Installed by the UI layer: tears the overlay down. Main thread. */
    var dismisser: (() -> Unit)? = null

    // Main-thread lifecycle state. Keep a surface through onPause: HOME intent delivery itself pauses an activity.
    // onStop removes it once an external app really covers it; instance identity protects a newer recreation from an old stop.
    private val visibleHomes = mutableMapOf<Int, Activity>()

    fun homeResumed(activity: Activity) {
        visibleHomes[activity.display?.displayId ?: TOP] = activity
    }

    fun homeStopped(activity: Activity) {
        val display = activity.display?.displayId ?: TOP
        if (visibleHomes[display] === activity) visibleHomes.remove(display)
    }

    fun homeTask(displayId: Int): Int? = visibleHomes[displayId]?.taskId

    fun open(overlay: Boolean = false, focusDisplay: Int? = null) {
        // Home while an overlay picker is open abandons it: undo what startPicking did, as stopPicking would. The overlay
        // windows are kept on a repeated Home, so a picker-era focusable window would otherwise stay focusable.
        val abandonedPicker = _state.value.overlay && _state.value.picking != null
        overlayDisplay = if (overlay) focusDisplay else null
        _state.update { it.copy(open = true, overlay = overlay, freed = null, picking = null) }
        if (abandonedPicker) {
            textInput?.invoke(null)
            overlayDisplay?.let { anchor(it) }
        }
        refresh()
    }

    fun close() {
        if (_state.value.overlay) dismisser?.invoke()
        _state.update { it.copy(open = false, overlay = false, freed = null, picking = null) }
        overlayDisplay = null
        pendingAnchor?.let { anchor(it) }
        pendingAnchor = null
    }

    /** The display whose overlay window has the controller while the overlay is up. */
    private var overlayDisplay: Int? = null

    /** Display to hand the controller to when the overlay closes (set by a move while the overlay keeps the controller). */
    private var pendingAnchor: Int? = null

    /** Installed by the UI layer: lets the overlay window on a display take input focus (IME) while its picker is open. */
    var textInput: ((displayId: Int?) -> Unit)? = null

    /** "Open app…" for [displayId]: that screen shows the picker. */
    fun startPicking(displayId: Int) {
        _state.update { it.copy(picking = displayId) }
        if (_state.value.overlay) textInput?.invoke(displayId)
    }

    fun stopPicking() {
        _state.update { it.copy(picking = null) }
        if (_state.value.overlay) {
            textInput?.invoke(null)
            overlayDisplay?.let { anchor(it) } // the controller may have followed the touched window; give it back
        }
    }

    /** An app was started on [displayId] from the picker: the switcher closes and the controller goes to that display. */
    fun picked(displayId: Int) {
        pendingAnchor = displayId
        close()
    }

    fun refresh() = scope.launch { reload() }

    /** The configured second screen's display id, or null on a single-screen device. */
    fun secondaryDisplay(): Int? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(presentationIndex())?.displayId
    }

    /** The display that is not [displayId]: the second screen from the top one, the top one from anywhere else. */
    fun other(displayId: Int): Int? = if (displayId == TOP) secondaryDisplay() else TOP

    /**
     * Whether the Home key lands on the switcher: whenever Shizuku is ready, even when no other app runs. The switcher is also
     * where "Open app…" lives, so Home from Sortie's own screens (a magazine article, a game page) must reach it rather than
     * depend on some external task existing. Without Shizuku, Home stays plain Sortie.
     */
    val summonable: Boolean get() = mover.ready

    /**
     * Home pressed on the second screen ([focusDisplay]). With the draw-over-apps grant the switcher is an overlay on both
     * screens (games keep running underneath); otherwise Sortie's navigation comes to the top screen with the switcher route.
     * Without Shizuku nothing opens and the Sortie surface Home covered ([homeTask], e.g. navigation after an X swap) comes
     * back, as Home does on the top screen. Main thread.
     */
    fun summon(focusDisplay: Int, homeTask: Int?) {
        if (!summonable) {
            homeTask?.let(::raiseOwnTask)
            return
        }
        if (presenter?.invoke(focusDisplay, homeTask) == true) return
        open()
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(TOP)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_SWITCHER, true)
        runCatching { context.startActivity(intent, options.toBundle()) }.onFailure { Log.w(TAG, "summon", it) }
    }

    /** Raises one of Sortie's own tasks (navigation, companion) on its display; AppTask needs no Shizuku. */
    fun raiseOwnTask(id: Int): Boolean = runCatching {
        val task = context.getSystemService(ActivityManager::class.java).appTasks.firstOrNull { it.taskInfo.taskId == id }
            ?: return@runCatching false
        task.moveToFront()
        true
    }.onFailure { Log.w(TAG, "raise $id", it) }.getOrDefault(false)

    /** Raises [entry] on its display and gives that display the controller. The overlay closes: the user chose what to play. */
    fun bringToFront(entry: Entry) = act(dismiss = true) {
        pendingAnchor = null
        mover.focusTask(entry.task) && anchor(entry.task.displayId)
    }

    /**
     * "Sortie" from the switcher: raise Sortie's navigation where it already runs (its task is kept out of the carousels) and
     * give that display the controller; start it on [displayId] when none exists. Closes the switcher.
     */
    fun goHome(displayId: Int) = act(dismiss = true) {
        pendingAnchor = null
        val nav = mover.rootTasks().firstOrNull { it.component == "${context.packageName}/${MainActivity::class.java.name}" }
        if (nav != null) {
            mover.focusTask(nav) && anchor(nav.displayId)
        } else {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            runCatching {
                context.startActivity(
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    options.toBundle()
                )
            }.isSuccess
        }
    }

    /**
     * Moves [entry] to [displayId] and raises it there. Normally the switcher stays open: the display it left is marked
     * [State.freed] so its carousel offers every running window as a replacement, and the controller stays with the switcher
     * (a moved focused task drags display focus along, so the overlay's display is anchored again). A move *into* the freed
     * display is the user's replacement choice: it closes the switcher and hands the controller to that display.
     */
    fun moveTo(entry: Entry, displayId: Int) {
        val replacing = displayId == _state.value.freed
        act(dismiss = replacing, freed = if (replacing) null else entry.task.displayId) {
            val ok = mover.moveTask(entry.task, displayId) && mover.focusTask(entry.task)
            if (ok) {
                when {
                    replacing || !_state.value.overlay -> {
                        pendingAnchor = null // the replacement is the last choice: the controller goes with it
                        anchor(displayId)
                    }
                    else -> {
                        pendingAnchor = displayId
                        overlayDisplay?.let { anchor(it) }
                    }
                }
            }
            ok
        }
    }

    fun closeApp(entry: Entry) = act { mover.removeTask(entry.task) }

    /** Swaps the top app of each display: a → b and b → a in one go. Nothing is freed, so the overlay closes. */
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

    /** Restores the exact Sortie surface Home covered, or the prior external app, then anchors the overlay's display. */
    fun reveal(displayId: Int, homeTask: Int?) = scope.launch {
        if (homeTask != null) {
            raiseOwnTask(homeTask)
        } else {
            lock.withLock {
                reloadLocked()
                _state.value.on(displayId).firstOrNull()?.task?.let { mover.focusTask(it) }
            }
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
        val intent = Intent(context, AnchorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        lastAnchorAt = System.currentTimeMillis()
        anchorGuard.anchored(displayId)
        context.startActivity(intent, options.toBundle())
        true
    }.getOrElse {
        Log.w(TAG, "anchor $displayId", it)
        false
    }

    /** When the last anchor was started: a launch from a companion skips its own hand-off when one just happened. */
    @Volatile
    var lastAnchorAt = 0L
        private set

    fun anchoredRecently(): Boolean = System.currentTimeMillis() - lastAnchorAt < 1500

    private val anchorGuard = AnchorGuard(System::currentTimeMillis)

    /** Navigation resumed on [displayId] (see [AnchorGuard.navigationResumed]). */
    fun navigationResumed(displayId: Int) = anchorGuard.navigationResumed(displayId)

    /** A companion resumed on [displayId]: whether it hands the controller back, or the resume was our own anchor's (see [AnchorGuard]). */
    fun companionShouldHandOff(displayId: Int): Boolean = anchorGuard.companionResumed(displayId)

    /** A companion screen giving the controller back to the other screen; nothing to do on a single-screen device. */
    fun handOff(fromDisplay: Int) {
        other(fromDisplay)?.let { anchor(it) }
    }

    private fun act(dismiss: Boolean = false, freed: Int? = null, block: suspend () -> Boolean) = scope.launch {
        val ok = lock.withLock {
            _state.update { it.copy(busy = true) }
            runCatching { block() }.onFailure { Log.w(TAG, "switch", it) }.getOrDefault(false)
        }
        reload()
        _state.update { it.copy(freed = if (ok) freed else null) }
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

        /** Android's Home-key discriminator: automatic HOME starts during task/display changes do not carry it. */
        const val EXTRA_FROM_HOME_KEY = "android.intent.extra.FROM_HOME_KEY"
    }
}
