package io.github.esko997.sortie.launch

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Display
import androidx.core.content.FileProvider
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Resolves a game to an ordered list of profiles (override, then system order, then the generic one), tries them, records what happened. */
class LaunchManager(
    private val context: Context,
    private val manifests: ManifestStore,
    private val user: UserStore,
    private val playLog: PlayLog
) {
    data class Attempt(val profileId: String, val command: String, val outcome: String)

    data class LogEntry(val at: String, val gameId: String, val attempts: List<Attempt>, val launched: Boolean)

    /** Supplied by the activity: which display Sortie itself is on right now. */
    var currentDisplayId: (() -> Int)? = null

    /** Called right after an emulator was started, with the display it was sent to (or null for "wherever the system put it"). */
    var afterLaunch: ((displayId: Int?) -> Unit)? = null

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    fun profilesFor(game: GameEntity): List<LaunchProfile> {
        val snap = manifests.snapshot.value
        val system = snap.systems.firstOrNull { it.id == game.systemId }
        return profileOrder(game.id, game.systemId, user.data.value, system?.profiles.orEmpty(), snap.profiles)
    }

    fun isInstalled(profile: LaunchProfile): Boolean {
        val pkg = profile.`package` ?: return true
        return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    suspend fun launch(game: GameEntity, only: LaunchProfile? = null): Boolean {
        val file = File(game.path)
        val attempts = mutableListOf<Attempt>()
        val candidates = only?.let { listOf(it) } ?: profilesFor(game)
        var started: LaunchProfile? = null
        var startedDisplay: Int? = null
        for (profile in candidates) {
            if (!isInstalled(profile)) {
                attempts += Attempt(profile.id, "", "package ${profile.`package`} not installed")
                continue
            }
            val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull()
            val titleId = sidecarTitleId(file)
            val intent = if (profile.generic) openWith(file, uri) else IntentBuilder.build(profile, IntentBuilder.vars(file, game.systemId, uri, titleId))
            val (options, displayId, displayNote) = displayOptions(profile.display)
            val command = IntentBuilder.describe(intent, displayId)
            if (uri != null && !profile.generic && IntentBuilder.usesContentUri(profile)) grant(intent, uri)
            try {
                context.startActivity(intent, options?.toBundle())
                attempts += Attempt(profile.id, command, "started" + (displayNote?.let { " ($it)" } ?: ""))
                started = profile
                startedDisplay = displayId
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: ActivityNotFoundException) {
                attempts += Attempt(profile.id, command, "activity not found: ${e.message}")
            } catch (e: SecurityException) {
                attempts += Attempt(profile.id, command, "security: ${e.message}")
            } catch (e: Exception) {
                attempts += Attempt(profile.id, command, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        _log.update { (listOf(LogEntry(LocalTime.now().format(TS), game.id, attempts, started != null)) + it).take(50) }
        attempts.forEach { Log.i("SortieLaunch", "${game.id} via ${it.profileId}: ${it.outcome} | ${it.command}") }
        // Record the session outside the try/catch so a logging failure can never trigger a second launch.
        started?.let { runCatching { playLog.start(game.id, it.id, it.display) }.onFailure { e -> Log.w("SortieLaunch", "play log", e) } }
        if (started != null) afterLaunch?.invoke(startedDisplay) // last: the activity may finish itself here
        return started != null
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
            displayOptions("same")
        }
        val outcome = try {
            start(options?.toBundle())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        val launched = outcome == null
        val described = command + (targetId?.let { " --display $it" } ?: "")
        val attempt = Attempt(id.substringBefore(':'), described, outcome ?: ("started" + (displayNote?.let { " ($it)" } ?: "")))
        _log.update { (listOf(LogEntry(LocalTime.now().format(TS), id, listOf(attempt), launched)) + it).take(50) }
        Log.i("SortieLaunch", "$id: ${attempt.outcome} | ${attempt.command}")
        if (!launched) return false
        if (displayId != null) return true
        runCatching { playLog.start(id, null, "same") }.onFailure { e -> Log.w("SortieLaunch", "play log", e) }
        afterLaunch?.invoke(targetId) // last: the activity may finish itself here
        return true
    }

    /** `<game>.titleid` next to the file (or `<stem>.psvita`, ES-DE's convention) holds a title id when the emulator needs one. */
    private fun sidecarTitleId(file: File): String? {
        val dir = file.parentFile ?: return null
        return listOf(File(dir, file.name + ".titleid"), File(dir, file.nameWithoutExtension + ".psvita"))
            .firstOrNull { it.isFile && it.length() in 1..64 }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun openWith(file: File, uri: Uri?): Intent {
        val target = uri ?: Uri.fromFile(file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(target, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun grant(intent: Intent, uri: Uri) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        (intent.component?.packageName ?: intent.`package`)?.let { context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * The display an emulator is sent to. "same" always resolves to an explicit id: without one Android reuses the emulator's
     * existing task wherever it is (after a switcher move, on the other screen) instead of the screen the user is looking at.
     * On a dual-screen device whose navigation lives on the bottom, "same" means the top screen.
     */
    private fun displayOptions(display: String): Triple<ActivityOptions?, Int?, String?> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val screens = manifests.snapshot.value.device.screens
        val secondary = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).getOrNull(screens.presentationIndex)
        val current = currentDisplayId?.invoke() ?: Display.DEFAULT_DISPLAY
        val id = when (display) {
            "top" -> Display.DEFAULT_DISPLAY
            "bottom" -> secondary?.displayId ?: return Triple(null, null, "no secondary display, launched on the current one")
            else -> if (secondary != null && screens.navigation == "bottom") Display.DEFAULT_DISPLAY else current
        }
        return Triple(ActivityOptions.makeBasic().setLaunchDisplayId(id), id, null)
    }

    companion object {
        private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
