package io.github.esko997.sortie.ui

import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.manifest.BrowseChromeMode
import io.github.esko997.sortie.ui.SortieOutlinedButton as OutlinedButton

/** Auto folds the header in a window narrower or shorter than this, in dp divided by the font scale (larger text, less room). */
private const val COMPACT_WINDOW_DP = 600

/** How far a finger must pull down from the folded strip before the header opens. */
private const val PULL_TO_REVEAL_DP = 24

/** Content description of the folded strip's chevron, which opens the full header. */
const val SHOW_HEADER = "Show header"

/** Content description of the full header's chevron, which folds it to the strip. */
const val HIDE_HEADER = "Hide header"

/**
 * Whether a browse screen uses its compact form (`user.browseChrome`): always for Compact, never for Expanded, and for Auto when
 * the window — its real size, whatever device or screen it is on — is under [COMPACT_WINDOW_DP] either way at the current text size.
 */
@Composable
fun browseCompact(mode: String): Boolean = when (BrowseChromeMode.of(mode)) {
    BrowseChromeMode.EXPANDED -> false
    BrowseChromeMode.COMPACT -> true
    BrowseChromeMode.AUTO -> {
        val configuration = LocalConfiguration.current
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        minOf(configuration.screenWidthDp, configuration.screenHeightDp) / scale < COMPACT_WINDOW_DP
    }
}

/**
 * The folding header of Home and the library browse screens. It opens with the screen, folds to a one-line strip when the
 * controller focus goes into the games or the user drags the grid, and opens again from the strip (tap, pull down, its
 * chevron) or with Up from the grid's first row. [foldable] is false for Expanded (and Auto in a large window): the header
 * then never folds. [pinned]: nothing to browse right now (empty library, no results), so the header stays open whatever
 * [folded] says. Folded or not is saved with the destination, so Back from a game comes back the way the user left it.
 */
@Stable
class BrowseChrome internal constructor(folded: Boolean) {
    var foldable by mutableStateOf(false)
        internal set
    var pinned by mutableStateOf(false)
        internal set
    private var folded by mutableStateOf(folded)

    /** The full header is shown. */
    val expanded: Boolean get() = !foldable || pinned || !folded

    /** Where the controller focus goes once the header has opened or folded (see [rememberBrowseChrome]). */
    internal var pendingFocus: FocusRequester? = null

    /** The folded strip's [SHOW_HEADER] chevron. */
    val showFocus = FocusRequester()

    /** The full header's [HIDE_HEADER] chevron. */
    val hideFocus = FocusRequester()

    /** Folds the header, if it may; [focus] takes the controller focus afterwards (a control that is still there). */
    fun fold(focus: FocusRequester? = null) {
        if (!expanded || !foldable || pinned) return
        pendingFocus = focus
        folded = true
    }

    /** Opens the header; [focus] (one of its controls) takes the controller focus once it is composed. */
    fun reveal(focus: FocusRequester? = null) {
        if (expanded) return
        pendingFocus = focus
        folded = false
    }

    /** While [pinned] the header is open; it stays open afterwards until the user browses again (no fold mid-typing). */
    internal fun unfoldWhilePinned() {
        if (pinned && folded) folded = false
    }

    companion object {
        val saver: Saver<BrowseChrome, Boolean> = Saver(save = { it.folded }, restore = { BrowseChrome(it) })
    }
}

/**
 * The [BrowseChrome] of one destination, for `user.browseChrome` [mode]. Hands the controller focus to [BrowseChrome.pendingFocus]
 * after the replacement control has been laid out. Ignore transient grid focus while a removed header control is being
 * replaced, so it cannot immediately fold the newly revealed header. A tap never moves the focus.
 */
@Composable
fun rememberBrowseChrome(mode: String, pinned: Boolean = false): BrowseChrome {
    val chrome = rememberSaveable(saver = BrowseChrome.saver) { BrowseChrome(folded = false) }
    chrome.foldable = browseCompact(mode)
    chrome.pinned = pinned
    SideEffect { chrome.unfoldWhilePinned() }
    val inputModes = LocalInputModeManager.current
    val expanded = chrome.expanded
    LaunchedEffect(chrome, expanded) {
        val target = chrome.pendingFocus ?: return@LaunchedEffect
        try {
            if (inputModes.inputMode == InputMode.Keyboard) {
                withFrameNanos { }
                target.requestFocus()
            }
        } finally {
            chrome.pendingFocus = null
        }
    }
    return chrome
}

/**
 * On a browse grid: the header folds when the controller focus enters the grid or the user drags a grid that can scroll (not
 * for programmatic scrolls, such as a new search jumping to the top). While folded, Up out of the grid's first row opens the
 * header instead and focuses [revealTo], the control just above the grid; a folded strip is never the target of that Up.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.foldsBrowseChrome(chrome: BrowseChrome, state: LazyGridState, revealTo: FocusRequester): Modifier {
    LaunchedEffect(chrome, state) {
        state.interactionSource.interactions.collect {
            if (it is DragInteraction.Start && (state.canScrollForward || state.canScrollBackward)) chrome.fold()
        }
    }
    return this
        .onFocusChanged { if (it.hasFocus && chrome.pendingFocus == null) chrome.fold() }
        .focusProperties {
            exit = { direction ->
                if (direction == FocusDirection.Up && !chrome.expanded) {
                    chrome.reveal(revealTo)
                    FocusRequester.Cancel
                } else {
                    FocusRequester.Default
                }
            }
        }
        .focusGroup()
}

/**
 * The folded header: one line of [content] (title, counts, active filters) that a tap or a pull down opens, then [actions] and,
 * with [toggle], the [SHOW_HEADER] chevron. The pull starts on the strip itself, never at the screen edge (Android's own
 * gesture) or on the grid, whose swipes stay the grid's. A nested strip (the console list inside Home) passes [toggle] false:
 * the screen's own strip above it has the chevron. [inset]: the strip's own side margin, 0 inside an already padded screen.
 */
@Composable
fun FoldedHeader(
    chrome: BrowseChrome,
    modifier: Modifier = Modifier,
    toggle: Boolean = true,
    inset: Dp = 12.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable RowScope.() -> Unit
) {
    val pull = with(LocalDensity.current) { PULL_TO_REVEAL_DP.dp.toPx() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = inset, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .pointerInput(chrome) { detectTapGestures(onTap = { chrome.reveal() }) }
                .pointerInput(chrome) {
                    var pulled = 0f
                    detectVerticalDragGestures(onDragStart = { pulled = 0f }) { change, dy ->
                        pulled += dy
                        change.consume()
                        if (pulled > pull) chrome.reveal()
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
        actions()
        if (toggle) ChromeToggle(open = false, Modifier.focusRequester(chrome.showFocus)) { chrome.reveal(chrome.hideFocus) }
    }
}

/**
 * The header's chevron: ▾ [SHOW_HEADER] on the folded strip, ▴ [HIDE_HEADER] in the full header (only where it may fold). The
 * focus stays on the chevron across the change, so a controller user can toggle back and forth in place.
 */
@Composable
fun ChromeToggle(open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .semantics { contentDescription = if (open) HIDE_HEADER else SHOW_HEADER }
            .focusGlow(focused, sortieButtonShape(ButtonDefaults.outlinedShape), pressed = pressed),
        contentPadding = PaddingValues(horizontal = 14.dp),
        interactionSource = interaction
    ) { Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium, maxLines = 1) }
}

/** The full header's [HIDE_HEADER] chevron for [chrome], or nothing where the header never folds. */
@Composable
fun HideHeaderButton(chrome: BrowseChrome, modifier: Modifier = Modifier) {
    if (!chrome.foldable || chrome.pinned) return
    ChromeToggle(open = true, modifier.focusRequester(chrome.hideFocus)) { chrome.fold(chrome.showFocus) }
}

/**
 * On the column holding a folding header: its height eases between the header and the strip, briefly and once per change (no
 * looping motion). With the system's animations off (Developer options or Remove animations) it changes at once.
 */
@Composable
fun Modifier.foldingHeader(): Modifier {
    val context = LocalContext.current
    val still = remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    return if (still) this else animateContentSize(tween(160))
}
