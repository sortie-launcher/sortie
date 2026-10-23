package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.esko997.sortie.data.db.GameEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(game: GameEntity, vm: SortieViewModel) {
    val user by vm.user.collectAsState()
    val stats by vm.playStats.collectAsState()
    var showProfiles by remember { mutableStateOf(false) }
    val snap by vm.manifests.collectAsState()
    val profiles = remember(game.id, user.profileOverrides[game.id], snap.loadedAt) { vm.profilesFor(game) }
    val fav = game.id in user.favourites
    val palette = rememberPalette(game.cover ?: game.screenshot)
    val accent = palette?.accent ?: Amber
    val launchFocus = remember { FocusRequester() }
    LaunchedEffect(game.id) { Selection.set(game) }
    LaunchedEffect(game.id) { runCatching { launchFocus.requestFocus() } }
    androidx.compose.runtime.DisposableEffect(Unit) {
        Hotkeys.handle(setOf(Hotkeys.Key.FAVOURITE))
        onDispose { Hotkeys.release(setOf(Hotkeys.Key.FAVOURITE)) }
    }
    LaunchedEffect(game.id) { Hotkeys.events.collect { if (it == Hotkeys.Key.FAVOURITE) vm.toggleFavourite(game.id) } }
    Column(Modifier.fillMaxSize().background(palette?.backdrop ?: Ink)) {
        Row(Modifier.weight(1f).padding(16.dp)) {
            Cover(game, width = 200, height = 260)
            Column(Modifier.padding(start = 20.dp).verticalScroll(rememberScrollState())) {
                Text(displayTitle(game), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row {
                    Stars(game.fit)
                    Text(
                        "  " + listOfNotNull(
                            game.systemId.uppercase(), game.year?.toString(), game.developer,
                            game.players?.let {
                                "$it players"
                            }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                game.hook?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = accent, modifier = Modifier.padding(top = 8.dp)) }
                stats[game.id]?.let { Text("Played ${it.minutes} min", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                val sortieInteraction = remember { MutableInteractionSource() }
                val profilesInteraction = remember { MutableInteractionSource() }
                val favInteraction = remember { MutableInteractionSource() }
                val favShape = RoundedCornerShape(12.dp)
                FlowRow(
                    Modifier.padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            vm.launch(game)
                        },
                        enabled = !game.placeholder,
                        modifier = Modifier
                            .focusGlow(sortieInteraction.collectIsFocusedAsState().value, ButtonDefaults.shape, accent)
                            .focusRequester(launchFocus),
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Ink),
                        interactionSource = sortieInteraction
                    ) {
                        Text("Sortie")
                    }
                    OutlinedButton(
                        onClick = { showProfiles = !showProfiles },
                        modifier = Modifier.focusGlow(profilesInteraction.collectIsFocusedAsState().value, ButtonDefaults.outlinedShape, accent),
                        interactionSource = profilesInteraction
                    ) { Text("Launch with…") }
                    OutlinedIconToggleButton(
                        checked = fav,
                        onCheckedChange = { vm.toggleFavourite(game.id) },
                        modifier = Modifier
                            .focusGlow(favInteraction.collectIsFocusedAsState().value, favShape, accent)
                            .size(48.dp)
                            .semantics { contentDescription = "Favourite" },
                        shape = favShape,
                        colors = IconButtonDefaults.outlinedIconToggleButtonColors(
                            checkedContainerColor = accent.copy(alpha = 0.18f),
                            checkedContentColor = accent
                        ),
                        interactionSource = favInteraction
                    ) { Text(if (fav) "★" else "☆", fontSize = 24.sp) }
                }
                if (showProfiles) {
                    profiles.forEach { (p, installed) ->
                        FocusRow(
                            onClick = {
                                if (installed) {
                                    vm.setOverride(game.id, p.id)
                                    vm.launch(game, p)
                                }
                            },
                            colour = accent
                        ) {
                            Text(
                                (if (user.profileOverrides[game.id] == p.id) "● " else "○ ") + p.label + if (installed) "" else "  (not installed)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    FocusRow(onClick = { vm.setOverride(game.id, null) }, colour = accent) {
                        Text("Clear override", style = MaterialTheme.typography.bodySmall)
                    }
                    FocusRow(onClick = { vm.hide(game.id, true) }, colour = accent) {
                        Text("Hide this game from folders", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (game.flags.isNotBlank() || game.tags.isNotBlank()) {
                    Row { (game.flags.split(',') + game.tags.split(',')).filter { it.isNotBlank() }.distinct().take(8).forEach { Badge(it) } }
                }
                val variants by remember(game.id) { vm.variantsOf(game.id) }.collectAsState(emptyList())
                if (variants.isNotEmpty()) {
                    Text(
                        "Also: " + variants.joinToString(" · ") { it.fileName },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    variants.forEach { v ->
                        FocusRow(onClick = { vm.launch(v) }, colour = accent) { Text("Sortie ${v.fileName}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                game.desc?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(8.dp))
                Text(game.fileName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HintBar("A" to "sortie", "Y" to "favourite", "Select" to "launch with…", "B" to "back")
    }
}
