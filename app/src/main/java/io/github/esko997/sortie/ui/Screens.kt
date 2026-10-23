package io.github.esko997.sortie.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.db.GameEntity

@Composable
fun HomeScreen(
    vm: SortieViewModel,
    onSystem: (String) -> Unit,
    onFolder: (String) -> Unit,
    onSearch: () -> Unit,
    onDiagnostics: () -> Unit,
    onApps: () -> Unit = {}
) {
    val systems by vm.systems.collectAsState()
    val folders by vm.folders.collectAsState()
    val biosMissing by vm.biosMissing.collectAsState()
    val user by vm.user.collectAsState()
    val total by vm.total.collectAsState()
    val index by vm.index.collectAsState()
    val apps by vm.apps.collectAsState()
    val covers by vm.tileCovers.collectAsState()
    val grid = user.homeLayout == "grid"
    androidx.compose.runtime.LaunchedEffect(Unit) { Selection.set(null) }
    // Y opens search from Home (Start is intercepted by Android while Sortie is the home app); Select toggles list/grid.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val keys = setOf(Hotkeys.Key.FAVOURITE, Hotkeys.Key.CONTEXT)
        Hotkeys.handle(keys)
        onDispose { Hotkeys.release(keys) }
    }
    androidx.compose.runtime.LaunchedEffect(grid) {
        Hotkeys.events.collect {
            when (it) {
                Hotkeys.Key.FAVOURITE -> onSearch()
                Hotkeys.Key.CONTEXT -> vm.setHomeLayout(if (grid) "list" else "grid")
                else -> Unit
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
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
        val tiles = buildList {
            folders.forEach { f ->
                add(
                    Tile("f:" + f.def.id, f.def.label, "${f.games.size}" + if (f.problems.isNotEmpty()) " · rule problems" else "", Amber) {
                        onFolder(f.def.id)
                    }
                )
            }
            add(Tile("apps", "Apps", "${apps.size}", androidx.compose.ui.graphics.Color(0xFF9AA3AD), onClick = onApps))
            systems.forEach { row ->
                val missing = biosMissing[row.def.id]
                add(
                    Tile(
                        "s:" + row.def.id,
                        row.def.label,
                        "${row.count} · ${row.def.generation}" + (
                            missing?.let {
                                " · BIOS missing: ${it.joinToString()}"
                            } ?: ""
                            ),
                        parseColour(row.def.colour),
                        cover = covers[row.def.id]
                    ) { onSystem(row.def.id) }
                )
            }
        }
        if (grid) {
            LazyVerticalGrid(
                GridCells.Fixed(user.gridColumns),
                Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                gridItems(tiles, key = { it.key }) { t -> SquareTile(t) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
                item { SectionTitle("Folders") }
                items(
                    tiles.filter { !it.key.startsWith("s:") },
                    key = { it.key }
                ) { t -> FocusRow(onClick = t.onClick) { FolderLine(t.title, t.subtitle, t.colour) } }
                item { SectionTitle("Systems") }
                items(
                    tiles.filter { it.key.startsWith("s:") },
                    key = { it.key }
                ) { t -> FocusRow(onClick = t.onClick) { FolderLine(t.title, t.subtitle, t.colour) } }
            }
        }
        HintBar("A" to "open", "Y" to "search", "Select" to if (grid) "list view" else "grid view", "L1/R1" to "systems", "X" to "swap screens")
    }
}

private class Tile(
    val key: String,
    val title: String,
    val subtitle: String,
    val colour: androidx.compose.ui.graphics.Color,
    val cover: String? = null,
    val onClick: () -> Unit
)

/** 1:1 tile for the grid layout: system colour wash, label, count; grows slightly when focused. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SquareTile(t: Tile) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "tile")
    Box(
        Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(t.colour.copy(alpha = 0.22f))
            .border(2.dp, if (focused) Amber else t.colour.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .combinedClickable(interactionSource = interaction, indication = null, onClick = t.onClick)
    ) {
        // a representative cover, washed under a gradient so the label stays readable
        t.cover?.let { c ->
            coil3.compose.AsyncImage(
                model = java.io.File(c),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(0.35f to androidx.compose.ui.graphics.Color.Transparent, 1f to Ink.copy(alpha = 0.92f))
                )
            )
        }
        Box(Modifier.padding(12.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(t.colour).align(Alignment.TopStart))
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                t.subtitle.substringBefore(" · BIOS"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
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
fun GameListScreen(title: String, games: List<GameEntity>, vm: SortieViewModel, onGame: (String) -> Unit, subtitle: String? = null) {
    val stats by vm.playStats.collectAsState()
    val saves by vm.saveStems.collectAsState()
    var minFit by rememberSaveable { mutableStateOf(0) }
    var onDeviceOnly by rememberSaveable { mutableStateOf(true) }
    var notesOnly by rememberSaveable { mutableStateOf(false) }
    val shown = remember(games, minFit, onDeviceOnly, notesOnly) {
        games.filter { (!onDeviceOnly || !it.placeholder) && it.fit >= minFit && (!notesOnly || it.handWritten) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Column {
                        Text("$title · ${shown.size}", style = MaterialTheme.typography.titleLarge)
                        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { shown.randomOrNull()?.let { onGame(it.id) } }, enabled = shown.isNotEmpty()) { Text("🎲 Pick one") }
                    FilterChip(selected = minFit >= 4, onClick = { minFit = if (minFit >= 4) 0 else 4 }, label = { Text("★★★★+") })
                    FilterChip(selected = notesOnly, onClick = { notesOnly = !notesOnly }, label = { Text("✍ notes") })
                    FilterChip(selected = onDeviceOnly, onClick = { onDeviceOnly = !onDeviceOnly }, label = { Text("on device") })
                }
            }
            items(shown, key = {
                it.id
            }) { g -> GameRow(g, stats[g.id]?.minutes, hasSave = g.fileName.substringBeforeLast('.').lowercase() in saves, onClick = { onGame(g.id) }) }
        }
        HintBar("A" to "open", "L1/R1" to "systems", "B" to "back")
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
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(results, key = { it.id }) { g -> GameRow(g, stats[g.id]?.minutes, onClick = { onGame(g.id) }) }
        }
        HintBar("A" to "open", "B" to "back")
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
            val shizuku by vm.shizuku.collectAsState()
            val moveResult by vm.moveResult.collectAsState()
            val secondary = snap.device.screens.count > 1
            SectionTitle("Screens (Shizuku)")
            Text("Shizuku: ${shizuku.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.shizukuRefresh() }) { Text("Refresh") }
                OutlinedButton(onClick = { vm.shizukuRequest() }) { Text("Request permission") }
                if (secondary) {
                    OutlinedButton(onClick = { vm.moveTopApp(0, 4) }) { Text("Top app → bottom") }
                    OutlinedButton(onClick = { vm.moveTopApp(4, 0) }) { Text("Bottom app → top") }
                }
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
