package io.github.esko997.sortie.ui

import android.os.StatFs
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.backup.BackupManager
import io.github.esko997.sortie.data.cocoon.ArtworkCopy
import io.github.esko997.sortie.data.cocoon.CocoonImportModel
import io.github.esko997.sortie.data.cocoon.CocoonImportState
import io.github.esko997.sortie.data.cocoon.CocoonImporter
import io.github.esko997.sortie.data.cocoon.CocoonLocator
import io.github.esko997.sortie.data.cocoon.CocoonPaths
import io.github.esko997.sortie.data.cocoon.DeltaCount
import io.github.esko997.sortie.data.cocoon.FolderDelta
import io.github.esko997.sortie.data.cocoon.ImportOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Longest list the preview prints in full; the rest is summarised as "+N more". */
private const val LIST_CAP = 20

/** Free space on one storage volume against the artwork bytes planned for it. [available] < 0 = Android didn't say. */
private class VolumeSpace(val volume: String, val needed: Long, val available: Long) {
    val fits: Boolean get() = available < 0 || needed <= available
}

/**
 * Settings → Import from Cocoon…: locate Cocoon's folder (and optionally a Cocoon backup, from that folder or typed/picked from
 * anywhere else), preview everything the import would add, then apply it on confirmation. The work lives in
 * [CocoonImportModel], so leaving the screen or swapping displays never interrupts it; this screen only shows its state.
 * Cocoon's own files are only ever read.
 */
@Composable
fun CocoonImportScreen(graph: SortieApp.Graph) {
    val context = LocalContext.current
    val model = remember(graph) { CocoonImportModel.of(graph) }
    val state by model.state.collectAsState()
    val user by graph.user.data.collectAsState()
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::chooseTree) }
    val pickBackupTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(model::chooseBackupTree) }
    val noBrowser: (Throwable) -> Unit = {
        Toast.makeText(context, "Android has no file browser to pick folders with. Type the folder's path instead.", Toast.LENGTH_LONG).show()
    }
    var typed by rememberSaveable { mutableStateOf("") }
    var typedBackup by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state) { if (state === CocoonImportState.Idle) model.locate() }

    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val working = state as? CocoonImportState.Working
    val copying = working?.step == CocoonImporter.ARTWORK_STEP
    LaunchedEffect(state::class, copying, (state as? CocoonImportState.Located)?.backupProblem) {
        if (working != null && !copying) return@LaunchedEffect
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    val located = state as? CocoonImportState.Located
    val gamelists by produceState(emptyMap<File, Int>(), located?.candidates) {
        value = withContext(Dispatchers.IO) { located?.candidates.orEmpty().associateWith(::gamelistCount) }
    }
    val previewPlan = (state as? CocoonImportState.Preview)?.plan
    val space by produceState<List<VolumeSpace>?>(null, previewPlan) {
        value = previewPlan?.let { withContext(Dispatchers.IO) { freeSpace(it.artwork) } }
    }
    val bytes: (Long) -> String = { Formatter.formatShortFileSize(context, it) }

    // Plain text in a LazyColumn is unreachable on the D-pad, so every passage sits in a focusable block that scrolls into view.
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            item { PageTitle("Import from Cocoon") }
            when (val s = state) {
                CocoonImportState.Idle -> item { WorkProgress("Looking for Cocoon…") }
                is CocoonImportState.Working -> working(s, primary, bytes, onStop = model::cancel)
                is CocoonImportState.Located -> located(
                    s,
                    remembered = user.cocoonDir,
                    gamelists = gamelists,
                    typed = typed,
                    onTyped = { typed = it },
                    typedBackup = typedBackup,
                    onTypedBackup = { typedBackup = it },
                    primary = primary,
                    onChoose = model::choose,
                    onUseTyped = { if (typed.isNotBlank()) model.choose(resolveFolder(graph, typed.trim())) },
                    onPick = { runCatching { pickTree.launch(null) }.onFailure(noBrowser) },
                    onUseTypedBackup = { if (typedBackup.isNotBlank()) model.chooseBackup(resolveFolder(graph, typedBackup.trim())) },
                    onPickBackup = { runCatching { pickBackupTree.launch(null) }.onFailure(noBrowser) },
                    onPreview = model::preview
                )
                is CocoonImportState.Preview ->
                    preview(s, space, primary, bytes, onOptions = model::setOptions, onConfirm = model::confirm, onCancel = model::dismiss)
                is CocoonImportState.Result -> item {
                    OutcomeRow(s.title, s.ok, s.lines, onDismiss = model::dismiss, modifier = Modifier.focusRequester(primary))
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item { InfoBlock("Nothing in Cocoon changes") { Note(READ_ONLY) } }
        }
        HintBar("A" to "choose", "B" to "back")
    }
}

private const val READ_ONLY = "The import only reads Cocoon's folder: nothing in it is created, renamed, changed or deleted, artwork is copied " +
    "(never moved), and Cocoon itself is not started, stopped or reconfigured. Everything is written to Sortie's own folder, after a " +
    "safety copy of your current preferences and play history."

private fun LazyListScope.working(s: CocoonImportState.Working, primary: FocusRequester, bytes: (Long) -> String, onStop: () -> Unit) {
    item {
        WorkProgress(
            s.step,
            fraction = if (s.total > 0) s.done.toFloat() / s.total else null,
            detail = (if (s.total > 0) "${bytes(s.done)} of ${bytes(s.total)}. " else "") + "You can leave this screen: the import carries on."
        )
    }
    if (s.step == CocoonImporter.ARTWORK_STEP) {
        item { Spacer(Modifier.height(8.dp)) }
        settingsGroup(null) {
            row {
                FocusRow(onClick = onStop, modifier = Modifier.focusRequester(primary)) {
                    Line("Stop copying artwork", "Keeps what is copied so far; run the import again later to copy the rest")
                }
            }
        }
    }
}

private fun LazyListScope.located(
    s: CocoonImportState.Located,
    remembered: String?,
    gamelists: Map<File, Int>,
    typed: String,
    onTyped: (String) -> Unit,
    typedBackup: String,
    onTypedBackup: (String) -> Unit,
    primary: FocusRequester,
    onChoose: (File) -> Unit,
    onUseTyped: () -> Unit,
    onPick: () -> Unit,
    onUseTypedBackup: () -> Unit,
    onPickBackup: () -> Unit,
    onPreview: (File?) -> Unit
) {
    val chosen = s.chosen
    item {
        Note(
            if (s.cocoonInstalled) {
                "Cocoon is installed. The import reads the files it keeps in its folder and doesn't need Cocoon to run."
            } else {
                "Cocoon isn't installed. Its folder can still be imported: the import reads the files Cocoon left there."
            }
        )
    }
    if (chosen != null) {
        item { SectionTitle("Backup (optional)") }
        item {
            Note(
                "A Cocoon backup adds your own folders, real play sessions and per-game emulator choices. Make one in Cocoon → Settings; " +
                    "folders and sessions made after the backup's date are not in it. The backup can be anywhere on this device: the ones " +
                    "in ${chosen.path}/backups are listed, and below you can type or pick another."
            )
        }
        // a refused location is focused first, so the reason is on screen before anything else is chosen
        val problem = s.backupProblem
        if (problem != null) {
            item {
                InfoBlock("That backup can't be used", Modifier.focusRequester(primary)) {
                    Note(problem, error = true)
                    Note("Nothing was changed.")
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        settingsGroup(null) {
            s.backups.forEachIndexed { i, b ->
                row(key = "backup:${b.dir.path}") {
                    FocusRow(onClick = { onPreview(b.dir) }, modifier = if (problem == null && i == 0) Modifier.focusRequester(primary) else Modifier) {
                        Line("Use backup ${b.dir.name}", "Made ${BackupManager.formatTime(b.createdAt)} · ${b.dir.path}")
                    }
                }
            }
            row {
                val focused = problem == null && s.backups.isEmpty()
                FocusRow(onClick = { onPreview(null) }, modifier = if (focused) Modifier.focusRequester(primary) else Modifier) {
                    Line(
                        "Continue without a backup",
                        if (s.backups.isEmpty()) "No backup found in ${chosen.path}/backups" else "Systems, favourites, emulators and artwork only"
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
        item { Note("Another backup… (its cocoon_backup_… folder, or a folder holding backups; absolute, or relative to the card root)") }
        item { PathField(typedBackup, onTypedBackup, "e.g. /storage/emulated/0/Download/cocoon_backup_…") }
        item { Spacer(Modifier.height(6.dp)) }
        settingsGroup(null) {
            row {
                FocusRow(onClick = onUseTypedBackup) {
                    Line("Use the backup typed above", if (typedBackup.isBlank()) "Type a path first" else typedBackup.trim())
                }
            }
            row {
                FocusRow(onClick = onPickBackup) {
                    Line("Pick a backup with file browser…", "A cocoon_backup_… folder or a folder holding backups; Sortie keeps no access grant")
                }
            }
        }
    }
    item { SectionTitle("Cocoon folder") }
    if (s.candidates.isEmpty()) {
        item { Note("No Cocoon folder was found on this device's storage. Type its path or pick it with the file browser.", error = true) }
    }
    // the model lists the remembered folder under its stored path, so plain path equality identifies it without disk access
    settingsGroup(null) {
        s.candidates.forEach { dir ->
            row(key = "cocoon:${dir.path}") {
                val tags = listOfNotNull(
                    "in use".takeIf { dir == chosen },
                    "used last time".takeIf { remembered != null && File(remembered) == dir },
                    gamelists[dir]?.let { plural(it, "gamelist") }
                )
                val summary = tags.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "Detected" }
                FocusRow(onClick = { onChoose(dir) }) {
                    Line(dir.path, summary, accent = if (dir == chosen) Amber else Color.Unspecified)
                }
            }
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
    item { Note("Enter a folder… (absolute, or relative to the card root)") }
    item { PathField(typed, onTyped, "e.g. /storage/emulated/0/Cocoon") }
    item { Spacer(Modifier.height(6.dp)) }
    settingsGroup(null) {
        row {
            FocusRow(onClick = onUseTyped, modifier = if (chosen == null) Modifier.focusRequester(primary) else Modifier) {
                Line("Use the folder typed above", if (typed.isBlank()) "Type a path first" else typed.trim())
            }
        }
        row { FocusRow(onClick = onPick) { Line("Pick with file browser…", "Choose Cocoon's folder; Sortie reads it directly and keeps no access grant") } }
    }
}

private fun LazyListScope.preview(
    p: CocoonImportState.Preview,
    space: List<VolumeSpace>?,
    primary: FocusRequester,
    bytes: (Long) -> String,
    onOptions: (ImportOptions) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val plan = p.plan
    val o = p.options
    val d = p.delta
    val backup = plan.backup
    val tooBig = o.artwork && space.orEmpty().any { !it.fits }
    item {
        // focused first, so confirming takes a deliberate D-pad down past the toggles and Cancel
        InfoBlock("Import from ${plan.cocoonDir.path}?", Modifier.focusRequester(primary)) {
            if (p.changed) {
                Note(
                    "Your library settings changed after the previous preview, so nothing was imported. This preview was made again " +
                        "from the current settings: review it, then choose Import.",
                    error = true
                )
            }
            Note(
                if (backup == null) {
                    "No backup used: folders, play sessions and per-game emulators are not imported."
                } else {
                    val by = if (backup.appVersion.isBlank()) "" else " by Cocoon ${backup.appVersion}"
                    "Backup ${backup.dir.path}, made ${BackupManager.formatTime(backup.createdAt)}$by."
                }
            )
            Note(
                "Matched ${plural(plan.matched, "game")} to files on this device; ${plan.unmatched.size} not found, ${plan.ambiguous.size} ambiguous " +
                    "(their favourite/hidden flags are dropped, not guessed)."
            )
            Note(
                "Your current preferences and play history are saved as a safety copy first. Restoring it brings back the preferences " +
                    "(favourites, hidden games, emulator choices); added systems, folders, play sessions and copied artwork stay. " +
                    "Choose below what to bring over."
            )
        }
    }
    settingsGroup("Bring over") {
        if (plan.newSystems.isNotEmpty()) row { Toggle("Add ${plural(plan.newSystems.size, "system")}", o.systems) { onOptions(o.copy(systems = it)) } }
        // the legacy cleanup stays selectable when every favourite is already here
        if (d.favourites.add > 0 || plan.legacyFavourites > 0) {
            row { Toggle(favouritesToggle(d.favourites, plan.legacyFavourites), o.favourites) { onOptions(o.copy(favourites = it)) } }
        }
        if (plan.legacyFavourites > 0) row { InfoBlock("Old Favourites folder entry") { Note(legacyFavouritesNote(plan.legacyFavourites, o.favourites)) } }
        if (d.hidden.add > 0) {
            row { Toggle("Hide ${plural(d.hidden.add, "game")}${alreadyHere(d.hidden.present)}", o.hidden) { onOptions(o.copy(hidden = it)) } }
        }
        if (plan.artwork.isNotEmpty()) {
            row { Toggle("Copy ${plural(plan.artwork.size, "artwork file")} (${bytes(plan.artworkBytes)})", o.artwork) { onOptions(o.copy(artwork = it)) } }
        }
        if (backup != null) {
            if (d.folders.create + d.folders.update > 0) row { Toggle(foldersToggle(d.folders), o.folders) { onOptions(o.copy(folders = it)) } }
            if (d.sessions.add > 0) {
                val label = "Add ${plural(d.sessions.add, "play session")} (${d.sessions.addedMinutes} min)${alreadyHere(d.sessions.present)}"
                row { Toggle(label, o.sessions) { onOptions(o.copy(sessions = it)) } }
            }
            if (d.overrides.add > 0) {
                val kept = if (d.overrides.present > 0) " · ${plural(d.overrides.present, "game")} keep your choice" else ""
                row { Toggle("Set ${plural(d.overrides.add, "per-game emulator")}$kept", o.overrides) { onOptions(o.copy(overrides = it)) } }
            }
        }
    }
    if (tooBig) {
        item { Note("The artwork doesn't fit in the free space (see below). Turn off the artwork copy or free some space first.", error = true) }
    }
    item { Spacer(Modifier.height(12.dp)) }
    settingsGroup(null) {
        row { FocusRow(onClick = onCancel) { Line("Cancel", "Change nothing") } }
        if (!tooBig) {
            row {
                FocusRow(onClick = onConfirm) {
                    Line("Import", "Writes Sortie's library files, user.json, the play database and media folders", accent = Amber)
                }
            }
        }
    }
    item { Spacer(Modifier.height(8.dp)) }
    val present = alreadyPresent(p)
    if (present.isNotEmpty()) {
        item { InfoBlock("Already in Sortie") { Note("Nothing new to add for: ${present.joinToString()}. The import leaves them as they are.") } }
    }

    if (plan.artwork.isNotEmpty() || plan.missingVolume > 0) {
        item {
            InfoBlock("Artwork") {
                if (plan.artwork.isNotEmpty()) {
                    Note("Covers, logos and gameplay screenshots Cocoon downloaded, for games Sortie has no art for yet. Existing files are never replaced.")
                    when {
                        space == null -> Note("Checking free space…")
                        else -> space.forEach { v ->
                            val free = if (v.available < 0) "free space unknown" else "${bytes(v.available)} free"
                            Note("${v.volume}: ${bytes(v.needed)} to copy, $free", error = !v.fits)
                        }
                    }
                } else {
                    Note("No Cocoon artwork to copy: Sortie already has art for the matched games, or Cocoon's references don't resolve here.")
                }
                if (plan.missingVolume > 0) {
                    // a Cocoon state restored from another device keeps that device's file names and volume ids
                    Note(
                        "${plural(plan.missingVolume, "artwork reference")} point at files that aren't here (another device's storage, or media " +
                            "Cocoon re-downloaded under new names); they are skipped."
                    )
                }
            }
        }
    }
    if (plan.newSystems.isNotEmpty()) {
        item {
            InfoBlock("Systems to add") {
                plan.newSystems.take(LIST_CAP).forEach { ns ->
                    val sys = ns.system
                    // the first profile in the new system's list is its default; Cocoon's choice (or its installed equivalent) is put there
                    val choice = plan.systemProfiles.firstOrNull { it.systemId == sys.id }
                    val label = sys.profiles.firstOrNull()?.let { id -> ns.profiles.firstOrNull { it.id == id }?.label ?: id }
                    val emulator = when {
                        label == null -> "no emulator (Open with… only)"
                        choice == null -> label
                        !choice.installed -> "$label — not installed yet"
                        choice.requestedProfileId != null -> "$label — installed, instead of Cocoon's ${choice.requestedProfileId}"
                        else -> "$label — installed"
                    }
                    Note("${sys.id} · ${sys.label}${if (ns.fromPreset) "" else " (custom)"}: ${sys.roots.joinToString()} · $emulator")
                }
                more(plan.newSystems.size)?.let { Note(it) }
            }
        }
    }
    val newIds = plan.newSystems.mapTo(HashSet()) { it.system.id }
    val media = plan.mediaDirs.filterKeys { it !in newIds }
    if (media.isNotEmpty()) {
        item {
            InfoBlock("Existing systems that get a media folder") {
                Note("Needed for the copied artwork; only systems without one get it.")
                capped(media.map { (id, path) -> "$id → $path" }).forEach { Note(it) }
            }
        }
    }
    if (plan.unmatched.isNotEmpty()) item { InfoBlock("Not found on this device (${plan.unmatched.size})") { capped(plan.unmatched).forEach { Note(it) } } }
    if (plan.ambiguous.isNotEmpty()) item { InfoBlock("Ambiguous, skipped (${plan.ambiguous.size})") { capped(plan.ambiguous).forEach { Note(it) } } }
    if (plan.skippedPlatforms.isNotEmpty()) {
        item { InfoBlock("Platforms skipped (${plan.skippedPlatforms.size})") { capped(plan.skippedPlatforms).forEach { Note(it) } } }
    }
    if (plan.playStats > 0) {
        item {
            InfoBlock("Play stats") {
                Note(
                    "${plural(plan.playStats, "game")} ${if (plan.playStats == 1) "has" else "have"} Cocoon play stats; shown only, not imported" +
                        if (backup == null) " — pick a backup to import real sessions." else ". The backup's real sessions are imported instead."
                )
            }
        }
    }
    if (plan.droidDeck.isNotEmpty()) {
        item {
            InfoBlock("DroidDeck games (${plan.droidDeck.size})") {
                Note("Cocoon's DroidDeck pins belong to Cocoon and can't move. Once Sortie is the home app, pin these again from DroidDeck:")
                capped(plan.droidDeck).forEach { Note(it) }
            }
        }
    }
    if (plan.notes.isNotEmpty()) item { InfoBlock("Notes") { capped(plan.notes).forEach { Note(it) } } }
}

/** The Favourites switch: adding the new favourites, plus the old default Favourites entry's removal when there is one. */
private fun favouritesToggle(favourites: DeltaCount, legacy: Int): String {
    val cleanup = "remove the old Favourites folder ${if (legacy == 1) "entry" else "entries"}"
    val add = "Add ${plural(favourites.add, "favourite")}"
    return when {
        legacy == 0 -> add + alreadyHere(favourites.present)
        favourites.add == 0 -> "${cleanup.replaceFirstChar { it.uppercase() }} (no new favourites)"
        else -> "$add and $cleanup${alreadyHere(favourites.present)}"
    }
}

private fun alreadyHere(n: Int): String = if (n > 0) " · $n already here" else ""

private fun foldersToggle(f: FolderDelta): String {
    val what = listOfNotNull(
        f.create.takeIf { it > 0 }?.let { "create ${plural(it, "folder")}" },
        f.update.takeIf { it > 0 }?.let { "add to ${plural(it, "existing folder")}" }
    ).joinToString(" and ").replaceFirstChar { it.uppercase() }
    return "$what (${plural(f.members, "item")})${alreadyHere(f.unchanged)}"
}

/** Categories the plan has items for but every one is already in Sortie, so no switch is shown for them. */
private fun alreadyPresent(p: CocoonImportState.Preview): List<String> {
    val d = p.delta
    return listOfNotNull(
        plural(d.favourites.present, "favourite").takeIf { d.favourites.add == 0 && d.favourites.present > 0 },
        plural(d.hidden.present, "hidden game").takeIf { d.hidden.add == 0 && d.hidden.present > 0 },
        plural(d.folders.unchanged, "folder").takeIf { d.folders.create + d.folders.update == 0 && d.folders.unchanged > 0 },
        plural(d.sessions.present, "play session").takeIf { d.sessions.add == 0 && d.sessions.present > 0 },
        "per-game emulators for ${plural(d.overrides.present, "game")} (your choices stay)".takeIf { d.overrides.add == 0 && d.overrides.present > 0 }
    )
}

private fun legacyFavouritesNote(legacy: Int, on: Boolean): String {
    val copies = if (legacy == 1) "a copy" else "$legacy copies"
    return "folders.json has $copies of Sortie's old default Favourites folder (every favourite, apps included, by title). The built-in " +
        "Favourites folder replaced it and already lists the same favourites, so Sortie ignores the entry and reports it as a folder " +
        "rule problem. " + if (on) {
            "With Favourites on, the import removes only that entry from folders.json, after the safety copy. Your favourites, other " +
                "folders and customised entries stay; folders.json isn't in the safety copy, but the entry only repeated the built-in folder."
        } else {
            "Favourites is off, so the entry stays in folders.json and keeps being reported."
        }
}

/**
 * One-line path field a controller can pass through: up/down from a physical pad move the focus instead of the caret, A opens
 * the keyboard, Done closes it (same behaviour as Library setup's fields).
 */
@Composable
private fun PathField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).onPreviewKeyEvent { e ->
            when {
                !isPhysicalPad(e) -> false
                e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                    if (e.type == KeyEventType.KeyDown) {
                        keyboard?.hide()
                        focus.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                    }
                    true
                }
                e.key == Key.ButtonA || e.key == Key.DirectionCenter -> {
                    if (e.type == KeyEventType.KeyUp) keyboard?.show()
                    true
                }
                else -> false
            }
        }
    )
}

/** Gamelists Cocoon exported under [dir], for telling candidate folders apart. */
private fun gamelistCount(dir: File): Int = File(dir, "gamelists").listFiles()?.count { File(it, "gamelist.xml").isFile } ?: 0

/**
 * Groups the planned copies by storage volume (the target's mounted volume, else its nearest existing folder) and asks Android
 * how much room each has. Grouped by kind folder first, so thousands of covers cost a handful of path lookups.
 */
private fun freeSpace(artwork: List<ArtworkCopy>): List<VolumeSpace> {
    if (artwork.isEmpty()) return emptyList()
    val volumes = CocoonLocator.volumes().map { CocoonPaths.canonical(it).path }
    val needed = LinkedHashMap<String, Long>()
    artwork.groupBy { it.target.parentFile ?: it.target }.forEach { (dir, copies) ->
        val path = CocoonPaths.canonical(dir).path
        val volume = volumes.firstOrNull { path == it || path.startsWith("$it/") } ?: existingAncestor(dir).path
        needed[volume] = (needed[volume] ?: 0L) + copies.sumOf { it.bytes }
    }
    return needed.map { (volume, n) -> VolumeSpace(volume, n, runCatching { StatFs(existingAncestor(File(volume)).path).availableBytes }.getOrDefault(-1L)) }
}

private fun existingAncestor(f: File): File {
    var d: File? = f
    while (d != null && !d.exists()) d = d.parentFile
    return d ?: File("/")
}

private fun capped(items: List<String>): List<String> = items.take(LIST_CAP) + listOfNotNull(more(items.size))

private fun more(size: Int): String? = if (size > LIST_CAP) "+${size - LIST_CAP} more" else null
