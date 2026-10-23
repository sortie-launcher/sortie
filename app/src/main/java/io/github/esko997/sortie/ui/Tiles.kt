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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay

/** Glow colour of a home tile that is being moved (edit mode); distinct from every focus colour. */
val LiftColour = Color(0xFF38BDF8)

/**
 * 1:1 tile shared by the home and browse grids. With a [cover] the art is cropped square under a bottom gradient so [title] stays
 * readable; [colour] marks a system or folder tile (colour wash, dot, idle border, cover washed at 45%); [icon] tiles (apps,
 * shortcuts) show the icon centred on Slate with the label below. The focus glow uses the cover's palette accent when
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
    paletteGlow: Boolean = false,
    favourite: Boolean = false,
    lifted: Boolean = false,
    icon: (() -> Drawable?)? = null,
    onLongClick: (() -> Unit)? = null
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
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
    ) {
        if (icon != null) {
            Column(
                Modifier.fillMaxSize().padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                IconView(icon, 64)
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            if (cover != null) {
                CoverImage(cover, Modifier.fillMaxSize(), ContentScale.Crop, alpha = if (colour != null) 0.45f else 1f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Ink.copy(alpha = 0.92f))))
            } else if (colour == null) {
                Text(title.take(1), style = MaterialTheme.typography.displaySmall, color = Color(0xFF4A525C), modifier = Modifier.align(Alignment.Center))
            }
            if (colour != null) Box(Modifier.padding(12.dp).size(10.dp).clip(RoundedCornerShape(5.dp)).background(colour).align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        if (favourite) Text("★", color = Amber, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

/** A full-width row inside a grid (headers, expanders, list-style rows next to tiles). */
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
