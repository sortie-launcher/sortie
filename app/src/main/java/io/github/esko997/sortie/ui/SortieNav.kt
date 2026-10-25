package io.github.esko997.sortie.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.ui.mecha.MechaFooter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [nav] is owned by MainActivity, which reads its back stack when X hands the whole saved state (back stack, each screen's
 * filters, search text and grid position) to the instance on the other screen.
 */
@Composable
fun SortieNav(vm: SortieViewModel, nav: NavHostController) {
    val enc = { s: String -> Uri.encode(s) }
    val systems by vm.systems.collectAsState()
    val context = LocalContext.current
    val graph = (context.applicationContext as SortieApp).graph
    val scope = rememberCoroutineScope()
    val openIndexedGame: (String) -> Unit = { id ->
        scope.launch {
            if (vm.game(id).first() != null) {
                nav.navigate("game/${enc(id)}")
            } else {
                Toast.makeText(context, "This game is not indexed on this device.", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(graph.user) {
        graph.user.writeError.collect { error ->
            if (error != null) {
                Toast.makeText(context, "Changes aren't saved. Check storage and Settings → Backup and restore.", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        Selection.openRoute.collect { route ->
            runCatching { nav.navigate(route) { launchSingleTop = true } } // a typo from adb must not crash the launcher
            Selection.openRoute.resetReplayCache()
        }
    }
    LaunchedEffect(Unit) {
        Hotkeys.handle(setOf(Hotkeys.Key.SEARCH, Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER))
        Hotkeys.events.collect { key ->
            val route = nav.currentBackStackEntry?.destination?.route ?: ""
            when (key) {
                Hotkeys.Key.SEARCH -> if (route != "search" && route != "library") nav.navigate("search") { launchSingleTop = true }
                Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER -> if (route == "home") {
                    // on Home L1/R1 page the sort modes, or the consoles while "By Console" is the sort
                    val delta = if (key == Hotkeys.Key.NEXT_FOLDER) 1 else -1
                    if (vm.user.value.homeSort == "console") vm.cycleHomeConsole(delta) else vm.cycleHomeSort(delta, SORTS.map { it.first })
                } else if (
                    route != "library" && route != "magazines" && route != MAGAZINE_ARTICLE_ROUTE &&
                    route != "journal" && route != "reference/{id}" && systems.isNotEmpty()
                ) {
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
                else -> Unit // FAVOURITE and CONTEXT are screen-local (see HomeScreen, DetailScreen, BrowseLayoutHotkey)
            }
        }
    }
    val granted by vm.storageGranted.collectAsState()
    if (!granted) {
        PermissionScreen()
        return
    }
    // The companion takes its own measured strip under the screens (on browsing and reading routes only), never an overlay.
    Column(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
            composable("home") {
                HomeScreen(
                    vm,
                    onSystem = { nav.navigate("system/${enc(it)}") },
                    onFolder = { nav.navigate("folder/$it") },
                    onGame = { nav.navigate("game/${enc(it)}") },
                    onSearch = { nav.navigate("search") },
                    onDiagnostics = { nav.navigate("settings") },
                    onApps = { nav.navigate("apps") },
                    onManual = { nav.navigate("manual") },
                    onMagazines = { nav.navigate("magazines") },
                    onJournal = { nav.navigate("journal") },
                    onLibrary = { nav.navigate("library") }
                )
            }
            composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val games by remember(id) { vm.gamesOf(id) }.collectAsState(emptyList())
                GameListScreen(systems.firstOrNull { it.def.id == id }?.def?.label ?: id, games, vm, onGame = { nav.navigate("game/${enc(it)}") })
            }
            composable("folder/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val view by remember(id) { vm.folder(id) }.collectAsState(null)
                view?.let { f -> FolderScreen(view = f, vm = vm, onGame = { nav.navigate("game/${enc(it)}") }) }
            }
            composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val game by remember(id) { vm.game(id) }.collectAsState(null)
                game?.let {
                    DetailScreen(
                        it,
                        vm,
                        onReference = { nav.navigate("reference/${enc(id)}") },
                        onArticle = { issue, article -> nav.navigate("magazines/${enc(issue)}/${enc(article)}") }
                    )
                }
            }
            composable("search") { SearchScreen(vm, onGame = { nav.navigate("game/${enc(it)}") }) }
            composable("diagnostics") { DiagnosticsScreen(vm) }
            composable("settings") {
                SettingsScreen(
                    vm,
                    onDiagnostics = { nav.navigate("diagnostics") },
                    onManual = { nav.navigate("manual") },
                    onBackup = { nav.navigate("backup") },
                    onLibrary = { nav.navigate("library") },
                    onImportCocoon = { nav.navigate("cocoon-import") }
                )
            }
            composable("library") { LibrarySetupScreen(graph, onDone = { nav.popBackStack() }, onGame = openIndexedGame) }
            composable("backup") { BackupScreen(vm) }
            composable("cocoon-import") { CocoonImportScreen(graph) }
            composable("manual") { ManualScreen() }
            composable("magazines") {
                MagazineScreen(graph, onGame = openIndexedGame)
            }
            // an article opened from a game page's "Featured in": B from the article returns to that page
            composable(
                MAGAZINE_ARTICLE_ROUTE,
                arguments = listOf(navArgument("issue") { type = NavType.StringType }, navArgument("article") { type = NavType.StringType })
            ) { entry ->
                val issue = entry.arguments?.getString("issue") ?: return@composable
                val article = entry.arguments?.getString("article") ?: return@composable
                MagazineScreen(graph, onGame = openIndexedGame, opened = ReaderLocation(issue, article))
            }
            composable("journal") { JournalScreen(graph, onGame = openIndexedGame) }
            composable("reference/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ReferenceCardsScreen(graph, id)
            }
            composable("apps") { AppsScreen(vm) }
            composable("switcher") { SwitcherScreen() }
        }
        MechaFooter(graph.mecha, nav)
    }
}

private const val MAGAZINE_ARTICLE_ROUTE = "magazines/{issue}/{article}"
