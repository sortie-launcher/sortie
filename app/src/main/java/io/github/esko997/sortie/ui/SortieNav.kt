package io.github.esko997.sortie.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun SortieNav(vm: SortieViewModel) {
    val nav = rememberNavController()
    val enc = { s: String -> Uri.encode(s) }
    val systems by vm.systems.collectAsState()
    LaunchedEffect(Unit) {
        Selection.openRoute.collect { route ->
            nav.navigate(route) { launchSingleTop = true }
            Selection.openRoute.resetReplayCache()
        }
    }
    LaunchedEffect(Unit) {
        Hotkeys.handle(setOf(Hotkeys.Key.SEARCH, Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER))
        Hotkeys.events.collect { key ->
            val route = nav.currentBackStackEntry?.destination?.route ?: ""
            when (key) {
                Hotkeys.Key.SEARCH -> if (route != "search") nav.navigate("search") { launchSingleTop = true }
                Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER -> if (systems.isNotEmpty()) {
                    val cur = nav.currentBackStackEntry?.arguments?.getString("id")
                    val i = systems.indexOfFirst { it.def.id == cur }
                    val next = when {
                        i < 0 -> 0
                        key == Hotkeys.Key.NEXT_FOLDER -> (i + 1) % systems.size
                        else -> (i - 1 + systems.size) % systems.size
                    }
                    nav.navigate("system/${enc(systems[next].def.id)}") {
                        popUpTo("home")
                        launchSingleTop = true
                    }
                }
                else -> Unit // FAVOURITE and CONTEXT are screen-local (see DetailScreen / GameListScreen)
            }
        }
    }
    val granted by vm.storageGranted.collectAsState()
    if (!granted) {
        PermissionScreen()
        return
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(vm, onSystem = {
                nav.navigate("system/${enc(it)}")
            }, onFolder = { nav.navigate("folder/$it") }, onSearch = { nav.navigate("search") }, onDiagnostics = { nav.navigate("diagnostics") })
        }
        composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val games by remember(id) { vm.gamesOf(id) }.collectAsState(emptyList())
            GameListScreen(systems.firstOrNull { it.def.id == id }?.def?.label ?: id, games, vm, onGame = { nav.navigate("game/${enc(it)}") })
        }
        composable("folder/{id}") { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val view by remember(id) { vm.folder(id) }.collectAsState(null)
            view?.let { f ->
                GameListScreen(
                    title = f.def.label,
                    games = f.games,
                    vm = vm,
                    onGame = { nav.navigate("game/${enc(it)}") },
                    subtitle = f.problems.takeIf { it.isNotEmpty() }?.joinToString("; ")
                )
            }
        }
        composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val game by remember(id) { vm.game(id) }.collectAsState(null)
            game?.let { DetailScreen(it, vm) }
        }
        composable("search") { SearchScreen(vm, onGame = { nav.navigate("game/${enc(it)}") }) }
        composable("diagnostics") { DiagnosticsScreen(vm) }
    }
}
