package io.github.esko997.sortie.launch

import io.github.esko997.sortie.data.manifest.LaunchProfile

/** A game whose emulator is gone this soon after Sortie started it never really started (see [sessionReturn]). */
const val QUICK_EXIT_MS = 3_000L

/** What Sortie coming back to the front means for the open play session. */
enum class SessionReturn {
    /** The emulator still has a task: Home only summoned Sortie over it. The session stays open. */
    RUNNING,

    /** The game was played (or the player pressed Home) and the session ends normally. */
    ENDED,

    /** The emulator closed right after starting: the session ends uncounted and the player is told. */
    QUICK_EXIT
}

/**
 * Decides [SessionReturn] from signals Sortie already has when it resumes, without polling. [appRunning] is whether the
 * session's app still has a task (null when unknown: no Shizuku, or no package to look for). [homeKeyAt] is the wall-clock
 * time of the latest Home press Sortie received. A Home press after the start means the player came back on purpose, so
 * a quick return with the app's state unknown is not a quick exit; with Shizuku the still-running emulator says so too.
 */
fun sessionReturn(startedAt: Long, now: Long, appRunning: Boolean?, homeKeyAt: Long): SessionReturn = when {
    appRunning == true -> SessionReturn.RUNNING
    now - startedAt > QUICK_EXIT_MS -> SessionReturn.ENDED
    homeKeyAt >= startedAt -> SessionReturn.ENDED
    else -> SessionReturn.QUICK_EXIT
}

/** What a libretro app (RetroArch) was asked to change by a launch, compared with the content it last got from Sortie. */
enum class ContentSwitch { NONE, GAME, CORE }

/** The core and file a libretro [profile] loads for [path]; null for an emulator that is not a libretro frontend. */
fun libretroContent(profile: LaunchProfile, path: String): Pair<String, String>? = profile.extras["LIBRETRO"]?.let { it to path }

/** [next] against the content the same app last received ([previous], null when none in this process). */
fun contentSwitch(previous: Pair<String, String>?, next: Pair<String, String>?): ContentSwitch = when {
    previous == null || next == null -> ContentSwitch.NONE
    previous.first != next.first -> ContentSwitch.CORE
    previous.second != next.second -> ContentSwitch.GAME
    else -> ContentSwitch.NONE
}

/**
 * The notice for a quick exit of [app]. RetroArch 1.22 closes itself when asked to open different content while it runs
 * (docs/LAUNCHERS.md); the next Play then starts normally, so that case says so.
 */
fun quickExitMessage(app: String, switch: ContentSwitch): String = when (switch) {
    ContentSwitch.CORE -> "$app closed while switching cores — press Play again."
    ContentSwitch.GAME -> "$app closed while switching games — press Play again."
    ContentSwitch.NONE -> "$app closed right after starting."
}
