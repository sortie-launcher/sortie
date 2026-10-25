package io.github.esko997.sortie.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.manifest.BrowseChromeMode
import io.github.esko997.sortie.stats.CompanionView
import io.github.esko997.sortie.stats.DeviceStatsMode
import io.github.esko997.sortie.stats.nextPerfInterval
import io.github.esko997.sortie.stats.nextStatsInterval
import io.github.esko997.sortie.stats.perfInterval
import io.github.esko997.sortie.stats.statsInterval

/** Device preferences and entry points for library management, Android permissions and recovery. */
@Composable
fun SettingsScreen(
    vm: SortieViewModel,
    onDiagnostics: () -> Unit,
    onManual: () -> Unit,
    onBackup: () -> Unit,
    onLibrary: () -> Unit,
    onImportCocoon: () -> Unit
) {
    val user by vm.user.collectAsState()
    val systems by vm.systems.collectAsState()
    val snap by vm.manifests.collectAsState()
    val context = LocalContext.current
    val dualScreen = remember { (context.applicationContext as SortieApp).graph.switcher.secondaryDisplay() != null }
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            item { FocusRow(onClick = onLibrary) { Line("Library and emulators", "add or edit systems, game folders, emulators and BIOS/save locations") } }
            if (systems.isNotEmpty()) {
                item {
                    FocusRow(onClick = {
                        if (!openSettingsScreen(context, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
                            Toast.makeText(context, "Open Android Settings → Apps → Default apps → Home to choose your launcher.", Toast.LENGTH_LONG).show()
                        }
                    }) { Line("Default home app", "optional: choose Sortie or switch back to your previous launcher") }
                }
            }
            item { SectionTitle("Display") }
            item {
                Toggle("Home layout: grid", user.homeLayout == "grid") { vm.setHomeLayout(if (it) "grid" else "list") }
            }
            item { Toggle("Browse layout: grid", user.browseLayout == "grid") { vm.setBrowseLayout(if (it) "grid" else "list") } }
            item {
                val chrome = BrowseChromeMode.of(user.browseChrome)
                val explain = when (chrome) {
                    BrowseChromeMode.AUTO -> "compact when the window is short or narrow, full when there is room"
                    BrowseChromeMode.EXPANDED -> "full header and hints always"
                    BrowseChromeMode.COMPACT -> "compact always"
                }
                FocusRow(onClick = { vm.setBrowseChrome(chrome.next()) }) {
                    Line(
                        "Header and hints (Home and browse)",
                        "${chrome.label} · A cycles Auto, Expanded, Compact · $explain · compact folds the header while browsing " +
                            "(tap or pull down its strip, or press Up on the first row, to show it) and shows lighter hints; " +
                            "Controls lists every command"
                    )
                }
            }
            item { Toggle("Grids scroll sideways (rows of tiles)", user.gridHorizontal) { vm.setGridHorizontal(it) } }
            item {
                FocusRow(onClick = { vm.setGridRows(if (user.gridRows >= 5) 2 else user.gridRows + 1) }) {
                    Line("Grid rows (sideways)", "${user.gridRows} · A cycles 2–5 · more rows, smaller tiles")
                }
            }
            item {
                FocusRow(onClick = { vm.setGridColumns(if (user.gridColumns >= 7) 4 else user.gridColumns + 1) }) {
                    Line("Grid columns (downward)", "${user.gridColumns} · A cycles 4–7")
                }
            }
            item {
                FocusRow(onClick = { vm.setAppColumns(if (user.appColumns >= 12) 6 else user.appColumns + 2) }) {
                    Line("App tiles per row (Apps screen)", "${user.appColumns} · A cycles 6–12")
                }
            }
            item { Toggle("Hero rotation on the idle companion screen", user.heroRotation) { vm.setHeroRotation(it) } }
            item { SectionTitle("Switcher") }
            item {
                Toggle("Overlay over running games (optional)", user.overlaySwitcher) { enabled ->
                    vm.setOverlaySwitcher(enabled)
                    if (enabled && !Settings.canDrawOverlays(context)) {
                        if (!openOverlayPermissionSettings(context)) {
                            Toast.makeText(context, "$OVERLAY_ACCESS_PATH. The regular switcher still works without it.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            item { SectionTitle("Device stats") }
            item {
                val mode = DeviceStatsMode.of(user.deviceStats)
                FocusRow(onClick = { vm.setDeviceStats(mode.next()) }) {
                    Line(
                        "Show device stats",
                        "${mode.label} · A cycles Off, Companion screen, Switcher overlay, Both · CPU, GPU, temperatures and fan need Shizuku"
                    )
                }
            }
            item {
                FocusRow(onClick = { vm.setDeviceStatsInterval(nextStatsInterval(user.deviceStatsInterval)) }) {
                    Line("Device stats refresh", "every ${statsInterval(user.deviceStatsInterval)} s · A cycles 2, 5, 10 s")
                }
            }
            item {
                FocusRow(onClick = { vm.setPerfInterval(nextPerfInterval(user.perfInterval)) }) {
                    Line("Perf view refresh", "every ${perfInterval(user.perfInterval)} s while a Perf view is shown · A cycles 1, 2 s")
                }
            }
            if (dualScreen) {
                item {
                    val view = CompanionView.of(user.companionView)
                    FocusRow(onClick = { vm.setCompanionView(view.next()) }) {
                        Line("Companion screen shows", "${view.label} · A switches between Companion and Perf (live device stats with history)")
                    }
                }
            }
            item { SectionTitle("Radial keyboard") }
            item {
                FocusRow(onClick = {
                    if (!openSettingsScreen(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
                        Toast.makeText(context, "Open Android Settings → System → Languages and input → On-screen keyboard.", Toast.LENGTH_LONG).show()
                    }
                }) {
                    Line("Enable radial keyboard…", "optional offline joystick typing; enable Sortie Radial Keyboard in Android settings")
                }
            }
            item {
                FocusRow(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
                    Line("Choose keyboard…", "select the radial keyboard or switch back to your usual keyboard")
                }
            }
            item { SectionTitle("Help") }
            item { FocusRow(onClick = onManual) { Line("Manual", "controls, home, lists, the companion screen, the switcher") } }
            item { FocusRow(onClick = onBackup) { Line("Backup and restore", "export preferences, notes and play history to a file; restore one") } }
            item {
                FocusRow(onClick = onImportCocoon) {
                    Line("Import from Cocoon…", "bring systems, favourites, folders and artwork over from the Cocoon launcher")
                }
            }
            item { FocusRow(onClick = onDiagnostics) { Line("Diagnostics", "index, manifests, Shizuku, launch log, wireless debugging") } }
            item { SectionTitle("Default launcher per system") }
            item {
                Text(
                    "The manifest's first installed profile is used unless chosen here. A per-game \"Launch with…\" choice still wins.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            items(systems, key = { it.def.id }) { row ->
                val chosen = user.systemProfiles[row.def.id]?.let { snap.profiles[it] }
                val default = row.def.profiles.firstOrNull()?.let { snap.profiles[it] }
                FocusRow(onClick = { picking = row.def.id }) {
                    Line(row.def.label, chosen?.label ?: default?.let { "${it.label} (manifest default)" } ?: "no profiles")
                }
            }
        }
        HintBar("A" to "change", "B" to "back")
    }
    picking?.let { systemId ->
        val options = vm.profilesForSystem(systemId)
        ContextSheet(
            systems.firstOrNull { it.def.id == systemId }?.def?.label ?: systemId,
            options.map { (p, installed) -> SheetAction(p.label + if (installed) "" else " (not installed)") { vm.setSystemProfile(systemId, p.id) } } +
                SheetAction("Manifest default") { vm.setSystemProfile(systemId, null) }
        ) { picking = null }
    }
}

/** A labelled switch row (also used by [CocoonImportScreen]'s preview). */
@Composable
internal fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FocusRow(onClick = { onChange(!on) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Switch(checked = on, onCheckedChange = onChange)
        }
    }
}

/** Title over a dimmer value line; the body of most settings rows (also used by [BackupScreen]). */
@Composable
internal fun Line(title: String, value: String) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
