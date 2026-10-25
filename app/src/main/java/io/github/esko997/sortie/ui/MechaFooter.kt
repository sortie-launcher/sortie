package io.github.esko997.sortie.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.ui.MechaDock

/** Ordinary browsing and reading routes, where the companion hangs out below the screen. Settings, editors and the switcher stay bare. */
private val DOCK_ROUTES = setOf("home", "system/{id}", "folder/{id}", "apps", "search", "magazines", "magazines/{issue}/{article}")

/**
 * The footer under [SortieNav]'s screens: shown on [DOCK_ROUTES] while the keyboard is down, so it takes its own measured
 * space and never covers content, hint bars or text fields. A hidden companion leaves only a "Show companion" entry on Home.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MechaFooter(store: MechaStore, nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: return
    if (route !in DOCK_ROUTES) return
    val view by store.view.collectAsState()
    if (view.state?.enabled == false && route != "home") return
    MechaDock(store, reading = route.startsWith("magazines"), wander = entry?.id, hideStrip = WindowInsets.isImeVisible)
}
