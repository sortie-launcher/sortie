package io.github.esko997.sortie.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.esko997.sortie.SortieApp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** All four hosts observe only committed appearance, independently of companion visibility and motion. */
@Composable
internal fun SelectedSortieTheme(content: @Composable () -> Unit) {
    val store = (LocalContext.current.applicationContext as SortieApp).graph.mecha
    val selection = remember(store) {
        store.view.distinctUntilChanged { before, after ->
            before.state?.selectedTheme == after.state?.selectedTheme &&
                before.state?.selectedFolderIcons == after.state?.selectedFolderIcons &&
                before.state?.selectedButtonStyle == after.state?.selectedButtonStyle
        }.map { view ->
            LauncherAppearance(
                view.state?.selectedTheme,
                view.state?.selectedFolderIcons,
                view.state?.selectedButtonStyle
            )
        }
    }
    val initial = remember(store) {
        val state = store.view.value.state
        LauncherAppearance(state?.selectedTheme, state?.selectedFolderIcons, state?.selectedButtonStyle)
    }
    val appearance by selection.collectAsState(initial)
    SortieTheme(
        palette = SortiePalette.entries.firstOrNull { it.id == appearance.palette } ?: SortiePalette.DEFAULT,
        folderIcons = appearance.folders?.let { SortieFolderIcons.fromId(it) } ?: SortieFolderIcons.DEFAULT,
        buttonStyle = appearance.buttons?.let { SortieButtonStyle.fromId(it) } ?: SortieButtonStyle.DEFAULT,
        content = content
    )
}

private data class LauncherAppearance(val palette: String?, val folders: String?, val buttons: String?)
