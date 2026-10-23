package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.db.GameEntity

@Composable
fun DetailScreen(game: GameEntity, vm: SortieViewModel) {
    val user by vm.user.collectAsState()
    val stats by vm.playStats.collectAsState()
    var showProfiles by remember { mutableStateOf(false) }
    val profiles = remember(game.id, user.profileOverrides[game.id]) { vm.profilesFor(game) }
    val fav = game.id in user.favourites
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(game.id) { runCatching { launchFocus.requestFocus() } }
    Row(Modifier.fillMaxSize().padding(16.dp)) {
        Cover(game, width = 200, height = 260)
        Column(Modifier.padding(start = 20.dp).verticalScroll(rememberScrollState())) {
            Text(game.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row { Stars(game.fit); Text("  " + listOfNotNull(game.systemId.uppercase(), game.year?.toString(), game.developer, game.players?.let { "$it players" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            game.hook?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Amber, modifier = Modifier.padding(top = 8.dp)) }
            stats[game.id]?.let { Text("Played ${it.minutes} min", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.launch(game) }, enabled = !game.placeholder, modifier = Modifier.focusRequester(launchFocus)) { Text("Sortie") }
                OutlinedButton(onClick = { showProfiles = !showProfiles }) { Text("Launch with…") }
                OutlinedButton(onClick = { vm.toggleFavourite(game.id) }) { Text(if (fav) "★ Favourite" else "☆ Favourite") }
            }
            if (showProfiles) {
                profiles.forEach { (p, installed) ->
                    FocusRow(onClick = { if (installed) { vm.setOverride(game.id, p.id); vm.launch(game, p) } }) {
                        Text((if (user.profileOverrides[game.id] == p.id) "● " else "○ ") + p.label + if (installed) "" else "  (not installed)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FocusRow(onClick = { vm.setOverride(game.id, null) }) { Text("Clear override", style = MaterialTheme.typography.bodySmall) }
            }
            if (game.flags.isNotBlank() || game.tags.isNotBlank()) {
                Row { (game.flags.split(',') + game.tags.split(',')).filter { it.isNotBlank() }.distinct().take(8).forEach { Badge(it) } }
            }
            Spacer(Modifier.height(8.dp))
            game.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(8.dp))
            Text(game.fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
