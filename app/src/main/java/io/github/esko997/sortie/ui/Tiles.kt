package io.github.esko997.sortie.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.esko997.sortie.data.manifest.GameAnnotation
import io.github.esko997.sortie.data.manifest.UserData
import kotlinx.coroutines.delay

/** Glow colour of a home tile that is being moved (edit mode); distinct from every focus colour. */
val LiftColour = Color(0xFF38BDF8)

/**
 * 1:1 tile shared by the home and browse grids. With a [cover] the art is cropped square under a bottom gradient so [title] stays
 * readable; [colour] marks a system or folder tile (colour wash, dot, idle border, cover washed at 45%); [icon] tiles (apps,
 * shortcuts) show the icon centred on Slate, with a label only when the tile has enough room for readable text.
 * The focus glow uses the cover's palette accent when
 * [paletteGlow] is set (decoded only once the tile is focused, then cached), else [colour], else Amber. [lifted] marks the tile
 * being moved in the home edit mode: [LiftColour] glow and a larger scale.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SquareTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    colour: Color? = null,
    cover: String? = null,
    logo: String? = null,
    paletteGlow: Boolean = false,
    favourite: Boolean = false,
    lifted: Boolean = false,
    icon: (() -> Drawable?)? = null,
    onLongClick: (() -> Unit)? = null,
    onFavourite: (() -> Unit)? = null,
    annotation: GameAnnotation? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val accent = if (paletteGlow && focused) rememberPalette(cover)?.accent else null
    val glow = when {
        lifted -> LiftColour
        accent != null -> accent
        colour != null -> colour
        else -> Amber
    }
    Box(
        modifier
            .focusGlow(focused || lifted, shape, glow, if (lifted) 1.12f else 1.06f, colour?.copy(alpha = 0.5f) ?: Color.Transparent)
            .aspectRatio(1f)
            .clip(shape)
            .background(colour?.copy(alpha = 0.22f) ?: Slate)
            .keyLongPress(onLongClick, onFavourite)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
    ) {
        if (icon != null) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp)) {
                // Below 100dp of usable width, labels crowd the icon; enlarged text needs proportionally more room.
                val compact = maxWidth < 100.dp * LocalDensity.current.fontScale
                if (compact) {
                    val iconSize = minOf(64.dp, maxWidth, maxHeight).value.toInt()
                    Box(
                        Modifier.fillMaxSize().semantics { contentDescription = title },
                        contentAlignment = Alignment.Center
                    ) {
                        IconView(icon, iconSize)
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            IconView(icon, minOf(64.dp, maxWidth, maxHeight).value.toInt())
                        }
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        } else {
            if (cover != null) {
                // behind a logo the cover is only a hint of the library; the logo is the name
                CoverImage(cover, Modifier.fillMaxSize(), ContentScale.Crop, alpha = if (logo != null) 0.2f else if (colour != null) 0.45f else 1f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))))
            } else if (colour == null) {
                Text(title.take(1), style = MaterialTheme.typography.displaySmall, color = Color(0xFF4A525C), modifier = Modifier.align(Alignment.Center))
            }
            if (logo != null) {
                CoverImage(logo, Modifier.align(Alignment.Center).fillMaxWidth(0.82f).fillMaxHeight(0.5f), ContentScale.Fit)
            } else if (colour != null) {
                Box(Modifier.padding(12.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(colour).align(Alignment.TopStart))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                if (logo == null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        annotation?.let {
            ControllerBadge(it, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = if (favourite) 32.dp else 8.dp))
        }
        if (favourite) Text("★", color = Amber, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/**
 * Horizontal rows or vertical columns, capped by the space available for readable square tiles.
 * Saved preferences are never changed; lists retain one column. Padding has already been removed
 * from the cross-axis space supplied to [ReadableGridCells].
 */
@Composable
fun ItemGrid(grid: Boolean, user: UserData, modifier: Modifier = Modifier, state: LazyGridState = rememberLazyGridState(), content: LazyGridScope.() -> Unit) {
    val preferred = if (user.gridHorizontal) user.gridRows else user.gridColumns
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val cells = remember(preferred, fontScale) { ReadableGridCells(preferred, fontScale) }
    if (grid && user.gridHorizontal) {
        LazyHorizontalGrid(
            cells,
            modifier,
            state = state,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    } else {
        LazyVerticalGrid(
            if (grid) cells else GridCells.Fixed(1),
            modifier,
            state = state,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid) 10.dp else 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

/** Keep at least 88 dp (scaled for larger text) per tile, reducing only the requested density. */
private data class ReadableGridCells(val preferred: Int, val fontScale: Float) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val minimum = (88.dp * fontScale).roundToPx()
        val fits = ((availableSize + spacing) / (minimum + spacing)).coerceAtLeast(1)
        return with(GridCells.Fixed(preferred.coerceIn(1, fits))) {
            calculateCrossAxisCellSizes(availableSize, spacing)
        }
    }
}

/** A full-width row inside a vertical grid, or a full-height column inside a horizontal one (headers, expanders). */
fun LazyGridScope.fullItem(key: String, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }, content = content)

/** One entry of a [ContextSheet]. */
class SheetAction(val label: String, val run: () -> Unit)

/**
 * Small controller-first context menu (hold A / long press): a dialog with one row per action plus Cancel; the first action takes
 * focus, A picks, B or a tap outside cancels. The dialog closes before the action runs.
 */
@Composable
fun ContextSheet(title: String, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    var focusedOnce by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(380.dp).clip(RoundedCornerShape(16.dp)).background(Slate).padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
            )
            actions.forEachIndexed { i, a ->
                FocusRow(
                    onClick = {
                        onDismiss()
                        a.run()
                    },
                    modifier = if (i == 0) Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) focusedOnce = true } else Modifier
                ) { Text(a.label, style = MaterialTheme.typography.bodyLarge) }
            }
            FocusRow(onClick = onDismiss, modifier = if (actions.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                Text("Cancel", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HintBar("A" to "choose", "B" to "cancel")
        }
        // Opened by a touch long-press the window is in touch input mode, where Compose refuses to move focus, so A would do
        // nothing until the D-pad is pressed; opened by the controller the dialog window gains focus a few frames after
        // composition and a single requestFocus() is lost. Ask for keyboard mode and retry briefly until the first row has focus.
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) {
            repeat(20) {
                if (focusedOnce) return@LaunchedEffect
                @OptIn(ExperimentalComposeUiApi::class)
                inputMode.requestInputMode(InputMode.Keyboard)
                runCatching { first.requestFocus() }
                delay(40)
            }
        }
    }
}

/** "Add to home" / "Remove from home" for [id] (a custom-layout tile id), with a toast saying what happened. */
fun homeAction(vm: SortieViewModel, id: String, placed: Boolean, context: Context): SheetAction =
    SheetAction(if (placed) "Remove from home" else "Add to home") {
        val now = vm.toggleHome(id)
        Toast.makeText(context, if (now) "Added to home" else "Removed from home", Toast.LENGTH_SHORT).show()
    }

/** Toggles game [id] in Want to play (R2, the context sheet, the detail button) with a toast like the favourite one. */
fun toggleWantToPlay(vm: SortieViewModel, id: String, context: Context) {
    val now = vm.toggleWantToPlay(id)
    Toast.makeText(context, if (now) "Added to Want to play" else "Removed from Want to play", Toast.LENGTH_SHORT).show()
}

/** "Want to play" / "Remove from Want to play" for game [id] in a [ContextSheet]. */
fun wantToPlayAction(vm: SortieViewModel, id: String, queued: Boolean, context: Context): SheetAction =
    SheetAction(if (queued) "Remove from Want to play" else "Want to play") { toggleWantToPlay(vm, id, context) }
