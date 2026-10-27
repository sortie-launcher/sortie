package io.github.esko997.sortie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.esko997.sortie.SortieApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Every window observes the committed theme, even when the companion is hidden or motion is disabled. */
@Composable
internal fun rememberSelectedTheme(): SortiePalette {
    val store = (LocalContext.current.applicationContext as SortieApp).graph.mecha
    val selection = remember(store) {
        store.view.map { it.state?.selectedTheme }.distinctUntilChanged()
    }
    val id by selection.collectAsState(initial = store.view.value.state?.selectedTheme)
    return SortiePalette.entries.firstOrNull { it.id == id } ?: SortiePalette.DEFAULT
}
