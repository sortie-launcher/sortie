package io.github.esko997.sortie.ui

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.shell.ScreenMover
import kotlinx.coroutines.delay

/** Where All files access lives when Settings can't be opened on it directly (wording of stock Android 11–15). */
const val ALL_FILES_ACCESS_PATH = "Settings → Apps → Special app access → All files access → Sortie"

/** Where the optional "Display over other apps" permission lives, for the same fallback. */
const val OVERLAY_ACCESS_PATH = "Settings → Apps → Special app access → Display over other apps → Sortie"

/**
 * Opens the first of [intents] this device can show, in a new task. Returns false when none of them could be opened, so the
 * caller can say where to go instead of silently doing nothing.
 */
fun openSettingsScreen(context: Context, vararg intents: Intent): Boolean {
    for (base in intents) {
        val intent = Intent(base).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) continue
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return false
}

private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

/** Sortie's All files access switch, else the list of apps with that access, else Sortie's app info, else Settings. */
fun openAllFilesAccessSettings(context: Context): Boolean = openSettingsScreen(
    context,
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    Intent(Settings.ACTION_SETTINGS)
)

/** Sortie's optional "Display over other apps" switch (the switcher overlay), with the same fallbacks. */
fun openOverlayPermissionSettings(context: Context): Boolean = openSettingsScreen(
    context,
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context)),
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
    Intent(Settings.ACTION_SETTINGS)
)

/** The device's displays right now, kept current as screens are added, removed or switched off. */
@Composable
private fun rememberDisplays(): List<Display> {
    val context = LocalContext.current
    val dm = remember(context) { context.getSystemService(DisplayManager::class.java) }
    var displays by remember(dm) { mutableStateOf(dm?.displays?.toList().orEmpty()) }
    DisposableEffect(dm) {
        if (dm == null) return@DisposableEffect onDispose { }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                displays = dm.displays.toList()
            }

            override fun onDisplayRemoved(displayId: Int) {
                displays = dm.displays.toList()
            }

            override fun onDisplayChanged(displayId: Int) {
                displays = dm.displays.toList()
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displays = dm.displays.toList()
        onDispose { dm.unregisterDisplayListener(listener) }
    }
    return displays
}

private fun displayLabel(d: Display): String = (if (d.displayId == Display.DEFAULT_DISPLAY) "main" else d.name.ifBlank { "screen" }) + " (${d.displayId})"

private fun shizukuText(status: ScreenMover.Status): String = when (status) {
    ScreenMover.Status.READY -> "Shizuku: ready. Sortie can move apps between screens."
    ScreenMover.Status.PERMISSION_NEEDED -> "Shizuku: running, but Sortie hasn't been allowed to use it yet."
    ScreenMover.Status.NOT_RUNNING -> "Shizuku: not running (optional). Start it in the Shizuku app to move apps between screens."
    ScreenMover.Status.UNAVAILABLE -> "Shizuku: not installed or too old (optional). Everything else works without it."
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(vm: SortieViewModel) {
    val index by vm.index.collectAsState()
    val log by vm.launchLog.collectAsState()
    val snap by vm.manifests.collectAsState()
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            var debuggingFailed by remember { mutableStateOf(false) }
            FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton(if (index.running) "Indexing…" else "Re-index everything", enabled = !index.running, primary = true) { vm.reindex(force = true) }
                GlowButton("Reload manifests") { vm.reloadManifests() }
                GlowButton("Wireless debugging") {
                    // Developer options, scrolled to Wireless debugging (the fragment arg Settings itself uses)
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    debuggingFailed = !openSettingsScreen(context, intent)
                }
            }
            if (debuggingFailed) {
                Text(
                    "Developer options couldn't be opened. Turn them on first: Settings → About phone → tap Build number seven times.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                "Manifests: ${snap.systems.size} systems · ${snap.profiles.size} profiles · ${snap.folders.size} folders · guide ${snap.guide.size}",
                style = MaterialTheme.typography.bodySmall
            )
            snap.errors.forEach { Text("! $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            index.report?.let { r ->
                Text("Last index: ${r.files} files, ${r.updated} updated, ${r.removed} removed, ${r.millis} ms", style = MaterialTheme.typography.bodySmall)
                r.problems.forEach { Text("! $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            val shizuku by vm.shizuku.collectAsState()
            val moveResult by vm.moveResult.collectAsState()
            // Real displays, not the manifest's idea of them: a move needs Shizuku and two screens that are switched on.
            val displays = rememberDisplays()
            val usable = displays.filter { it.state != Display.STATE_OFF }
            SectionTitle("Screens")
            Text(
                if (displays.isEmpty()) {
                    "No displays reported."
                } else {
                    displays.joinToString(
                        " · "
                    ) { displayLabel(it) + if (it.state == Display.STATE_OFF) " — off" else "" }
                },
                style = MaterialTheme.typography.bodySmall
            )
            Text(shizukuText(shizuku), style = MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton("Refresh") { vm.shizukuRefresh() }
                if (shizuku == ScreenMover.Status.PERMISSION_NEEDED) GlowButton("Allow Sortie in Shizuku") { vm.shizukuRequest() }
                if (shizuku == ScreenMover.Status.READY && usable.size > 1) {
                    for (from in usable) {
                        for (to in usable) {
                            if (from.displayId == to.displayId) continue
                            GlowButton("App on ${displayLabel(from)} → ${displayLabel(to)}") { vm.moveTopApp(from.displayId, to.displayId) }
                        }
                    }
                }
            }
            if (shizuku == ScreenMover.Status.READY && usable.size < 2) {
                Text(
                    "Moving apps needs a second screen that is switched on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (moveResult.isNotBlank()) Text(moveResult, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionTitle("Launch log (newest first)")
        }
        items(log) { e ->
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("${e.at}  ${e.gameId}  ${if (e.launched) "✓" else "✗"}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                e.attempts.forEach { a ->
                    Text("  ${a.profileId}: ${a.outcome}", style = MaterialTheme.typography.bodySmall)
                    if (a.command.isNotBlank()) {
                        Text(
                            "    adb shell ${a.command}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Shown instead of everything else until All files access is on; MainActivity re-checks it every time Sortie returns. */
@Composable
fun PermissionScreen() {
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Let Sortie see your games", style = MaterialTheme.typography.titleLarge)
        Text(
            "Sortie lists the games stored on this device and its memory card, and keeps your favourites, notes and play time next to " +
                "them. Android asks you to allow this once, as \"All files access\". Sortie has no internet access.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "1. Press Open settings.\n2. Turn on \"Allow access to manage all files\" for Sortie.\n3. Go back; Sortie opens your library.",
            style = MaterialTheme.typography.bodyMedium
        )
        GlowButton("Open settings", Modifier.focusRequester(primary), primary = true) { failed = !openAllFilesAccessSettings(ctx) }
        if (failed) {
            Text(
                "Android settings couldn't be opened from here. Open the Settings app yourself and go to $ALL_FILES_ACCESS_PATH.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(
                "If Settings opens on a different page, look for $ALL_FILES_ACCESS_PATH.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
