package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.db.GameEntity

@Composable
fun HomeScreen(vm: SortieViewModel, onSystem: (String) -> Unit, onFolder: (String) -> Unit, onSearch: () -> Unit, onDiagnostics: () -> Unit) {
    val systems by vm.systems.collectAsState()
    val total by vm.total.collectAsState()
    val index by vm.index.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Sortie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("$total games", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (index.running) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onSearch) { Text("Search") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDiagnostics) { Text("Diagnostics") }
            }
        }
        item { SectionTitle("Folders") }
        item { FocusRow(onClick = { onFolder("continue") }) { FolderLine("Continue", "recently played", Amber) } }
        item { FocusRow(onClick = { onFolder("favourites") }) { FolderLine("Favourites", "games and apps you starred", Amber) } }
        item { SectionTitle("Systems") }
        items(systems, key = { it.def.id }) { row ->
            FocusRow(onClick = { onSystem(row.def.id) }) { FolderLine(row.def.label, "${row.count} · ${row.def.generation}", parseColour(row.def.colour)) }
        }
    }
}

@Composable
private fun FolderLine(title: String, subtitle: String, colour: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColourChip(colour)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GameListScreen(title: String, games: List<GameEntity>, vm: SortieViewModel, onGame: (String) -> Unit) {
    val stats by vm.playStats.collectAsState()
    var minFit by rememberSaveable { mutableStateOf(0) }
    var onDeviceOnly by rememberSaveable { mutableStateOf(true) }
    var notesOnly by rememberSaveable { mutableStateOf(false) }
    val shown = remember(games, minFit, onDeviceOnly, notesOnly) {
        games.filter { (!onDeviceOnly || !it.placeholder) && it.fit >= minFit && (!notesOnly || it.handWritten) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Text("$title · ${shown.size}", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                FilterChip(selected = minFit >= 4, onClick = { minFit = if (minFit >= 4) 0 else 4 }, label = { Text("★★★★+") })
                FilterChip(selected = notesOnly, onClick = { notesOnly = !notesOnly }, label = { Text("✍ notes") })
                FilterChip(selected = onDeviceOnly, onClick = { onDeviceOnly = !onDeviceOnly }, label = { Text("on device") })
            }
        }
        items(shown, key = { it.id }) { g -> GameRow(g, stats[g.id]?.minutes, onClick = { onGame(g.id) }) }
    }
}

@Composable
fun SearchScreen(vm: SortieViewModel, onGame: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val results by remember(q) {
        if (q.trim().length >= 2) {
            vm.search(q.trim()).let { f ->
                kotlinx.coroutines.flow.flow {
                    kotlinx.coroutines.delay(200)
                    f.collect { emit(it) }
                }
            }
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    }.collectAsState(emptyList())
    val stats by vm.playStats.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            q,
            { q = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("title, genre, developer, description…") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results, key = { it.id }) { g -> GameRow(g, stats[g.id]?.minutes, onClick = { onGame(g.id) }) }
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: SortieViewModel) {
    val index by vm.index.collectAsState()
    val log by vm.launchLog.collectAsState()
    val snap by vm.manifests.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.padding(vertical = 8.dp)) {
                Button(onClick = { vm.reindex(force = true) }, enabled = !index.running) { Text(if (index.running) "Indexing…" else "Re-index everything") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.reloadManifests() }) { Text("Reload manifests") }
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

@Composable
fun PermissionScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sortie needs access to the library card", style = MaterialTheme.typography.titleLarge)
        Text("Grant \"All files access\" in Android settings, or over adb:", style = MaterialTheme.typography.bodyMedium)
        Text(
            "adb shell appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Open settings") }
    }
}
