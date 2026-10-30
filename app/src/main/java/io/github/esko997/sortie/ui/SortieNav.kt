package io.github.esko997.sortie.ui

import android.net.Uri
import android.view.Display
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.db.SearchScope
import io.github.esko997.sortie.data.magazine.ReaderLocation
import io.github.esko997.sortie.mecha.ui.MechaStageHost
import io.github.esko997.sortie.mecha.ui.rememberMechaStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [nav] is owned by MainActivity, which reads its back stack when X hands the whole saved state (back stack, each screen's
 * filters and grid position) to the instance on the other screen. Search's text, filters and scope live in [SearchSession].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun SortieNav(vm: SortieViewModel, nav: NavHostController) {
    val enc = { s: String -> Uri.encode(s) }
    val systems by vm.systems.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val graph = (context.applicationContext as SortieApp).graph
    val hotkeys = LocalHotkeys.current
    val openIndexedGame = rememberIndexedGameOpener(vm) { nav.navigate("game/${enc(it)}") }
    val openApp = rememberBuiltinOpener()
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
    // Search opened from a system or folder list looks only there; from anywhere else, in the whole library. Text and filters stay.
    val openSearch: (SearchScope?) -> Unit = { from ->
        SearchSession.open(from)
        nav.navigate("search") { launchSingleTop = true }
    }
    DisposableEffect(hotkeys) {
        val keys = setOf(Hotkeys.Key.SEARCH, Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER)
        hotkeys.handle(keys)
        onDispose { hotkeys.release(keys) }
    }
    LaunchedEffect(hotkeys) {
        hotkeys.events.collect { key ->
            val route = nav.currentBackStackEntry?.destination?.route ?: ""
            when (key) {
                Hotkeys.Key.SEARCH -> if (route != "search" && route != "library") {
                    val id = nav.currentBackStackEntry?.arguments?.getString("id")
                    openSearch(
                        when (route) {
                            "system/{id}" -> id?.let { SearchScope.System(it) }
                            "folder/{id}" -> id?.let { SearchScope.Folder(it) }
                            else -> null
                        }
                    )
                }
                Hotkeys.Key.PREV_FOLDER, Hotkeys.Key.NEXT_FOLDER -> if (route == "home") {
                    // on Home L1/R1 page the sort modes, or the consoles while "By Console" is the sort
                    val delta = if (key == Hotkeys.Key.NEXT_FOLDER) 1 else -1
                    if (vm.user.value.homeSort == "console") vm.cycleHomeConsole(delta) else vm.cycleHomeSort(delta, SORTS.map { it.first })
                } else if (route != "library" && route != "tonight" && route != "reference/{id}" && systems.isNotEmpty()) {
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
    // One foreground resident; browsing surfaces declare safe perches and collision boundaries.
    val stage = rememberMechaStage(graph.mecha)
    val entry by nav.currentBackStackEntryAsState()
    val browsing = when (entry?.destination?.route) {
        "home", "system/{id}", "folder/{id}" -> true
        else -> false
    }
    val routeOffset = with(LocalDensity.current) { 18.dp.roundToPx() }
    MechaStageHost(stage, quiet = !browsing) {
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(220)) { routeOffset } },
            exitTransition = { fadeOut(tween(120)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { routeOffset } }
        ) {
            composable("home") {
                // Home is the launcher's root: Back has nothing to return to. Without this, Android moves a root task to the
                // back, which on a device where Sortie runs in an ordinary task (started from an icon or another launcher)
                // drops the player into Recents or a stale app. HomeScreen's own handlers (Move, console sort) win when on.
                BackHandler {}
                HomeScreen(
                    vm,
                    onSystem = { nav.navigate("system/${enc(it)}") },
                    onFolder = { nav.navigate("folder/$it") },
                    onGame = { nav.navigate("game/${enc(it)}") },
                    onSearch = { openSearch(null) },
                    onSettings = { nav.navigate("settings") },
                    onApps = { nav.navigate("apps") },
                    onMagazines = { openApp(BuiltinApp.MAGAZINES) },
                    onTonight = { nav.navigate("tonight") },
                    onLibrary = { nav.navigate("library") }
                )
            }
            composable("tonight") {
                TonightScreen(
                    vm,
                    onGame = { nav.navigate("game/${enc(it)}") },
                    onArticle = { issue, article ->
                        openBuiltin(context, BuiltinApp.MAGAZINES, view.display?.displayId ?: Display.DEFAULT_DISPLAY, ReaderLocation(issue, article))
                    }
                )
            }
            composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val games by remember(id) { vm.gamesOf(id) }.collectAsState(emptyList())
                GameListScreen(
                    systems.firstOrNull { it.def.id == id }?.def?.label ?: id,
                    games,
                    vm,
                    onGame = { nav.navigate("game/${enc(it)}") },
                    onSearch = { openSearch(SearchScope.System(id)) }
                )
            }
            composable("folder/{id}") { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val view by remember(id) { vm.folder(id) }.collectAsState(null)
                view?.let { f ->
                    FolderScreen(
                        view = f,
                        vm = vm,
                        onGame = { nav.navigate("game/${enc(it)}") },
                        onSearch = { openSearch(SearchScope.Folder(id)) }
                    )
                }
            }
            composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                val game by remember(id) { vm.game(id) }.collectAsState(null)
                game?.let {
                    DetailScreen(
                        it,
                        vm,
                        onReference = { nav.navigate("reference/${enc(id)}") },
                        // the Magazines app opens on this screen at the article; B from it closes the reader back to this page
                        onArticle = { issue, article ->
                            openBuiltin(context, BuiltinApp.MAGAZINES, view.display?.displayId ?: Display.DEFAULT_DISPLAY, ReaderLocation(issue, article))
                        }
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
            composable("reference/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                ReferenceCardsScreen(graph, id)
            }
            composable("apps") { AppsScreen(vm) }
            composable("switcher") { SwitcherScreen(onHome = { nav.popBackStack("home", inclusive = false) }) }
        }
    }
}

/** Opens Sortie's page for game id through [open] when the game is indexed on this device; says so when it is not. */
@Composable
internal fun rememberIndexedGameOpener(vm: SortieViewModel, open: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(open)
    return remember<(String) -> Unit>(vm, context, scope) {
        { id ->
            scope.launch {
                if (vm.game(id).first() != null) {
                    latest(id)
                } else {
                    Toast.makeText(context, "This game is not indexed on this device.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
