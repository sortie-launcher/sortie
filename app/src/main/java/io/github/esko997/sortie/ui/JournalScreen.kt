package io.github.esko997.sortie.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.esko997.sortie.SortieApp
import io.github.esko997.sortie.data.apps.LibraryItem
import io.github.esko997.sortie.data.db.byIds
import io.github.esko997.sortie.data.journal.ItemPlay
import io.github.esko997.sortie.data.journal.JournalEntry
import io.github.esko997.sortie.data.journal.JournalStore
import io.github.esko997.sortie.data.journal.SessionState
import io.github.esko997.sortie.data.journal.Span
import io.github.esko997.sortie.data.journal.WeekPlay
import io.github.esko997.sortie.data.journal.WeekSession
import io.github.esko997.sortie.data.journal.entriesOfWeek
import io.github.esko997.sortie.data.journal.formatPlayed
import io.github.esko997.sortie.data.journal.weekPlay
import io.github.esko997.sortie.data.journal.weekStartOf
import io.github.esko997.sortie.data.journal.weekWindow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

internal val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")
internal val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
private val DAY_HEADING: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val SHORT_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")

/** Open session on the day tracks; counted play is [Amber], an unseen end is a grey tick. */
private val Playing = Color(0xFF7DD3FC)
private val Unseen = Color(0xFF9AA3AD)

/** Titles for [ids]: indexed games by their clean title, apps and shortcuts by label, anything else by its file name. */
internal suspend fun resolveTitles(graph: SortieApp.Graph, ids: Set<String>): Map<String, ItemTitle> {
    if (ids.isEmpty()) return emptyMap()
    val games = graph.db.games().byIds(ids.filter { LibraryItem.isGameId(it) }).associate { it.id to displayTitle(it) }
    val apps = graph.apps.apps.value.associate { it.id to it.label }
    val shortcuts = graph.shortcuts.shortcuts.value.associate { it.id to it.label }
    return ids.associateWith { id ->
        games[id]?.let { ItemTitle(it, indexed = true) }
            ?: (apps[id] ?: shortcuts[id])?.let { ItemTitle(it, indexed = false) }
            ?: ItemTitle(id.substringAfterLast('/').substringBeforeLast('.').ifBlank { id }, indexed = false)
    }
}

/**
 * The weekly journal: a Monday-to-Sunday week of recorded play sessions (read live from the play history) next to the user's own
 * dated notes with their screenshots. L1/R1 or the buttons move a week; any week, played or not, takes notes. Only sessions
 * Sortie saw end add time; unfinished and running ones are listed and marked, never given an estimated length.
 * [onGame] opens a game's detail page (its notes and controls live there; the journal only quotes the current "where was I?").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(graph: SortieApp.Graph, onGame: (String) -> Unit) {
    val store = remember(graph) { JournalStore(graph.user, graph.sortieDir) }
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val thisWeek = weekStartOf(today)
    var weekEpochDay by rememberSaveable { mutableLongStateOf(thisWeek.toEpochDay()) }
    val week = LocalDate.ofEpochDay(weekEpochDay)
    val window = remember(week, zone) { weekWindow(week, zone) }
    val sessions by remember(window) { graph.db.games().sessionsTouching(window.from, window.to) }.collectAsState(initial = null)
    val user by graph.user.data.collectAsState()
    val writeError by graph.user.writeError.collectAsState()
    val apps by graph.apps.apps.collectAsState()
    val shortcuts by graph.shortcuts.shortcuts.collectAsState()
    val hasOpen = sessions?.any { it.endedAt == null } == true
    // a running session's bar grows by the minute, and only while the journal is in front on a lit screen: a journal stopped
    // behind the game it is recording wakes for nothing ([rememberShown] follows lifecycle and display events, not frames)
    val shown = rememberShown()
    val now by produceState(System.currentTimeMillis(), hasOpen, shown) {
        value = System.currentTimeMillis()
        if (!hasOpen) return@produceState
        shown.collectLatest { visible ->
            while (visible) {
                value = System.currentTimeMillis()
                delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
            }
        }
    }
    val play = remember(sessions, week, now) { sessions?.let { weekPlay(week, zone, it, now) } }
    val entries = remember(user.journalEntries, week) { entriesOfWeek(user.journalEntries, week) }
    val undated = remember(user.journalEntries) { user.journalEntries.filter { it.localDate() == null } }
    val noted = remember(user.journalEntries) { user.journalEntries.mapNotNullTo(HashSet()) { e -> e.sessionStartedAt?.let { e.gameId to it } } }
    val ids = remember(play, entries, undated) {
        buildSet {
            play?.items?.forEach { add(it.id) }
            entries.values.flatten().forEach { e -> e.gameId?.let(::add) }
            undated.forEach { e -> e.gameId?.let(::add) }
        }
    }
    val titles by produceState(emptyMap<String, ItemTitle>(), ids, apps, shortcuts) { value = resolveTitles(graph, ids) }
    val titleOf = { id: String -> titles[id]?.title ?: id.substringAfterLast('/') }

    var editing by rememberSaveable(stateSaver = NullableEntrySaver) { mutableStateOf<JournalEntry?>(null) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) } // "<entry id>/<index>"
    // hoisted above the editor/viewer early returns so closing them keeps the list where it was
    val listState = rememberLazyListState()
    var focusedOnce by rememberSaveable { mutableStateOf(false) }
    val newEntry = { day: LocalDate, gameId: String?, session: Long? ->
        val t = System.currentTimeMillis()
        editing = JournalEntry(UUID.randomUUID().toString(), day.toString(), gameId = gameId, sessionStartedAt = session, createdAt = t, updatedAt = t)
    }

    // weeks are unbounded both ways, like the editor's dates, so a note dated ahead stays reachable
    val hotkeys = LocalHotkeys.current
    LaunchedEffect(hotkeys) {
        hotkeys.events.collect { key ->
            if (editing != null || viewing != null) return@collect
            when (key) {
                Hotkeys.Key.PREV_FOLDER -> weekEpochDay -= 7
                Hotkeys.Key.NEXT_FOLDER -> weekEpochDay += 7
                else -> Unit
            }
        }
    }

    editing?.let { seed ->
        JournalEditor(
            seed = seed,
            saved = user.journalEntries.firstOrNull { it.id == seed.id },
            store = store,
            graph = graph,
            weekItems = play?.items?.map { it.id }.orEmpty(),
            zone = zone,
            onClose = { saved ->
                editing = null
                // a re-dated note may now live in another week: show it there
                saved?.localDate()?.let { weekEpochDay = weekStartOf(it).toEpochDay() }
            }
        )
        return
    }
    viewing?.let { v ->
        val entry = user.journalEntries.firstOrNull { it.id == v.substringBefore('/') }
        JournalViewer(entry?.attachments.orEmpty(), v.substringAfter('/').toIntOrNull() ?: 0, store.media, onClose = { viewing = null })
        return
    }

    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    LaunchedEffect(Unit) {
        // after the editor or viewer closes the list keeps its scroll position; focus jumps back to "New note" only when it is in view
        if (focusedOnce && listState.firstVisibleItemIndex > 1) return@LaunchedEffect
        focusedOnce = true
        repeat(20) {
            @OptIn(ExperimentalComposeUiApi::class)
            inputMode.requestInputMode(InputMode.Keyboard)
            if (runCatching { primary.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 8.dp)) {
                    Text("Journal", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        weekTitle(week, thisWeek),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (week == thisWeek) Amber else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        when {
                            play == null -> "Reading play history…"
                            play.sessions.isEmpty() -> "No play recorded this week."
                            else -> "${formatPlayed(play.countedMs)} played in ${countLabel(play.sessions.size, "session")}" +
                                if (play.uncounted > 0) "; ${play.uncounted} without a known length add no time" else ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            item(key = "nav") {
                FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("New note", Modifier.focusRequester(primary)) { newEntry(if (week == thisWeek) today else week, null, null) }
                    GlowButton("Previous week") { weekEpochDay -= 7 }
                    GlowButton("Next week") { weekEpochDay += 7 }
                    GlowButton("This week", enabled = week != thisWeek) { weekEpochDay = thisWeek.toEpochDay() }
                }
            }
            writeError?.let { item(key = "write-error") { Notice("Preferences are not being saved: $it", error = true) } }
            play?.let { p ->
                item(key = "ribbon") {
                    FocusRow(onClick = {}) { WeekRibbon(p, today) }
                }
            }
            for (d in 0L until 7L) {
                val day = week.plusDays(d)
                val dayPlay = play?.days?.getOrNull(d.toInt())
                val dayEntries = entries[day].orEmpty()
                item(key = "day-$day") {
                    Column {
                        // a hairline between days, so a week of sessions and notes reads as seven sections
                        Box(
                            Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp).fillMaxWidth().height(1.dp)
                                .background(if (d == 0L) Color.Transparent else Hairline)
                        )
                        FocusRow(onClick = { newEntry(day, null, null) }) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        day.format(DAY_HEADING) + if (day == today) " (today)" else "",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (day == today) Amber else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if ((dayPlay?.countedMs ?: 0L) > 0L) {
                                        Text(formatPlayed(dayPlay!!.countedMs), style = MaterialTheme.typography.titleSmall, color = Amber)
                                    }
                                }
                                Text(
                                    if (day.isAfter(today)) "A: write a note for this day" else "A: add a note for this day",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(dayPlay?.sessions.orEmpty(), key = { "s-${it.session.rowId}" }) { ws ->
                    SessionRow(ws, titleOf(ws.session.gameId), zone, hasNote = (ws.session.gameId to ws.session.startedAt) in noted) {
                        val started = Instant.ofEpochMilli(ws.session.startedAt).atZone(zone).toLocalDate()
                        newEntry(started, ws.session.gameId, ws.session.startedAt)
                    }
                }
                dayEntries.forEach { e -> entryItems(e, titles, zone, store, onEdit = { editing = e }, onView = { i -> viewing = "${e.id}/$i" }) }
            }
            if (play != null && play.items.isNotEmpty()) {
                item(key = "games-head") { SectionTitle("Played this week") }
                items(play.items, key = { "g-${it.id}" }) { item ->
                    ItemRow(item, titles[item.id], user.gameAnnotations[item.id]?.returnNote.orEmpty(), onGame)
                }
            }
            if (undated.isNotEmpty()) {
                item(key = "undated-head") { SectionTitle("Notes with an unreadable date") }
                item(key = "undated-note") { Notice("These notes' dates could not be read. Open one and pick its day to file it in a week.") }
                undated.forEach { e -> entryItems(e, titles, zone, store, onEdit = { editing = e }, onView = { i -> viewing = "${e.id}/$i" }) }
            }
        }
        HintBar("A" to "choose", "L1 / R1" to "previous / next week", "B" to "back")
    }
}

/** One note (A edits it) and, below it, its screenshots as separately focusable thumbnails. */
@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.entryItems(
    e: JournalEntry,
    titles: Map<String, ItemTitle>,
    zone: ZoneId,
    store: JournalStore,
    onEdit: () -> Unit,
    onView: (Int) -> Unit
) {
    // no item keys: a hand-edited user.json could repeat an id, and duplicate keys would crash the list
    item {
        FocusRow(onClick = onEdit, modifier = Modifier.padding(start = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                e.gameId?.let { id ->
                    Text(
                        "About " + (titles[id]?.title ?: id.substringAfterLast('/')) +
                            (e.sessionStartedAt?.let { ", the session started ${Instant.ofEpochMilli(it).atZone(zone).format(DAY_TIME)}" } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                        color = Amber
                    )
                }
                if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        "Note",
                        e.attachments.size.takeIf { it > 0 }?.let { countLabel(it, "screenshot") },
                        "A: edit"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (e.attachments.isNotEmpty()) {
        item {
            FlowRow(
                Modifier.padding(start = 24.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                e.attachments.forEachIndexed { i, a -> AttachmentThumb(a, store.media, onOpen = { onView(i) }) }
            }
        }
    }
}

@Composable
private fun SessionRow(ws: WeekSession, title: String, zone: ZoneId, hasNote: Boolean, onWrite: () -> Unit) {
    FocusRow(onClick = onWrite, modifier = Modifier.padding(start = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(6.dp).height(36.dp)) {
                drawRoundRect(stateColour(ws.state), cornerRadius = CornerRadius(3.dp.toPx()))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(sessionLine(ws, zone), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (hasNote) "Has a note · A: write another" else "A: write about this session",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A played item's week total, its current "where was I?" note (read-only, quoted live) and, for indexed games, a way to its page. */
@Composable
private fun ItemRow(item: ItemPlay, title: ItemTitle?, returnNote: String, onGame: (String) -> Unit) {
    val opens = title?.indexed == true
    FocusRow(onClick = { if (opens) onGame(item.id) }) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title?.title ?: item.id.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                buildString {
                    append(formatPlayed(item.countedMs))
                    append(" in ").append(countLabel(item.counted, "counted session"))
                    if (item.uncounted > 0) append("; ").append(countLabel(item.uncounted, "session")).append(" without a known length")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (returnNote.isNotBlank()) {
                Text("Where was I? (the game's current note)", style = MaterialTheme.typography.labelMedium, color = Amber)
                Text(returnNote, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                when {
                    opens -> "A: open the game's page"
                    LibraryItem.isGameId(item.id) -> "Not in this device's library"
                    else -> "App"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The week at a glance: one 24-hour track per day, midnight to midnight. Counted play is drawn where it happened (a session past
 * midnight continues on the next day's track), a running session up to now, a session whose end Sortie never saw as a tick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekRibbon(play: WeekPlay, today: LocalDate) {
    val spans = play.sessions.flatMap { it.spans }.groupBy { it.day }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        play.days.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.day.format(SHORT_DAY),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (d.day == today) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(64.dp)
                )
                DayTrack(spans[d.day].orEmpty(), Modifier.weight(1f).height(14.dp))
                Text(
                    if (d.countedMs > 0) formatPlayed(d.countedMs) else "",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(112.dp)
                )
            }
        }
        Row(Modifier.padding(start = 64.dp, end = 112.dp)) {
            listOf("00:00", "06:00", "12:00", "18:00").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Legend(Amber, "counted play")
            Legend(Playing, "playing now")
            Legend(Unseen, "end not seen, adds no time")
        }
    }
}

@Composable
private fun DayTrack(spans: List<Span>, modifier: Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(Slate, cornerRadius = r)
        for (q in 1..3) {
            val x = size.width * q / 4
            drawLine(Color(0x33FFFFFF), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        val minW = 2.dp.toPx()
        spans.forEach { s ->
            val x0 = s.from * size.width
            val x1 = s.to * size.width
            if (s.state == SessionState.COUNTED || s.state == SessionState.PLAYING) {
                drawRoundRect(stateColour(s.state), topLeft = Offset(x0, 0f), size = Size(maxOf(x1 - x0, minW), size.height), cornerRadius = r)
            } else {
                drawRect(Unseen, topLeft = Offset(x0 - minW / 2, 0f), size = Size(minW, size.height))
            }
        }
    }
}

@Composable
private fun Legend(colour: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(8.dp)) { drawRoundRect(colour, cornerRadius = CornerRadius(2.dp.toPx())) }
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

private fun stateColour(state: SessionState): Color = when (state) {
    SessionState.COUNTED -> Amber
    SessionState.PLAYING -> Playing
    SessionState.UNFINISHED, SessionState.CLOCK_CHANGED -> Unseen
}

/** Times and length of one session as recorded; nothing is estimated for a session without a known end. */
private fun sessionLine(ws: WeekSession, zone: ZoneId): String {
    val s = ws.session
    val start = Instant.ofEpochMilli(s.startedAt).atZone(zone)
    val startText = start.format(if (ws.startedBeforeWeek) WEEKDAY_CLOCK else CLOCK)
    return when (ws.state) {
        SessionState.COUNTED -> {
            val end = Instant.ofEpochMilli(s.endedAt!!).atZone(zone)
            val endText = end.format(if (end.toLocalDate() != start.toLocalDate()) WEEKDAY_CLOCK else CLOCK)
            val whole = ws.durationMs ?: 0L
            "$startText–$endText, ${formatPlayed(whole)}" + if (ws.weekMs != whole) " (${formatPlayed(ws.weekMs)} of it in this week)" else ""
        }
        SessionState.PLAYING -> "Started $startText; still playing, counted once it ends"
        SessionState.UNFINISHED -> "Started $startText; Sortie didn't see it end, so it adds no time"
        SessionState.CLOCK_CHANGED -> "Started $startText; the clock changed during play, so it adds no time"
    }
}

private fun weekTitle(week: LocalDate, thisWeek: LocalDate): String {
    val end = week.plusDays(6)
    val range = when {
        week.year != end.year -> "${week.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))} – ${end.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}"
        week.month != end.month -> "${week.format(DateTimeFormatter.ofPattern("d MMMM"))} – ${end.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}"
        else -> "${week.dayOfMonth} – ${end.format(DateTimeFormatter.ofPattern("d MMMM yyyy"))}"
    }
    return when (week) {
        thisWeek -> "This week, $range"
        thisWeek.minusWeeks(1) -> "Last week, $range"
        thisWeek.plusWeeks(1) -> "Next week, $range"
        else -> range
    }
}

private fun countLabel(n: Int, noun: String): String = "$n $noun" + if (n == 1) "" else "s"
