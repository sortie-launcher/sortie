package io.github.esko997.sortie.launch

import io.github.esko997.sortie.shell.ScreenMover.RootTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Knows when a game Sortie started has really ended, for the post-session bookmark invitation. Android games are not child
 * processes and Sortie coming to the front proves nothing (Home summons the switcher over a running game; the companion and
 * a temporary Home resume too), so the only evidence used is the task list read through Shizuku:
 *
 * 1. [started] watches a new game session. Shortly after the launch the game's own task is identified ([identify]): the
 *    visible task of the emulator's package. A session whose task was never seen is never reported.
 * 2. [observe] runs when the navigation comes back to the front with a fresh task list. The game has ended only when that
 *    list is trustworthy (it was read at all, and shows Sortie's own task) and no longer holds the identified task. Other
 *    tasks of the same package do not count: RetroArch, for one, keeps its menu task after the game's own task closes.
 *    Anything else (no Shizuku, a failed or empty read, the game's task still there) keeps watching: a transient Home, a
 *    switcher visit or a return to the game cannot end it, and a later real exit is still caught, once.
 *
 * The watch is independent of [io.github.esko997.sortie.data.user.PlayLog]'s open session, which may close earlier (without
 * Shizuku every return ends it). Nothing here is persisted: after a restart of Sortie a missed invitation is preferred to a
 * wrong one. The [invite] waits for the navigation to be in front and focused ([mayPresent]) and is answered once.
 */
class SessionEndWatch(private val clock: () -> Long = System::currentTimeMillis) {
    /** A game session being watched; [taskId] is null until [identify] saw the game's task. */
    data class Watched(val gameId: String, val startedAt: Long, val pkg: String, val taskId: Int? = null)

    /** A game that was seen to end at [endedAt]: offer its bookmark. */
    data class Invite(val gameId: String, val sessionStartedAt: Long, val endedAt: Long)

    private val lock = Any()
    private var watched: Watched? = null

    private val _invite = MutableStateFlow<Invite?>(null)

    /** The invitation waiting to be shown or answered; null when there is none. */
    val invite: StateFlow<Invite?> = _invite

    private val _editorRequest = MutableStateFlow<String?>(null)

    /** A game whose bookmark editor the player asked for from the invitation, until its page opens the editor. */
    val editorRequest: StateFlow<String?> = _editorRequest

    /** Current watch, for tests and diagnostics. */
    fun watching(): Watched? = synchronized(lock) { watched }

    /**
     * A game session started ([gameId] with the emulator package [pkg]). Replaces any earlier watch and drops an invitation
     * not yet answered: a new game makes the previous one's moment pass.
     */
    fun started(gameId: String, startedAt: Long, pkg: String) = synchronized(lock) {
        if (watched?.let { it.gameId == gameId && it.startedAt == startedAt } == true) return@synchronized
        watched = Watched(gameId, startedAt, pkg)
        _invite.value = null
    }

    /**
     * A session Sortie cannot watch started (an app or shortcut, or a game without a known emulator package, such as one opened
     * through Android's chooser): the earlier game's watch and any unanswered invitation end here, so a later exit of that
     * earlier game can never prompt over what the player moved on to.
     */
    fun stop() = synchronized(lock) {
        watched = null
        _invite.value = null
    }

    /**
     * Identifies the watched session's own task from a task list read after its launch. Returns true once it is known (or there
     * is nothing to identify any more), false to try again with a later list. A null [tasks] (unreadable) identifies nothing.
     */
    fun identify(gameId: String, startedAt: Long, tasks: List<RootTask>?): Boolean = synchronized(lock) {
        val w = watched?.takeIf { it.gameId == gameId && it.startedAt == startedAt } ?: return@synchronized true
        if (w.taskId != null) return@synchronized true
        val task = tasks?.firstOrNull { it.pkg == w.pkg && it.visible } ?: return@synchronized false
        watched = w.copy(taskId = task.id)
        true
    }

    /** The session closed right after starting ([SessionReturn.QUICK_EXIT]): nothing was played, nothing to bookmark. */
    fun forget(gameId: String, startedAt: Long) = synchronized(lock) {
        if (watched?.let { it.gameId == gameId && it.startedAt == startedAt } == true) watched = null
    }

    /**
     * The navigation is back in front and read [tasks] (null when it could not). Returns the watched game when this list proves it
     * ended (see the class comment), and stops watching it; null otherwise. [ownTask] is the navigation's own task id.
     */
    fun observe(tasks: List<RootTask>?, ownTask: Int): Watched? = synchronized(lock) {
        val w = watched ?: return@synchronized null
        val task = w.taskId ?: return@synchronized null
        if (tasks == null || tasks.none { it.id == ownTask }) return@synchronized null
        if (tasks.any { it.id == task }) return@synchronized null
        watched = null
        if (clock() - w.startedAt <= QUICK_EXIT_MS) null else w
    }

    /** Offers [ended]'s bookmark; replaces nothing already being answered for another game. */
    fun offer(ended: Watched) {
        _invite.compareAndSet(null, Invite(ended.gameId, ended.startedAt, clock()))
    }

    /** "Not now", or the invitation timed out on screen: it is not shown again. */
    fun dismiss(invite: Invite) {
        _invite.compareAndSet(invite, null)
    }

    /** "Leave a note": the invitation is answered and [invite]'s game page is asked to open its bookmark editor. */
    fun accept(invite: Invite) {
        if (_invite.compareAndSet(invite, null)) _editorRequest.value = invite.gameId
    }

    /** The game page opened the editor [editorRequest] asked for. */
    fun editorOpened(gameId: String) {
        _editorRequest.compareAndSet(gameId, null)
    }

    companion object {
        /** An invitation the navigation could not show within this long after the game ended is dropped unseen. */
        const val UNSHOWN_TTL_MS = 10 * 60_000L

        /** How long a shown invitation stays before it counts as "Not now". */
        const val SHOWN_MS = 45_000L

        /** When to look for a just-launched game's task: it may take a moment to appear (cold start, a trampoline activity). */
        val IDENTIFY_DELAYS_MS = listOf(750L, 1_500L, 3_000L, 6_000L)

        /**
         * Whether [invite] may be shown now: the setting is on, it is still fresh, and Sortie's own navigation is the resumed,
         * focused window on a real route with no switcher (screen or overlay) open. The companion, built-in apps and a
         * temporary Home never host it.
         */
        fun mayPresent(invite: Invite?, enabled: Boolean, now: Long, resumed: Boolean, windowFocused: Boolean, switcherOpen: Boolean, route: String?): Boolean =
            invite != null && enabled && now - invite.endedAt <= UNSHOWN_TTL_MS && resumed && windowFocused &&
                !switcherOpen && route != null && route != "switcher"
    }
}
