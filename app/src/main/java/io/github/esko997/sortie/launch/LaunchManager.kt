package io.github.esko997.sortie.launch

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.manifest.ManifestStore
import io.github.esko997.sortie.data.user.PlayLog
import io.github.esko997.sortie.data.user.UserStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Resolves a game to an ordered list of profiles (override, then system order), tries them, and records what happened. */
class LaunchManager(
    private val context: Context,
    private val manifests: ManifestStore,
    private val user: UserStore,
    private val playLog: PlayLog
) {
    data class Attempt(val profileId: String, val command: String, val outcome: String)

    data class LogEntry(val at: String, val gameId: String, val attempts: List<Attempt>, val launched: Boolean)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log

    fun profilesFor(game: GameEntity): List<LaunchProfile> {
        val snap = manifests.snapshot.value
        val system = snap.systems.firstOrNull { it.id == game.systemId }
        val ids = buildList {
            user.data.value.profileOverrides[game.id]?.let { add(it) }
            system?.profiles?.forEach { if (it !in this) add(it) }
            if ("open-with" !in this && snap.profiles.containsKey("open-with")) add("open-with")
        }
        return ids.mapNotNull { snap.profiles[it] }
    }

    fun isInstalled(profile: LaunchProfile): Boolean {
        val pkg = profile.`package` ?: return true
        return runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    suspend fun launch(game: GameEntity, only: LaunchProfile? = null): Boolean {
        val file = File(game.path)
        val attempts = mutableListOf<Attempt>()
        val candidates = only?.let { listOf(it) } ?: profilesFor(game)
        var launched = false
        for (profile in candidates) {
            if (!isInstalled(profile)) {
                attempts += Attempt(profile.id, "", "package ${profile.`package`} not installed")
                continue
            }
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.files",
                    file
                )
            }.getOrNull()
            val intent = if (profile.generic) {
                openWith(
                    file,
                    uri
                )
            } else {
                IntentBuilder.build(profile, IntentBuilder.vars(file, game.systemId, uri))
            }
            val command = IntentBuilder.describe(intent)
            if (uri != null && profile.data?.contains("\${uri}") == true) grant(intent, uri)
            val options = displayOptions(profile.display)
            try {
                context.startActivity(intent, options?.toBundle())
                attempts += Attempt(profile.id, command, "started")
                playLog.start(game.id, profile.id, profile.display)
                launched = true
                break
            } catch (e: ActivityNotFoundException) {
                attempts += Attempt(profile.id, command, "activity not found: ${e.message}")
            } catch (e: SecurityException) {
                attempts += Attempt(profile.id, command, "security: ${e.message}")
            } catch (e: Exception) {
                attempts += Attempt(profile.id, command, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        _log.value = (listOf(LogEntry(TS.format(Date()), game.id, attempts, launched)) + _log.value).take(50)
        return launched
    }

    private fun openWith(file: File, uri: Uri?): Intent {
        val target = uri ?: Uri.fromFile(file)
        val view = Intent(
            Intent.ACTION_VIEW
        ).setDataAndType(target, "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(view, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun grant(intent: Intent, uri: Uri) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.`package`?.let { context.grantUriPermission(it, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        intent.component?.packageName?.let {
            context.grantUriPermission(
                it,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun displayOptions(display: String): ActivityOptions? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val secondary = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        val id = when (display) {
            "top" -> android.view.Display.DEFAULT_DISPLAY
            "bottom" -> secondary?.displayId ?: return null
            else -> return null
        }
        return ActivityOptions.makeBasic().setLaunchDisplayId(id)
    }

    companion object {
        private val TS = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    }
}
