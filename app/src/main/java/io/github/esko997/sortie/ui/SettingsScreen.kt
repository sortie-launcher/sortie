package io.github.esko997.sortie.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
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

/**
 * Device-side preferences only: per-system default launcher, layout and switcher behaviour. Everything that describes the card
 * (directories, systems, profiles, folders) stays desktop-authored in the manifests and is shown in Diagnostics instead.
 */
@Composable
fun SettingsScreen(vm: SortieViewModel, onDiagnostics: () -> Unit, onManual: () -> Unit, onBackup: () -> Unit) {
    val user by vm.user.collectAsState()
    val systems by vm.systems.collectAsState()
    val snap by vm.manifests.collectAsState()
    val context = LocalContext.current
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            item { SectionTitle("Display") }
            item {
                Toggle("Home layout: grid", user.homeLayout == "grid") { vm.setHomeLayout(if (it) "grid" else "list") }
            }
            item { Toggle("Browse layout: grid", user.browseLayout == "grid") { vm.setBrowseLayout(if (it) "grid" else "list") } }
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
            item { Toggle("Overlay over running games (needs the draw-over-apps grant)", user.overlaySwitcher) { vm.setOverlaySwitcher(it) } }
            item { SectionTitle("Radial keyboard") }
            item {
                FocusRow(onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }) {
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

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
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
