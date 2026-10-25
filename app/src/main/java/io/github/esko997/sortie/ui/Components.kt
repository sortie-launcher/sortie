package io.github.esko997.sortie.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.index.cleanTitle
import io.github.esko997.sortie.data.manifest.GameAnnotation
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

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
