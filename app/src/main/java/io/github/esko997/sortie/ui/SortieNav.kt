package io.github.esko997.sortie.ui

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
import java.net.URLDecoder
import java.net.URLEncoder

@Composable
fun SortieNav(vm: SortieViewModel) {
    val nav = rememberNavController()
    val enc = { s: String -> URLEncoder.encode(s, "UTF-8") }
    val systems by vm.systems.collectAsState()
    LaunchedEffect(Unit) {
        Hotkeys.events.collect { key ->
            val route = nav.currentBackStackEntry?.destination?.route ?: ""
            when (key) {
                Hotkeys.Key.SEARCH -> if (route != "search") nav.navigate("search") { launchSingleTop = true }
                Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER -> if (systems.isNotEmpty()) {
                    val cur = nav.currentBackStackEntry?.arguments?.getString("id")?.let { URLDecoder.decode(it, "UTF-8") }
                    val i = systems.indexOfFirst { it.def.id == cur }
                    val next = when {
                        i < 0 -> 0
                        key == Hotkeys.Key.NEXT_FOLDER -> (i + 1) % systems.size
                        else -> (i - 1 + systems.size) % systems.size
                    }
                    nav.navigate("system/${enc(systems[next].def.id)}") { popUpTo("home"); launchSingleTop = true }
                }
                else -> Unit // FAVOURITE and CONTEXT are screen-local (see DetailScreen / GameListScreen)
            }
        }
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(vm, onSystem = { nav.navigate("system/${enc(it)}") }, onFolder = { nav.navigate("folder/$it") }, onSearch = { nav.navigate("search") }, onDiagnostics = { nav.navigate("diagnostics") })
        }
        composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = URLDecoder.decode(entry.arguments!!.getString("id")!!, "UTF-8")
            val systems by vm.systems.collectAsState()
            val games by remember(id) { vm.gamesOf(id) }.collectAsState(emptyList())
            GameListScreen(systems.firstOrNull { it.def.id == id }?.def?.label ?: id, games, vm) { nav.navigate("game/${enc(it)}") }
        }
        composable("folder/{id}") { entry ->
            when (entry.arguments!!.getString("id")) {
                "favourites" -> { val games by vm.favourites.collectAsState(emptyList()); GameListScreen("Favourites", games, vm) { nav.navigate("game/${enc(it)}") } }
                else -> { val recent by vm.recent.collectAsState(emptyList()); GameListScreen("Continue", recent.map { it.first }, vm) { nav.navigate("game/${enc(it)}") } }
            }
        }
        composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = URLDecoder.decode(entry.arguments!!.getString("id")!!, "UTF-8")
            val game by remember(id) { vm.game(id) }.collectAsState(null)
            game?.let { DetailScreen(it, vm) }
        }
        composable("search") { SearchScreen(vm) { nav.navigate("game/${enc(it)}") } }
        composable("diagnostics") { DiagnosticsScreen(vm) }
    }
}
