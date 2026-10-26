package io.github.esko997.sortie.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.launch.SessionEndWatch
import kotlinx.coroutines.delay

/** The question the invitation asks, in the player's own words. */
const val BOOKMARK_INVITATION = "Leave a text note about where you are in the game for when you return?"

/**
 * After a game Sortie saw end ([SessionEndWatch]), a small card over the bottom of the navigation asks whether to leave that
 * game's bookmark. It is not a dialog: nothing behind it is blocked, it takes no focus and opens no keyboard by itself, and it
 * goes away after [SessionEndWatch.SHOWN_MS] on screen as a "Not now". It only shows while this navigation window is resumed
 * and focused, on a real route, with no switcher open ([SessionEndWatch.mayPresent]); until then it waits (briefly: an
 * invitation never shown within [SessionEndWatch.UNSHOWN_TTL_MS] is dropped). **Leave a note** (or L2) opens the game's page
 * with its bookmark editor; only then does the keyboard appear. Its buttons can be reached with the D-pad or touched.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.BookmarkInvitationHost(vm: SortieViewModel, nav: NavHostController) {
    val graph = (LocalContext.current.applicationContext as SortieApp).graph
    val watch = graph.sessionEnds
    val invite by watch.invite.collectAsState()
    val user by vm.user.collectAsState()
    val switcher by graph.switcher.state.collectAsState()
    val entry by nav.currentBackStackEntryAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val current = invite
    val show = SessionEndWatch.mayPresent(
        current,
        user.bookmarkInvitation,
        System.currentTimeMillis(),
        resumed = state.isAtLeast(Lifecycle.State.RESUMED),
        windowFocused = windowFocused,
        switcherOpen = switcher.open,
        route = entry?.destination?.route
    )
    if (!show || current == null) return
    val title by produceState<String?>(null, current.gameId) { value = graph.db.games().byId(current.gameId)?.let(::displayTitle) }
    val focus = LocalFocusManager.current
    val inside = remember(current) { booleanArrayOf(false) }
    // a choice made with the D-pad hands the focus back to the screen above before the card goes
    val leave: () -> Unit = { if (inside[0]) focus.moveFocus(FocusDirection.Up) }
    val accept: () -> Unit = {
        leave()
        watch.accept(current)
        val route = "game/${Uri.encode(current.gameId)}"
        val here = entry?.destination?.route == "game/{id}" && entry?.arguments?.getString("id") == current.gameId
        if (!here) runCatching { nav.navigate(route) }.onFailure { watch.editorOpened(current.gameId) }
    }
    val hotkeys = LocalHotkeys.current
    DisposableEffect(hotkeys, current) {
        hotkeys.handle(setOf(Hotkeys.Key.NOTE))
        onDispose { hotkeys.release(setOf(Hotkeys.Key.NOTE)) }
    }
    LaunchedEffect(hotkeys, current) { hotkeys.events.collect { if (it == Hotkeys.Key.NOTE) accept() } }
    // time on screen only: hidden by the switcher or another window, it starts again when shown
    LaunchedEffect(current) {
        delay(SessionEndWatch.SHOWN_MS)
        leave()
        watch.dismiss(current)
    }
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 64.dp)) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .background(Slate.copy(alpha = 0.96f), shape)
                .border(1.dp, Amber.copy(alpha = 0.6f), shape)
                .onFocusChanged { inside[0] = it.hasFocus }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(BOOKMARK_INVITATION, style = MaterialTheme.typography.bodyLarge)
            title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Amber) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GlowButton("Leave a note", primary = true, onClick = accept)
                GlowButton("Not now") {
                    leave()
                    watch.dismiss(current)
                }
                Text(
                    "L2 leave a note",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }
        }
    }
}
