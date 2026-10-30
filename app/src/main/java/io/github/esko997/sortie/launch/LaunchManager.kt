package io.github.esko997.sortie.launch

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.manifest.UserData
import io.github.esko997.sortie.data.setup.LibraryConfiguration
import io.github.esko997.sortie.data.setup.LibrarySetupStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Tries the game's configured profiles in order (game override, system override, system list), recording each attempt. */
class LaunchManager(
    private val context: Context,
    private val manifests: ManifestStore,
    private val user: UserStore,
    private val playLog: PlayLog,
    /** Saves the app picked in an Open with… fallback as the system's default launcher (see [chooserSelected]). */
    private val librarySetup: LibrarySetupStore
) {
    /** Why an attempt did not start, for the user-facing failure message (the [Attempt.outcome] keeps the exact error). */
    enum class Reason { STARTED, NO_PROFILE, FILE_MISSING, NOT_INSTALLED, NOT_FOUND, DENIED, ERROR }

    data class Attempt(val profileId: String, val command: String, val outcome: String, val reason: Reason = Reason.STARTED)

    data class LogEntry(val at: String, val gameId: String, val attempts: List<Attempt>, val launched: Boolean)

    /** Supplied by the navigation activity: which display Sortie's navigation is on right now. */
    var currentDisplayId: (() -> Int)? = null

    /**
     * Supplied by the navigation activity, called right after an emulator was started from it, with the display it was sent to
     * (or null for "wherever the system put it"). Never for a launch [from] another window (a built-in app; see [launch]).
     */
    var afterLaunch: ((displayId: Int?) -> Unit)? = null

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    private val main = Handler(Looper.getMainLooper())

    /** Card writes for a remembered choice run here, off the chooser broadcast, so a slow card never delays its completion. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One remembered choice at a time: two quick picks for one system must not both add a profile. */
    private val remembering = Mutex()

    /** Wall-clock time of the latest Home press Sortie received ([homeKeyPressed]); see [sessionReturn]. */
    @Volatile
    var homeKeyAt = 0L
        private set

    /** The game Sortie last started and what that asked of its app, for a [quickExit] notice. */
    private class Started(val gameId: String, val pkg: String?, val switch: ContentSwitch)

    @Volatile
    private var lastStarted: Started? = null

    /** Per libretro app (package): the core and file it was last asked to run in this process; see [contentSwitch]. */
    private val libretroContent = ConcurrentHashMap<String, Pair<String, String>>()

    fun profilesFor(game: GameEntity): List<LaunchProfile> {
        val snap = manifests.snapshot.value
        val system = snap.systems.firstOrNull { it.id == game.systemId }
        return profileOrder(game.id, game.systemId, user.data.value, system?.profiles.orEmpty(), snap.profiles)
    }

    fun isInstalled(profile: LaunchProfile): Boolean {
        val pkg = profile.`package` ?: return true
        return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * Starts [game] with its first profile that starts ([only]: just that one). [from] is the display of the window that asked
     * when that is not Sortie's navigation (a built-in app such as Magazines): it stands in for the navigation's display in the
     * "same" display rule and for a failure message, and [afterLaunch] is not called, because the navigation launched nothing
     * and must stay where it is. The play session opens either way: a game did start. [on] is a display the player picked for
     * this launch ("Play on the other screen"): it replaces every profile's display rule, the Open with… chooser's too. Only the
     * start is placed there; an emulator that moves itself afterwards is left alone.
     */
    suspend fun launch(game: GameEntity, only: LaunchProfile? = null, from: Int? = null, on: Int? = null): Boolean {
        val file = File(game.path)
        val attempts = mutableListOf<Attempt>()
        val snap = manifests.snapshot.value
        val userData = user.data.value
        val systemProfiles = snap.systems.firstOrNull { it.id == game.systemId }?.profiles.orEmpty()
        val candidates = only?.let { listOf(it) } ?: profileOrder(game.id, game.systemId, userData, systemProfiles, snap.profiles)
        var started: LaunchProfile? = null
        var startedDisplay: Int? = null
        // Nothing to try: the file is gone or the system has no profile. Recorded as an attempt so Diagnostics shows it.
        val blocked = when {
            definitelyMissing(file) -> Attempt("file", "", "file not found: ${file.path}", Reason.FILE_MISSING)
            candidates.isEmpty() -> Attempt("none", "", "no launch profile for system ${game.systemId}", Reason.NO_PROFILE)
            else -> null
        }
        blocked?.let { attempts += it }
        for (profile in if (blocked == null) candidates else emptyList()) {
            if (!isInstalled(profile)) {
                attempts += Attempt(profile.id, "", "package ${profile.`package`} not installed", Reason.NOT_INSTALLED)
                continue
            }
            // Everything that touches the file, the profile's template or the display belongs to the attempt: a malformed
            // profile or an unreadable sidecar is reported like a failed start, and the next profile is tried.
            var command = ""
            try {
                val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull()
                val titleId = sidecarTitleId(file)
                val (options, displayId, displayNote) = displayOptions(profile.display, from, on)
                val intent = if (profile.generic) {
                    val origin = chooserOrigin(game.id, game.systemId, userData, systemProfiles, snap.profiles, profile, explicit = only != null)
                    openWith(file, uri, game.id, profile, displayId, origin, scoped = from != null)
                } else {
                    IntentBuilder.build(profile, IntentBuilder.vars(file, game.systemId, uri, titleId))
                }
                command = IntentBuilder.describe(intent, displayId)
                if (uri != null && !profile.generic && IntentBuilder.usesContentUri(profile)) grant(intent, uri)
                context.startActivity(intent, options?.toBundle())
                val outcome = if (profile.generic) "chooser opened; waiting for app selection" else "started"
                attempts += Attempt(profile.id, command, outcome + (displayNote?.let { " ($it)" } ?: ""))
                started = profile
                startedDisplay = displayId
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                attempts += Attempt(profile.id, command, "activity not found: ${e.message}", Reason.NOT_FOUND)
            } catch (e: SecurityException) {
                attempts += Attempt(profile.id, command, "security: ${e.message}", Reason.DENIED)
            } catch (e: Exception) {
                attempts += Attempt(profile.id, command, "${e.javaClass.simpleName}: ${e.message}", Reason.ERROR)
            }
        }
        _log.update { (listOf(LogEntry(LocalTime.now().format(TS), game.id, attempts, started != null)) + it).take(50) }
        attempts.forEach { Log.i("SortieLaunch", "${game.id} via ${it.profileId}: ${it.outcome} | ${it.command}") }
        if (started == null) {
            val system = manifests.snapshot.value.systems.firstOrNull { it.id == game.systemId }?.label ?: game.systemId
            notify(gameFailureMessage(game.title, system, attempts, candidates.associate { it.id to it.label }), from ?: sortieDisplay())
        }
        // A chooser opening is not a game starting. Its private sender records only an actual selection.
        started?.takeUnless { it.generic }?.let {
            val pkg = it.`package`
            val content = libretroContent(it, game.path)
            val switch = if (pkg == null) ContentSwitch.NONE else contentSwitch(libretroContent[pkg], content)
            if (pkg != null && content != null) libretroContent[pkg] = content
            lastStarted = Started(game.id, pkg, switch)
            runCatching { playLog.start(game.id, it.id, it.display) }.onFailure { e -> Log.w("SortieLaunch", "play log", e) }
            if (from == null) afterLaunch?.invoke(startedDisplay) // last: the activity may finish itself here
        }
        return started != null
    }

    /**
     * The user picked [chosen] in the Open with… chooser: logs it and opens the play session. [origin] (see [chooserOrigin])
     * is non-null only when the chooser was the system's fallback after its emulators; then the pick also becomes that
     * system's default (see [rememberChoice]), so the chooser is not asked again on every launch. A one-off, per-game or
     * deliberately configured Open with… never changes the system. [scoped]: the launch came from a built-in app's window
     * (see [launch]'s `from`), so the navigation's [afterLaunch] does not apply.
     */
    internal suspend fun chooserSelected(
        gameId: String,
        profileId: String,
        display: String,
        target: Int?,
        chosen: ComponentName,
        origin: List<String>?,
        scoped: Boolean
    ) {
        val attempt = Attempt(profileId, chosen.flattenToShortString(), "app selected")
        _log.update { (listOf(LogEntry(LocalTime.now().format(TS), gameId, listOf(attempt), true)) + it).take(50) }
        Log.i("SortieLaunch", "$gameId via $profileId: app selected | ${attempt.command}")
        lastStarted = Started(gameId, chosen.packageName, ContentSwitch.NONE)
        runCatching { playLog.start(gameId, profileId, display) }.onFailure { e -> Log.w("SortieLaunch", "play log", e) }
        origin?.let { background.launch { rememberChoice(gameId, profileId, chosen, it) } }
        if (!scoped) afterLaunch?.invoke(target)
    }

    /**
     * Saves [chosen] as the game's system default ([rememberedChoice]): the `chosen-<package>` profile in launchers.json
     * and the selection in user.json's systemProfiles, never a reordered systems.json. Rechecked against the configuration
     * on the card and the user data, which must still reach the chooser through [origin]: anything the user changed while
     * the chooser was open wins. Best effort: any failure is logged and the launch, already done, is unaffected.
     */
    private suspend fun rememberChoice(gameId: String, profileId: String, chosen: ComponentName, origin: List<String>) {
        try {
            remembering.withLock {
                val pkg = chosen.packageName
                if (pkg == context.packageName) return
                val pm = context.packageManager
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg
                fun plan(config: LibraryConfiguration, data: UserData) =
                    rememberedChoice(gameId, profileId, origin, data, config.systems, config.profiles, pkg, chosen.className, label)
                var config = librarySetup.load()
                val choice = plan(config, user.data.value)
                if (choice == null) {
                    Log.i("SortieLaunch", "$gameId: Open with… choice not remembered; the launch settings changed meanwhile")
                    return
                }
                choice.profile?.let { config = librarySetup.saveProfiles(config, listOf(it)) }
                val current = user.data.value
                val confirmed = plan(config, current)?.profileId == choice.profileId &&
                    user.replace(current, current.copy(systemProfiles = current.systemProfiles + (choice.systemId to choice.profileId)))
                if (confirmed) {
                    Log.i("SortieLaunch", "${choice.systemId}: remembered Open with… choice as ${choice.profileId}")
                } else {
                    Log.i("SortieLaunch", "${choice.systemId}: Open with… choice not set as default; the user data changed meanwhile")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SortieLaunch", "remember Open with… choice for $gameId", e)
        }
    }

    /**
     * Starts an installed app or a launcher shortcut ([id] `app:…`/`shortcut:…`) under the same rules as a game: the "same"
     * display rule (top screen when navigation lives on the bottom one), the activity hooks, the launch log and the play log.
     * [start] performs the actual start with the ActivityOptions bundle, or null for "wherever the system puts it".
     * A [displayId] overrides the display rule for a side launch from a companion screen: the app opens on that display next to
     * whatever runs on the other one, so it neither opens a play session (that would abandon the running game's) nor fires
     * [afterLaunch] (Sortie's navigation stays where it is).
     */
    suspend fun launchExternal(id: String, command: String, displayId: Int? = null, start: (Bundle?) -> Unit): Boolean {
        val (options, targetId, displayNote) = if (displayId != null) {
            Triple(ActivityOptions.makeBasic().setLaunchDisplayId(displayId), displayId, "companion")
        } else {
            // a display lookup that fails must not lose the launch: let the system place it, and say so in the log
            runCatching { displayOptions("same") }.getOrElse { Triple(null, null, "display rule failed: ${it.message}") }
        }
        val failure: Pair<Reason, String>? = try {
            start(options?.toBundle())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivityNotFoundException) {
            Reason.NOT_FOUND to "activity not found: ${e.message}"
        } catch (e: SecurityException) {
            Reason.DENIED to "security: ${e.message}"
        } catch (e: Exception) {
            Reason.ERROR to "${e.javaClass.simpleName}: ${e.message}"
        }
        val launched = failure == null
        val described = command + (targetId?.let { " --display $it" } ?: "")
        val attempt = Attempt(
            id.substringBefore(':'),
            described,
            failure?.second ?: ("started" + (displayNote?.let { " ($it)" } ?: "")),
            failure?.first ?: Reason.STARTED
        )
        _log.update { (listOf(LogEntry(LocalTime.now().format(TS), id, listOf(attempt), launched)) + it).take(50) }
        Log.i("SortieLaunch", "$id: ${attempt.outcome} | ${attempt.command}")
        if (failure != null) {
            notify(externalFailureMessage(appLabel(id), id.startsWith(SHORTCUT_PREFIX), failure.first), displayId ?: sortieDisplay())
            return false
        }
        if (displayId != null) return true
        runCatching { playLog.start(id, null, "same") }.onFailure { e -> Log.w("SortieLaunch", "play log", e) }
        afterLaunch?.invoke(targetId) // last: the activity may finish itself here
        return true
    }

    /** A Home key press reached Sortie (either screen): the player is coming back on purpose. */
    fun homeKeyPressed() {
        homeKeyAt = System.currentTimeMillis()
    }

    /** [pkg] is known to have no task any more: its next start loads fresh content, whatever it ran before. */
    fun appClosed(pkg: String) {
        libretroContent.remove(pkg)
    }

    /**
     * The game [gameId] (played with [profileId]) closed right after starting ([SessionReturn.QUICK_EXIT]): logs it and returns
     * what to tell the player. Its app has closed, so it no longer holds the content it was switching from.
     */
    fun quickExit(gameId: String, profileId: String?): String {
        val started = lastStarted?.takeIf { it.gameId == gameId }
        val profile = profileId?.let { manifests.snapshot.value.profiles[it] }
        val pkg = started?.pkg ?: profile?.`package`
        pkg?.let(::appClosed)
        val name = pkg?.let { appLabel("app:$it") } ?: profile?.label ?: "The emulator"
        val switch = started?.switch ?: ContentSwitch.NONE
        Log.i("SortieLaunch", "$gameId via $profileId: closed right after starting ($switch)")
        return quickExitMessage(name, switch)
    }

    /**
     * True only when the game file is certainly gone: its folder is missing (card removed) or listable without it. A folder
     * that exists but cannot be listed says nothing, so the launch is still tried and the emulator reports what it can.
     */
    private fun definitelyMissing(file: File): Boolean = runCatching {
        if (file.isFile) return@runCatching false
        val dir = file.parentFile ?: return@runCatching true
        !dir.exists() || dir.list() != null
    }.getOrDefault(false)

    /** Sortie's display for a message; null (default display) when no activity supplies it or it has gone away. */
    private fun sortieDisplay(): Int? = runCatching { currentDisplayId?.invoke() }.getOrNull()

    /**
     * Tells the user why a launch did not start (or stopped at once): a long toast on [displayId], by default Sortie's own
     * (null: the default display context). Owned here, not by a screen, so failures from any activity or companion picker are
     * shown exactly once even when navigation has finished. Safe from any thread.
     */
    fun notify(message: String, displayId: Int? = sortieDisplay()) {
        main.post {
            runCatching {
                val app = context.applicationContext
                val display = displayId?.let { app.getSystemService(DisplayManager::class.java)?.getDisplay(it) }
                Toast.makeText(display?.let { app.createDisplayContext(it) } ?: app, message, Toast.LENGTH_LONG).show()
            }.onFailure { Log.w("SortieLaunch", "failure toast", it) }
        }
    }

    /** The installed app's own name for an `app:<pkg>` or `shortcut:<pkg>/<id>` id; null when it is not installed. */
    private fun appLabel(id: String): String? {
        val pkg = id.substringAfter(':').substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * `<game>.titleid` next to the file (or `<stem>.psvita`, ES-DE's convention) holds a title id when the emulator needs one.
     * An unreadable sidecar is logged and treated as absent: most profiles don't use it.
     */
    private fun sidecarTitleId(file: File): String? = runCatching {
        val dir = file.parentFile ?: return@runCatching null
        listOf(File(dir, file.name + ".titleid"), File(dir, file.nameWithoutExtension + ".psvita"))
            .firstOrNull { it.isFile && it.length() in 1..64 }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrElse {
        Log.w("SortieLaunch", "title id sidecar for ${file.name}", it)
        null
    }

    private fun openWith(file: File, uri: Uri?, gameId: String, profile: LaunchProfile, displayId: Int?, origin: List<String>?, scoped: Boolean): Intent {
        val target = uri ?: Uri.fromFile(file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(target, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, file.name, ChooserLaunchReceiver.sender(context, gameId, profile, displayId, origin, scoped))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun grant(intent: Intent, uri: Uri) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (intent.component?.packageName ?: intent.`package`)?.let { context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * The display an emulator is sent to (see [launchDisplay]), with a note for the launch log when the profile's rule was not
     * what decided it. A picked display ([on]) that has gone away since it was offered is ignored: the profile decides.
     */
    private fun displayOptions(display: String, from: Int? = null, on: Int? = null): Triple<ActivityOptions?, Int?, String?> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val screens = manifests.snapshot.value.device.screens
        val secondary = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(screens.presentationIndex)
        val picked = on?.takeIf { dm.getDisplay(it) != null }
        val current = from ?: currentDisplayId?.invoke() ?: Display.DEFAULT_DISPLAY
        val note = when {
            picked != null -> "other screen as asked, instead of the profile's \"$display\""
            on != null -> "asked-for display $on is gone, used the profile's \"$display\""
            else -> null
        }
        val id = launchDisplay(display, picked, current, secondary?.displayId, screens.navigation == "bottom", scoped = from != null)
            ?: return Triple(null, null, listOfNotNull(note, "no secondary display, launched on the current one").joinToString("; "))
        return Triple(ActivityOptions.makeBasic().setLaunchDisplayId(id), id, note)
    }

    companion object {
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        private const val SHORTCUT_PREFIX = "shortcut:"
        private const val DETAILS = "Details are in Settings → Diagnostics."

        /**
         * What to tell the user when no profile started [title]. [labels] maps profile ids to their names. The first problem that
         * stops every launch wins (missing file, no emulator set up); otherwise the last attempt that reached Android explains
         * the failure, and when every emulator is missing that is the message.
         */
        fun gameFailureMessage(title: String, system: String, attempts: List<Attempt>, labels: Map<String, String>): String {
            val name = title.ifBlank { "this game" }
            if (attempts.any { it.reason == Reason.FILE_MISSING }) {
                return "Can't start $name: its file isn't on this device right now. " +
                    "Check that the storage card is inserted, then re-index in Settings → Diagnostics."
            }
            if (attempts.isEmpty() || attempts.any { it.reason == Reason.NO_PROFILE }) {
                return "Can't start $name: no emulator is set up for $system yet. Add one in Settings."
            }
            fun label(a: Attempt) = labels[a.profileId] ?: a.profileId
            val tried = attempts.lastOrNull { it.reason != Reason.NOT_INSTALLED && it.reason != Reason.STARTED }
            if (tried == null) {
                val missing = attempts.filter { it.reason == Reason.NOT_INSTALLED }.map(::label).distinct()
                val names = if (missing.size <= 2) missing.joinToString(" or ") else missing.take(2).joinToString(", ") + " or another emulator"
                val verb = if (missing.size == 1) "isn't" else "aren't"
                return "Can't start $name: $names $verb installed. Install it, or choose another emulator for $system in Settings."
            }
            val emulator = label(tried)
            return when (tried.reason) {
                Reason.NOT_FOUND ->
                    "Can't start $name: $emulator didn't accept the game. Its launch settings may not match the installed version; " +
                        "try another emulator from the game's page. $DETAILS"
                Reason.DENIED -> "Can't start $name: Android didn't let Sortie open $emulator. $DETAILS"
                else -> "Can't start $name with $emulator. $DETAILS"
            }
        }

        /** What to tell the user when an app ([shortcut] = false) or one of its shortcuts did not open; [label] null = not installed. */
        fun externalFailureMessage(label: String?, shortcut: Boolean, reason: Reason): String {
            if (label == null) {
                return if (shortcut) "Can't open that shortcut: its app isn't installed any more." else "Can't open that app: it isn't installed any more."
            }
            val what = if (shortcut) "this $label shortcut" else label
            return when (reason) {
                Reason.NOT_FOUND -> if (shortcut) {
                    "Can't open $what: $label may have removed or disabled it. Add it again from the Apps screen."
                } else {
                    "Can't open $label: it may have been disabled or have no screen to open."
                }
                Reason.DENIED -> "Can't open $what: Android didn't allow it. $DETAILS"
                else -> "Can't open $what. $DETAILS"
            }
        }
    }
}
