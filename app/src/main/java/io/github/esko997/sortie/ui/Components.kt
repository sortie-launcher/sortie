package io.github.esko997.sortie.ui

import android.graphics.BlurMaskFilter
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.index.cleanTitle
import io.github.esko997.sortie.data.manifest.GameAnnotation
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val PULSE_HALF_PERIOD_S = 0.6
private const val PULSE_FOR_S = 3.0

/**
 * Controller-focus highlight shared by every focusable row/tile: a 3dp [colour] border, a soft blurred glow just outside the bounds whose
 * alpha pulses 0.55..1 (~1.2 s period) and a subtle [focusedScale]. The pulse transition only exists while [focused], so unfocused items
 * run no infinite animation. Unfocused items draw a 2dp [idleBorder] unless it is transparent, i.e. they look exactly as before.
 */
@Composable
fun Modifier.focusGlow(focused: Boolean, shape: Shape, colour: Color = Amber, focusedScale: Float = 1.02f, idleBorder: Color = Color.Transparent): Modifier {
    val scale = animateFloatAsState(if (focused) focusedScale else 1f, label = "focusScale")
    // The glow pulses for a few seconds after the focus lands, then holds: an endless pulse kept Sortie redrawing while idle
    // (~50% of a core at the display rate, still ~30% sampled at 20 Hz — a launcher waiting for input should cost nothing).
    val pulse: State<Float>? = if (focused) {
        val alpha = remember { mutableFloatStateOf(1f) }
        LaunchedEffect(Unit) {
            val t0 = System.nanoTime()
            while (true) {
                val t = (System.nanoTime() - t0) / 1e9
                if (t >= PULSE_FOR_S) break
                alpha.floatValue = 0.775f + 0.225f * sin(t * PI / PULSE_HALF_PERIOD_S).toFloat()
                delay(50)
            }
            alpha.floatValue = 1f
        }
        alpha
    } else {
        null
    }
    // state is read only inside the layer/draw lambdas, so the pulse re-draws without recomposing the row
    val base = this
        .zIndex(if (focused) 1f else 0f)
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
    if (pulse == null) return if (idleBorder == Color.Transparent) base else base.border(2.dp, idleBorder, shape)
    return base
        .drawWithCache {
            // The blurred outline is rasterised once per size into a bitmap; the pulse then only changes the alpha of one
            // texture draw per frame. Blurring the path on every frame cost ~70% of a core on the Thor.
            val spread = 3.dp.toPx()
            val margin = (spread + 12.dp.toPx()).toInt()
            val w = size.width.toInt() + 2 * margin
            val h = size.height.toInt() + 2 * margin
            val bitmap = ImageBitmap(w, h)
            val outline = shape.createOutline(Size(size.width + 2 * spread, size.height + 2 * spread), layoutDirection, this)
            val paint = Paint().apply {
                color = colour
                style = PaintingStyle.Stroke
                strokeWidth = 6.dp.toPx()
                asFrameworkPaint().maskFilter = BlurMaskFilter(8.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
            }
            Canvas(bitmap).run {
                translate(margin - spread, margin - spread)
                drawOutline(outline, paint)
            }
            onDrawBehind { drawImage(bitmap, topLeft = Offset(-margin.toFloat(), -margin.toFloat()), alpha = pulse.value) }
        }
        .border(3.dp, colour, shape)
}

/**
 * Held buttons for the controller: A (context sheet) and R1 (favourite). `combinedClickable`'s long press only listens to touch;
 * on a held key Android repeats the key-down (first repeat after ~500 ms), so the first repeat is the hold. Every release that
 * follows is swallowed so it does not also click or page: the gamepad's BUTTON_A arrives twice, raw and as the framework's
 * DPAD_CENTER fallback, whose key-up is sent regardless; R1's short press pages systems/sorts on key-up (MainActivity), so
 * swallowing the up after a hold is what keeps a favourite from also paging. A fresh press (repeat 0) re-arms.
 */
@Composable
fun Modifier.keyLongPress(onLongClick: (() -> Unit)?, onHoldR1: (() -> Unit)? = null): Modifier {
    if (onLongClick == null && onHoldR1 == null) return this
    var fired by remember { mutableStateOf(false) }
    return onPreviewKeyEvent { e ->
        val action = when (e.key) {
            Key.ButtonA, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> onLongClick
            Key.ButtonR1 -> onHoldR1
            else -> null
        } ?: return@onPreviewKeyEvent false
        when {
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                // a fresh press: the previous hold is over (its key-ups, however many, have passed)
                if (fired && e.key != Key.DirectionCenter) fired = false
                fired
            }
            e.type == KeyEventType.KeyDown -> {
                if (!fired) {
                    fired = true
                    action()
                }
                true
            }
            else -> fired
        }
    }
}

/**
 * R2 on a focused game tile or row toggles that game's Want to play membership, once per press: only the first key-down acts.
 * A held trigger repeats the key-down; those repeats and the release are swallowed, so a hold never flickers the membership —
 * also when the toggle removed the tile (Want to play folder) and the focus moved to a neighbour before the repeats arrive.
 * Text fields, apps, folders and settings rows never carry it, so R2 there reaches nothing.
 */
fun Modifier.wantToPlayKey(onToggle: (() -> Unit)?): Modifier = if (onToggle == null) {
    this
} else {
    onPreviewKeyEvent { e ->
        if (e.key != Key.ButtonR2) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) onToggle()
        true
    }
}

/** A focus-aware row: [focusGlow] outline in [colour] plus a subtle surface when focused (D-pad). Long press = context menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    colour: Color = Amber,
    onFavourite: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .focusGlow(focused, shape, colour)
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Slate else Color.Transparent)
            .keyLongPress(onLongClick, onFavourite)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) { content() }
}

/** The title without the file-name noise (see [cleanTitle]). */
fun displayTitle(g: GameEntity): String = cleanTitle(g.title)

@Composable
fun Stars(fit: Int, modifier: Modifier = Modifier) {
    if (fit <= 0) return
    Text("★".repeat(fit.coerceIn(0, 5)) + "☆".repeat(5 - fit.coerceIn(0, 5)), color = Amber, style = MaterialTheme.typography.labelMedium, modifier = modifier)
}

@Composable
fun Cover(game: GameEntity, width: Int = 56, height: Int = 72, modifier: Modifier = Modifier) {
    val path = game.cover ?: game.screenshot
    Box(modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(Slate), contentAlignment = Alignment.Center) {
        if (path != null) {
            CoverImage(path, Modifier.size(width.dp, height.dp), contentScale = ContentScale.Fit)
        } else {
            Text(game.title.take(1), style = MaterialTheme.typography.titleLarge, color = Color(0xFF4A525C))
        }
    }
}

@Composable
fun GameRow(
    game: GameEntity,
    minutes: Int?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    hasSave: Boolean = false,
    onFavourite: (() -> Unit)? = null,
    /** The user's controller requirement for [game] (from `user.gameAnnotations`, looked up by the caller); unknown draws nothing. */
    annotation: GameAnnotation? = null,
    /** R2: toggles [game] in Want to play (see [wantToPlayKey]). */
    onWantToPlay: (() -> Unit)? = null,
    /** ★ before the title, as on [LauncherRow] and the grid's [SquareTile]. */
    favourite: Boolean = false,
    /** The system's display label, first in the metadata line (so a row without year/players/genre is never blank). */
    platform: String? = null,
    /** A leading badge naming the row's role, e.g. "Continue" on the home list. */
    label: String? = null
) {
    FocusRow(onClick = onClick, modifier = Modifier.heroOnFocus(game).wantToPlayKey(onWantToPlay), onLongClick = onLongClick, onFavourite = onFavourite) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(game)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    (if (favourite) "★ " else "") + displayTitle(game),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Stars(game.fit)
                    val meta = listOfNotNull(platform, game.year?.toString(), game.players?.let { "$it P" }, game.genre?.substringBefore(',')?.take(24))
                    Text(
                        "  " + meta.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    label?.let { Badge(it, Color(0xFF78350F)) }
                    if (game.hack) Badge("hack")
                    if (game.placeholder) Badge("not on device", Color(0xFF7F1D1D))
                    if (game.arriving) Badge("arriving", Color(0xFF1E3A8A))
                    if ((minutes ?: 0) > 0) Badge("${minutes}m played", Color(0xFF14532D))
                    if (hasSave) Badge("save", Color(0xFF3F3F46))
                    annotation?.let { ControllerBadge(it, Modifier.padding(end = 6.dp, top = 2.dp)) }
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, colour: Color = Color(0xFF334155)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFE6E8EB),
        modifier = Modifier.padding(end = 6.dp, top = 2.dp).clip(RoundedCornerShape(4.dp)).background(colour).padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun ColourChip(colour: Color) {
    Box(Modifier.width(6.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(colour))
}

/** "Played 95 min · 4 sessions · last 3 days ago" for a game's play stats. */
fun playedLine(s: PlayStat): String {
    val last = s.lastPlayed?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().lowercase() }
    return listOfNotNull(
        "Played ${s.minutes} min",
        "${s.sessions} session" + if (s.sessions == 1) "" else "s",
        last?.let { "last $it" }
    ).joinToString(" · ")
}

/** The stem save files are matched on (`DeviceState.saveStems`): the file name without its extension, lower-cased. */
fun GameEntity.saveStem(): String = fileName.substringBeforeLast('.').lowercase()

/** While this tile or row has the D-pad focus the companion's hero panel shows [game] (see [Selection]). */
fun Modifier.heroOnFocus(game: GameEntity): Modifier = onFocusChanged { if (it.isFocused) Selection.set(game) }

/**
 * The time, 24-hour, recomposed once a minute on the minute boundary and never faster — the same budget as the companion's
 * play timer. The format comes from the device locale's 24-hour pattern.
 */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
        }
    }
    Text(now.format(CLOCK_FORMAT), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
