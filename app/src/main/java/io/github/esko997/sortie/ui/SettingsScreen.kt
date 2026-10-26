package io.github.esko997.sortie.ui

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.manifest.BrowseChromeMode
import io.github.esko997.sortie.stats.CompanionView
import io.github.esko997.sortie.stats.DeviceStatsMode
import io.github.esko997.sortie.stats.PERF_INTERVALS
import io.github.esko997.sortie.stats.STATS_INTERVALS
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
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            item { PageTitle("Settings") }
            settingsGroup(null) {
                row { FocusRow(onClick = onLibrary) { Line("Library and emulators", "Add or edit systems, game folders, emulators and BIOS/save locations") } }
                if (systems.isNotEmpty()) {
                    row {
                        FocusRow(onClick = {
                            if (!openSettingsScreen(context, Intent(Settings.ACTION_HOME_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
                                Toast.makeText(context, "Open Android Settings → Apps → Default apps → Home to choose your launcher.", Toast.LENGTH_LONG).show()
                            }
                        }) { Line("Default home app", "Optional: choose Sortie or switch back to your previous launcher") }
                    }
                }
            }
            settingsGroup("Display") {
                row {
                    val grid = user.homeLayout == "grid"
                    ChoiceRow("Home layout", LAYOUTS, if (grid) 1 else 0, onCycle = { vm.setHomeLayout(if (grid) "list" else "grid") }) {
                        vm.setHomeLayout(if (it == 1) "grid" else "list")
                    }
                }
                row {
                    val grid = user.browseLayout == "grid"
                    ChoiceRow(
                        "Browse layout",
                        LAYOUTS,
                        if (grid) 1 else 0,
                        detail = "System lists, folders, search and Apps",
                        onCycle = { vm.setBrowseLayout(if (grid) "list" else "grid") }
                    ) { vm.setBrowseLayout(if (it == 1) "grid" else "list") }
                }
                row {
                    val chrome = BrowseChromeMode.of(user.browseChrome)
                    val explain = when (chrome) {
                        BrowseChromeMode.AUTO -> "Compact when the window is short or narrow, full when there is room."
                        BrowseChromeMode.EXPANDED -> "Full header and hints always."
                        BrowseChromeMode.COMPACT -> "Compact always."
                    }
                    ChoiceRow(
                        "Header and hints (Home and browse)",
                        BrowseChromeMode.entries.map { it.label },
                        chrome.ordinal,
                        detail = "$explain Compact folds the header while browsing (tap or pull down its strip, or press Up on the first " +
                            "row, to show it) and shows lighter hints; Controls lists every command.",
                        onCycle = { vm.setBrowseChrome(chrome.next()) }
                    ) { vm.setBrowseChrome(BrowseChromeMode.entries[it]) }
                }
                row { Toggle("Grids scroll sideways (rows of tiles)", user.gridHorizontal) { vm.setGridHorizontal(it) } }
                row {
                    NumberChoice("Grid rows (sideways)", (2..5).toList(), user.gridRows, detail = "More rows, smaller tiles", onSet = vm::setGridRows) {
                        vm.setGridRows(if (user.gridRows >= 5) 2 else user.gridRows + 1)
                    }
                }
                row {
                    NumberChoice("Grid columns (downward)", (4..7).toList(), user.gridColumns, onSet = vm::setGridColumns) {
                        vm.setGridColumns(if (user.gridColumns >= 7) 4 else user.gridColumns + 1)
                    }
                }
                row {
                    NumberChoice("App tiles per row (Apps screen)", listOf(6, 8, 10, 12), user.appColumns, onSet = vm::setAppColumns) {
                        vm.setAppColumns(if (user.appColumns >= 12) 6 else user.appColumns + 2)
                    }
                }
                row { Toggle("Hero rotation on the idle companion screen", user.heroRotation) { vm.setHeroRotation(it) } }
            }
            settingsGroup("Switcher") {
                row {
                    Toggle(
                        "Overlay over running games (optional)",
                        user.overlaySwitcher,
                        detail = "Draws the switcher over the running games; off, it opens as a Sortie screen. Needs Display over other apps"
                    ) { enabled ->
                        vm.setOverlaySwitcher(enabled)
                        if (enabled && !Settings.canDrawOverlays(context)) {
                            if (!openOverlayPermissionSettings(context)) {
                                Toast.makeText(context, "$OVERLAY_ACCESS_PATH. The regular switcher still works without it.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
            settingsGroup("Device stats") {
                row {
                    val mode = DeviceStatsMode.of(user.deviceStats)
                    ChoiceRow(
                        "Show device stats",
                        DeviceStatsMode.entries.map { it.label },
                        mode.ordinal,
                        detail = "CPU, GPU, temperatures and fan need Shizuku",
                        onCycle = { vm.setDeviceStats(mode.next()) }
                    ) { vm.setDeviceStats(DeviceStatsMode.entries[it]) }
                }
                row {
                    val seconds = statsInterval(user.deviceStatsInterval)
                    ChoiceRow(
                        "Device stats refresh",
                        STATS_INTERVALS.map { "$it s" },
                        STATS_INTERVALS.indexOf(seconds),
                        detail = "Only while the stats are on a screen that is on",
                        onCycle = { vm.setDeviceStatsInterval(nextStatsInterval(user.deviceStatsInterval)) }
                    ) { vm.setDeviceStatsInterval(STATS_INTERVALS[it]) }
                }
                row {
                    val seconds = perfInterval(user.perfInterval)
                    ChoiceRow(
                        "Perf view refresh",
                        PERF_INTERVALS.map { "$it s" },
                        PERF_INTERVALS.indexOf(seconds),
                        detail = "While a Perf view is shown",
                        onCycle = { vm.setPerfInterval(nextPerfInterval(user.perfInterval)) }
                    ) { vm.setPerfInterval(PERF_INTERVALS[it]) }
                }
                if (dualScreen) {
                    row {
                        val view = CompanionView.of(user.companionView)
                        ChoiceRow(
                            "Companion screen shows",
                            CompanionView.entries.map { it.label },
                            view.ordinal,
                            detail = "Perf: live device stats with history",
                            onCycle = { vm.setCompanionView(view.next()) }
                        ) { vm.setCompanionView(CompanionView.entries[it]) }
                    }
                }
            }
            settingsGroup("Radial keyboard") {
                row {
                    FocusRow(onClick = {
                        if (!openSettingsScreen(context, Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), Intent(Settings.ACTION_SETTINGS))) {
                            Toast.makeText(context, "Open Android Settings → System → Languages and input → On-screen keyboard.", Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Line("Enable radial keyboard…", "Optional offline joystick typing; enable Sortie Radial Keyboard in Android settings")
                    }
                }
                row {
                    FocusRow(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
                        Line("Choose keyboard…", "Select the radial keyboard or switch back to your usual keyboard")
                    }
                }
            }
            settingsGroup("Help") {
                row { FocusRow(onClick = onManual) { Line("Manual", "Controls, home, lists, the companion screen, the switcher") } }
                row { FocusRow(onClick = onBackup) { Line("Backup and restore", "Export preferences, notes and play history to a file; restore one") } }
                row {
                    FocusRow(onClick = onImportCocoon) {
                        Line("Import from Cocoon…", "Bring systems, favourites, folders and artwork over from the Cocoon launcher")
                    }
                }
                row { FocusRow(onClick = onDiagnostics) { Line("Diagnostics", "Index, manifests, Shizuku, launch log, wireless debugging") } }
            }
            item { SectionTitle("Default launcher per system") }
            item { Note("The manifest's first installed profile is used unless chosen here. A per-game \"Launch with…\" choice still wins.") }
            item { Spacer(Modifier.height(4.dp)) }
            settingsGroup(null) {
                systems.forEach { s ->
                    row(key = "launcher:${s.def.id}") {
                        val chosen = user.systemProfiles[s.def.id]?.let { snap.profiles[it] }
                        val default = s.def.profiles.firstOrNull()?.let { snap.profiles[it] }
                        FocusRow(onClick = { picking = s.def.id }) {
                            Line(s.def.label, chosen?.label ?: default?.let { "${it.label} (manifest default)" } ?: "No profiles")
                        }
                    }
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

private val LAYOUTS = listOf("List", "Grid")

/** [ChoiceRow] over numbers; a value outside [values] (hand-edited) is shown in its place among them rather than hidden. */
@Composable
private fun NumberChoice(title: String, values: List<Int>, current: Int, detail: String? = null, onSet: (Int) -> Unit, onCycle: () -> Unit) {
    val shown = if (current in values) values else (values + current).sorted()
    ChoiceRow(title, shown.map { it.toString() }, shown.indexOf(current), detail, onCycle) { onSet(shown[it]) }
}

/** A page's heading, the same on every settings and utility screen. */
@Composable
internal fun PageTitle(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
}

/**
 * A labelled switch row (also used by [CocoonImportScreen]'s preview); [detail] is a dimmer line under the label. The row is the
 * controller's stop: A flips it.
 */
@Composable
internal fun Toggle(label: String, on: Boolean, detail: String? = null, onChange: (Boolean) -> Unit) {
    FocusRow(onClick = { onChange(!on) }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.width(12.dp))
            // the row is the controller's stop; the switch stays tappable but is not a second, unmarked one
            Switch(checked = on, onCheckedChange = onChange, modifier = Modifier.focusProperties { canFocus = false })
        }
    }
}

/** Title over a dimmer value line; the body of most settings rows (also used by [BackupScreen]). [accent] colours the title. */
@Composable
internal fun Line(title: String, value: String, accent: Color = Color.Unspecified) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = accent)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A setting with a few values, every one shown and the current one marked. A on the row moves to the next ([onCycle], the
 * controller's way); tapping a value picks that one ([onPick]). The values are touch targets only, so the row stays one
 * controller stop.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceRow(title: String, options: List<String>, selected: Int, detail: String? = null, onCycle: () -> Unit, onPick: (Int) -> Unit) {
    val current = options.getOrNull(selected)
    FocusRow(onClick = onCycle, modifier = Modifier.semantics { if (current != null) stateDescription = current }) {
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            FlowRow(
                Modifier.padding(top = 6.dp, bottom = if (detail == null) 0.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEachIndexed { i, label -> ChoiceValue(label, i == selected) { onPick(i) } }
            }
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private val ValueShape = RoundedCornerShape(50)

/** One value of a [ChoiceRow]: the chosen one in amber; the mark moves with a short fade when the choice changes. */
@Composable
private fun ChoiceValue(label: String, on: Boolean, onPick: () -> Unit) {
    val fill by animateColorAsState(if (on) Amber.copy(alpha = 0.16f) else Color.Transparent, tween(160), label = "valueFill")
    val edge by animateColorAsState(if (on) Amber.copy(alpha = 0.7f) else Hairline, tween(160), label = "valueEdge")
    val ink by animateColorAsState(if (on) Amber else MaterialTheme.colorScheme.onSurfaceVariant, tween(160), label = "valueInk")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = ink,
        maxLines = 1,
        modifier = Modifier
            .focusProperties { canFocus = false }
            .clip(ValueShape)
            .background(fill)
            .border(1.dp, edge, ValueShape)
            .selectable(selected = on, interactionSource = null, indication = PressVeil, role = Role.RadioButton, onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** A dim passage of explanation; [error] colours it as a problem. */
@Composable
internal fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** A focusable passage of text (A does nothing), so the D-pad can reach it and scroll it into view. */
@Composable
internal fun InfoBlock(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FocusRow(onClick = {}, modifier = modifier) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp))
            content()
        }
    }
}

/**
 * How a piece of work ended, held until A: an amber (done) or red (failed) edge beside the [title] and its [lines]. Backup,
 * Cocoon import and library setup all end on one.
 */
@Composable
internal fun OutcomeRow(title: String, ok: Boolean, lines: List<String>, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val tone = if (ok) Amber else MaterialTheme.colorScheme.error
    FocusRow(onClick = onDismiss, modifier = modifier) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(tone))
            Column(Modifier.padding(start = 6.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tone,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                lines.forEach { Note(it, error = !ok) }
                Text(
                    "A: OK",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

/**
 * Work in progress: [step] in amber over a bar, determinate when [fraction] is known (it glides to each new value), else
 * indeterminate. The bar runs only while the work does; [detail] says more under it.
 */
@Composable
internal fun WorkProgress(step: String, fraction: Float? = null, detail: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(step, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Amber, modifier = Modifier.padding(horizontal = 8.dp))
        val bar = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp).clip(ValueShape)
        if (fraction != null) {
            val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "workProgress")
            LinearProgressIndicator(progress = { shown }, modifier = bar, color = Amber, trackColor = Hairline)
        } else {
            LinearProgressIndicator(bar, color = Amber, trackColor = Hairline)
        }
        detail?.let { Note(it) }
    }
}

/** The rows of one settings group, collected before they are laid out so the first and last can round the group's corners. */
internal class GroupRows {
    val rows = ArrayList<Pair<Any?, @Composable () -> Unit>>()

    /** [key] keeps a row's state with it when rows come and go (e.g. one per system). */
    fun row(key: Any? = null, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/**
 * Settings rows grouped on one slate panel, split by hairlines, under an optional [SectionTitle]: the grouping reads at a
 * glance and the focused row lifts off the panel. Nothing is drawn for a group without rows.
 */
internal fun LazyListScope.settingsGroup(title: String?, build: GroupRows.() -> Unit) {
    val rows = GroupRows().apply(build).rows
    if (rows.isEmpty()) return
    if (title != null) item { SectionTitle(title) }
    rows.forEachIndexed { i, (key, content) ->
        item(key = key) { GroupCell(first = i == 0, last = i == rows.lastIndex, content = content) }
    }
}

/** [settingsGroup] for a page that is a plain scrolling column rather than a lazy list. */
@Composable
internal fun SettingsPanel(build: GroupRows.() -> Unit) {
    val rows = GroupRows().apply(build).rows
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, (k, content) ->
            key(k ?: i) { GroupCell(first = i == 0, last = i == rows.lastIndex, content = content) }
        }
    }
}

private val GroupRadius = 14.dp

/**
 * One row's slice of a group's panel. The slice holding the focus draws above its neighbours, so the focus glow is never cut
 * off by the next slice's panel.
 */
@Composable
private fun GroupCell(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    var lifted by remember { mutableStateOf(false) }
    val top = if (first) GroupRadius else 0.dp
    val bottom = if (last) GroupRadius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomEnd = bottom, bottomStart = bottom)
    Box(
        Modifier
            .zIndex(if (lifted) 1f else 0f)
            .onFocusChanged { lifted = it.hasFocus }
            .fillMaxWidth()
            .background(Slate, shape)
            .then(
                if (first) {
                    Modifier
                } else {
                    Modifier.drawBehind {
                        val inset = 14.dp.toPx()
                        drawLine(Hairline, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = 1.dp.toPx())
                    }
                }
            )
            .padding(start = 4.dp, end = 4.dp, top = if (first) 4.dp else 0.dp, bottom = if (last) 4.dp else 0.dp)
    ) { content() }
}
