package io.github.esko997.sortie.shell

/**
 * Decides whether a companion that just resumed hands the controller back to the other screen. An anchor started on a display
 * pauses whatever is on top there and resumes it a frame later; when that is a companion, handing off again would anchor the
 * other screen, whose companion would anchor back (two screens ping-ponging). So the resume an anchor causes on its own target
 * display is skipped, once.
 *
 * Only that resume: navigation resuming on the target shows the anchor has done its job, and from then on a companion
 * appearing there (navigation swapped away within the same second) must hand off again, or the controller stays behind on the
 * companion's screen. A target nothing of Sortie's resumes on (a game) stops counting after [windowMs].
 */
internal class AnchorGuard(private val now: () -> Long, private val windowMs: Long = 1_500) {
    private var target: Int? = null
    private var at = 0L

    /** An anchor was started on [displayId]. */
    @Synchronized
    fun anchored(displayId: Int) {
        target = displayId
        at = now()
    }

    /** Navigation resumed on [displayId]: an earlier anchor there has landed and explains no later companion resume. */
    @Synchronized
    fun navigationResumed(displayId: Int) {
        if (target == displayId) target = null
    }

    /** A companion resumed on [displayId]: true when it should hand the controller to the other screen. */
    @Synchronized
    fun companionResumed(displayId: Int): Boolean {
        val t = target ?: return true
        if (now() - at >= windowMs) {
            target = null
            return true
        }
        if (t != displayId) return true
        target = null // our anchor's own resume, which happens once
        return false
    }
}
