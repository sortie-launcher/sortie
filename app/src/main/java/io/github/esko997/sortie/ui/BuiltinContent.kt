package io.github.esko997.sortie.ui

import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.esko997.sortie.BuiltinApp
import io.github.esko997.sortie.BuiltinRequest
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.magazine.ReaderLocation

/**
 * A built-in app's window actions: move it to the other screen ([movable] only when there is one) and close it. The hint bars of
 * that window offer them by touch ([HintBar]); [root] tells the window's own bars from the bars of its dialogs.
 */
@Stable
class WindowActions(val root: View, val movable: Boolean, val move: () -> Unit, val close: () -> Unit)

/** Set inside a built-in app's window ([BuiltinContent]); null everywhere else, so other screens' hint bars are unchanged. */
val LocalWindowActions = staticCompositionLocalOf<WindowActions?> { null }

/**
 * A built-in app's whole content, inside its host window: Magazines (the reader, with the game and reference pages its links
 * open) or Performance. X moves the window to the other screen through [onMove] (null on one screen); [onClose] closes only
 * this window. B climbs back through the app and closes it from its first screen (the host finishes once nothing takes B).
 * [request] is a fresh "open this" from outside (a game page's "Featured in"), never repeated after recreation or a move.
 */
@Composable
fun BuiltinContent(app: BuiltinApp, vm: SortieViewModel, request: BuiltinRequest?, onMove: (() -> Unit)?, onClose: () -> Unit) {
    val hotkeys = LocalHotkeys.current
    val move by rememberUpdatedState(onMove)
    val close by rememberUpdatedState(onClose)
    val movable = onMove != null
    // X: this window's own dispatcher, so the launcher (or another built-in) on the other screen never moves with it
    DisposableEffect(hotkeys, movable) {
        val keys = setOf(Hotkeys.Key.SWAP_SCREENS)
        if (movable) hotkeys.handle(keys)
        onDispose { if (movable) hotkeys.release(keys) }
    }
    LaunchedEffect(hotkeys) { hotkeys.events.collect { if (it == Hotkeys.Key.SWAP_SCREENS) move?.invoke() } }
    val root = LocalView.current.rootView
    val actions = remember(root, movable) { WindowActions(root, movable, move = { move?.invoke() }, close = { close() }) }
    CompositionLocalProvider(LocalWindowActions provides actions) {
        when (app) {
            BuiltinApp.MAGAZINES -> MagazinesApp(vm, request)
            BuiltinApp.PERFORMANCE -> PerformanceApp()
        }
    }
}

/**
 * The reader and the pages its links lead to. A game page's "Featured in" goes back to the reader at that article (B then
 * climbs to its contents); a request from outside goes there too, handing B back so the reader closes to where it came from.
 */
@Composable
private fun MagazinesApp(vm: SortieViewModel, request: BuiltinRequest?) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    val nav = rememberNavController()
    val enc = { s: String -> Uri.encode(s) }
    var reading by remember { mutableStateOf(request?.let { ReaderRequest(it.opened, handsBack = true, serial = it.serial) }) }
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        if (reading?.serial == r.serial) return@LaunchedEffect // the one the reader was composed with
        reading = ReaderRequest(r.opened, handsBack = true, serial = r.serial)
        nav.popBackStack(READER, inclusive = false)
    }
    val openGame = rememberIndexedGameOpener(vm) { nav.navigate("game/${enc(it)}") }
    NavHost(nav, startDestination = READER, modifier = Modifier.fillMaxSize()) {
        composable(READER) { MagazineScreen(graph, onGame = openGame, request = reading) }
        composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val game by remember(id) { vm.game(id) }.collectAsState(null)
            game?.let {
                DetailScreen(
                    it,
                    vm,
                    onReference = { nav.navigate("reference/${enc(id)}") },
                    onArticle = { issue, article ->
                        // negative: never equal to a host request's serial
                        reading = ReaderRequest(ReaderLocation(issue, article), handsBack = false, serial = -SystemClock.elapsedRealtimeNanos())
                        nav.popBackStack(READER, inclusive = false)
                    }
                )
            }
        }
        composable("reference/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            ReferenceCardsScreen(graph, id)
        }
    }
}

private const val READER = "reader"

/** The window's Other screen (with its X) and Close, for touch; never in the controller's way, which has X and B for them. */
@Composable
internal fun WindowActionChips(actions: WindowActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (actions.movable) WindowChip("Other screen", "X", actions.move)
        WindowChip("Close", null, actions.close)
    }
}

@Composable
private fun WindowChip(label: String, button: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .focusProperties { canFocus = false }
            .clip(shape)
            .background(Slate)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (button != null) 4.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        button?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Ink,
                maxLines = 1,
                modifier = Modifier.clip(shape).background(Amber).padding(horizontal = 6.dp)
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Amber, maxLines = 1)
    }
}
