package io.github.esko997.sortie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.esko997.sortie.data.manifest.LaunchProfile
import io.github.esko997.sortie.data.setup.LibraryPresets
import io.github.esko997.sortie.data.setup.RETROARCH_CORES
import io.github.esko997.sortie.launch.resolve

/**
 * Launch with…'s "Choose RetroArch core…" for [build]: every core in [RETROARCH_CORES], whatever the system, narrowed by a
 * filter on name, system and core id. A core stands for its `ra-`/`ra64-`/`ra32-<core>` profile, resolved through [profiles]
 * like any stored id (a launchers.json profile of that id wins). A gives it to [onPick], hold A to [onHold], Left/Right jump
 * by letter, B closes.
 */
@Composable
fun RetroArchCorePicker(
    build: LibraryPresets.RetroArchBuild,
    profiles: Map<String, LaunchProfile>,
    onPick: (LaunchProfile) -> Unit,
    onHold: (LaunchProfile) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val cores = remember(query) {
        val q = query.trim()
        RETROARCH_CORES.filter { c -> q.isEmpty() || listOf(c.name, c.system, c.id).any { it.contains(q, ignoreCase = true) } }
    }
    val keys = remember(cores) { cores.map { it.id } }
    val letters = remember(cores) { initials(cores.map { it.name }) }
    val memory = rememberGridMemory()
    val state = rememberLazyGridState()
    LaunchedEffect(cores) { state.scrollToItem(0) }
    val jumper = rememberLetterJump(memory, state)
    fun profile(core: String) = profiles.resolve(LibraryPresets.retroArchId(build, core))!!
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).fillMaxHeight(0.9f).clip(shape).background(PanelColour)
                .border(1.dp, RuleColour, shape).padding(12.dp)
        ) {
            Text(
                "${build.appLabel} core",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
            Note("Sortie can't see which cores ${build.appLabel} has: get one in its Online Updater first, or RetroArch reports it missing.")
            SetupTextField(query, { query = it }, "Filter by core, system or id", modifier = Modifier.padding(vertical = 6.dp))
            Box(Modifier.weight(1f)) {
                LazyVerticalGrid(GridCells.Fixed(1), Modifier.fillMaxSize().letterJump(jumper, letters, keys), state = state) {
                    items(cores, key = { it.id }) { c ->
                        RememberedItem(memory, c.id) {
                            FocusRow(onClick = { onPick(profile(c.id)) }, onLongClick = { onHold(profile(c.id)) }) {
                                Column {
                                    Text(c.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOf(c.system, c.id).filter(String::isNotEmpty).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                if (cores.isEmpty()) Note("No core matches “${query.trim()}”.")
                LetterFlash(jumper)
            }
            HintBar(
                "A" to "launch",
                "hold A" to "system default",
                "←/→" to "letter",
                "B" to "close",
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp))
            )
        }
        // the first core takes the focus (keyboard mode, retried while the dialog window gains focus); Up reaches the filter
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            memory.focused = keys.firstOrNull() ?: return@LaunchedEffect
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            memory.focusRemembered(state, keys)
        }
    }
}
