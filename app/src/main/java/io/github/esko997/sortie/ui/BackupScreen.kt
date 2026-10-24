package io.github.esko997.sortie.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.backup.BackupManager
import io.github.esko997.sortie.data.backup.BackupState
import io.github.esko997.sortie.data.manifest.UserData
import kotlinx.coroutines.delay

/** Some file managers label .json as octet-stream or plain text, so the restore picker accepts those too. */
private val OPEN_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/**
 * Settings → Backup and restore. Export writes a file the user picks (Storage Access Framework); restore reads one, shows what
 * it would change and only writes after an explicit confirmation. The work lives in [BackupManager], so leaving the screen or
 * swapping displays mid-restore cannot interrupt it; this screen only shows its state.
 */
@Composable
fun BackupScreen(vm: SortieViewModel) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    val backup = graph.backup
    val state by backup.state.collectAsState()
    val current by vm.user.collectAsState()
    val session by graph.playLog.current.collectAsState()
    val writeError by graph.user.writeError.collectAsState()
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { backup.export(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { backup.preview(it) }
    val busy = state is BackupState.Working
    val pickExport: () -> Unit = { runCatching { create.launch(backup.suggestedName()) }.onFailure(backup::pickerUnavailable) }
    val pickRestore: () -> Unit = { runCatching { open.launch(OPEN_TYPES) }.onFailure(backup::pickerUnavailable) }

    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(state::class) {
        if (busy) return@LaunchedEffect
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    // Plain text in a LazyColumn is unreachable on the D-pad, so every passage sits in a focusable block that scrolls into view.
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("Backup and restore", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp)) }
            when (val s = state) {
                is BackupState.Working -> item { Note(s.what) }
                is BackupState.Preview -> preview(s, session?.gameId, primary, onConfirm = backup::confirmRestore, onCancel = backup::dismiss)
                is BackupState.Result -> item {
                    FocusRow(onClick = backup::dismiss, modifier = Modifier.focusRequester(primary)) {
                        Column {
                            Text(s.title, style = MaterialTheme.typography.titleSmall, color = if (s.ok) Amber else MaterialTheme.colorScheme.error)
                            s.lines.forEach { Note(it, error = !s.ok) }
                            Text("A: OK", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
                BackupState.Idle -> {
                    item {
                        FocusRow(onClick = pickExport, modifier = Modifier.focusRequester(primary)) {
                            Line("Export backup…", "save preferences, layout, dock, game notes and play history to a file you choose")
                        }
                    }
                    item {
                        FocusRow(onClick = pickRestore) {
                            Line("Restore from backup…", "pick a backup file; you see what it holds before anything changes")
                        }
                    }
                    session?.let { item { Note("${it.gameId} is being played. Export leaves its open session out; restore waits until it ends.") } }
                }
            }
            writeError?.let { item { Note("Preferences are not being saved: $it", error = true) } }
            item {
                InfoBlock("What a backup holds") {
                    Note("Now on this device: ${summary(current)}.")
                    Note(BackupManager.SCOPE)
                    Note(
                        "Restoring replaces every preference with the backup's and merges play history: sessions already on this device (and any " +
                            "recorded since the backup) are kept, so restoring the same file twice adds nothing. Unfinished sessions are never imported."
                    )
                    Note(
                        "Before a restore, the current state is saved to ${backup.safetyDir.path}/pre-restore-<time>.json. Restoring that file " +
                            "brings your previous preferences back; sessions merged in by a restore stay in the history."
                    )
                }
            }
            item {
                InfoBlock("Android keeps these, not Sortie") {
                    Note(BackupManager.NOT_RESTORED)
                    Note(
                        "Shortcut entries (DroidDeck games and similar) are restored as ids only: each one shows again once its app has pinned or " +
                            "published the shortcut on this device."
                    )
                }
            }
        }
        HintBar("A" to "choose", "B" to "back")
    }
}

private fun LazyListScope.preview(p: BackupState.Preview, playing: String?, primary: FocusRequester, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val b = p.backup
    val u = b.user
    val merge = p.merge
    item {
        // focused first, so confirming takes a deliberate D-pad down past Cancel
        InfoBlock("Restore ${p.source}?", Modifier.focusRequester(primary)) {
            val made = BackupManager.formatTime(b.createdAt)
            Note("Made $made on ${b.device.ifBlank { "an unknown device" }}${if (b.appVersion.isBlank()) "" else " by Sortie ${b.appVersion}"}.")
            Note("Preferences (replace the current ones): ${summary(u)}.")
            Note(
                "Home: ${u.homeLayout}, sort ${u.homeSort}; browse: ${u.browseLayout}; grid ${u.gridColumns} columns / ${u.gridRows} sideways rows; " +
                    "${u.hidden.size} hidden, ${u.profileOverrides.size} per-game and ${u.systemProfiles.size} per-system launcher choices."
            )
            Note(
                "Play history (merged, nothing here is removed): ${b.sessions.size} sessions in the file — ${merge.toInsert.size} new " +
                    "(+${merge.addedMinutes} min), ${merge.alreadyPresent} already on this device."
            )
            if (p.openIgnored > 0) Note("${p.openIgnored} unfinished sessions in the file will be ignored.")
            b.openSessionExcluded?.let { Note("${it.gameId} was being played when this backup was made; that session is not in it.") }
            if (p.missingLaunchables > 0) {
                Note(
                    "${p.missingLaunchables} apps or shortcuts it refers to aren't on this device; they stay hidden until the app is installed or the " +
                        "shortcut is pinned again.",
                    error = true
                )
            }
            Note("Your current preferences are saved as a safety copy first (it recovers preferences; merged history stays).")
        }
    }
    if (playing != null) {
        item { Note("Restore is blocked while $playing is being played. Quit it and return to Sortie first.", error = true) }
    }
    item { FocusRow(onClick = onCancel) { Line("Cancel", "keep everything as it is") } }
    if (playing == null) {
        item { FocusRow(onClick = onConfirm) { Line("Replace preferences and merge history", "writes user.json and the play database") } }
    }
}

/** A focusable passage of text (A does nothing), so the D-pad can reach and scroll to it. */
@Composable
private fun InfoBlock(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FocusRow(onClick = {}, modifier = modifier) {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

private fun summary(u: UserData): String = listOf(
    plural(u.favourites.size, "favourite"),
    plural(u.wantToPlay.size, "Want to play game"),
    plural(u.layout.size, "custom home tile"),
    plural(u.dock.count { it.isNotBlank() }, "dock slot"),
    plural(u.gameAnnotations.size, "game note"),
    plural(u.gameProgress.size, "Finished/Shelved game"),
    plural(u.magazineClippings.size, "magazine clipping"),
    plural(u.referenceCards.values.sumOf { it.size }, "reference card"),
    "${u.journalEntries.size} journal ${if (u.journalEntries.size == 1) "entry" else "entries"}",
    plural(u.shortcuts.size, "opted-in shortcut")
).joinToString(" · ")

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
