package io.github.esko997.sortie.data.journal

import io.github.esko997.sortie.data.db.SessionEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** The Monday that starts [date]'s journal week. */
fun weekStartOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

/** Half-open span of epoch milliseconds, `[from, to)`. */
data class Window(val from: Long, val to: Long) {
    operator fun contains(t: Long): Boolean = t in from until to

    /** Milliseconds of `[start, end)` inside this window. */
    fun overlap(start: Long, end: Long): Long = (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)
}

/** One local calendar day: midnight to midnight in [zone], so 23 or 25 hours long on a daylight-saving change. */
fun dayWindow(day: LocalDate, zone: ZoneId): Window = Window(day.atStartOfDay(zone).toEpochMs(), day.plusDays(1).atStartOfDay(zone).toEpochMs())

/** Monday 00:00 to the next Monday 00:00 in [zone]. */
fun weekWindow(start: LocalDate, zone: ZoneId): Window = Window(start.atStartOfDay(zone).toEpochMs(), start.plusWeeks(1).atStartOfDay(zone).toEpochMs())

private fun java.time.ZonedDateTime.toEpochMs(): Long = toInstant().toEpochMilli()

/** How the journal may use a recorded session's time. Only [COUNTED] sessions add to any total. */
enum class SessionState {
    /** Ended while Sortie watched: its length is real. */
    COUNTED,

    /** Sortie never saw it end (process killed, device slept): when it stopped is unknown, so it adds nothing. */
    UNFINISHED,

    /** Still open: counted once it ends. */
    PLAYING,

    /** Recorded end before its start (the clock was changed): no usable length. */
    CLOCK_CHANGED
}

/** A part of a session drawn on one day's 24-hour track, as fractions of that day (a point when [from] == [to]). */
data class Span(val day: LocalDate, val from: Float, val to: Float, val state: SessionState)

data class WeekSession(
    val session: SessionEntity,
    val state: SessionState,
    /** The day it is listed under: the local day it started, or the week's Monday when it began the week before. */
    val day: LocalDate,
    val startedBeforeWeek: Boolean,
    /** Counted or playing and still going past the end of the week. */
    val endsAfterWeek: Boolean,
    /** Whole length of a [SessionState.COUNTED] session, else null (never estimated). */
    val durationMs: Long?,
    /** Counted time inside this week (0 unless [SessionState.COUNTED]). */
    val weekMs: Long,
    val spans: List<Span>
)

/** [countedMs] is the counted play that fell on this day, so a session past midnight adds to both days. */
data class DayPlay(val day: LocalDate, val countedMs: Long, val sessions: List<WeekSession>)

/** Per library item: counted time this week, sessions with and without a usable length. */
data class ItemPlay(val id: String, val countedMs: Long, val counted: Int, val uncounted: Int, val lastStart: Long)

data class WeekPlay(val start: LocalDate, val days: List<DayPlay>, val countedMs: Long, val items: List<ItemPlay>, val uncounted: Int) {
    val sessions: List<WeekSession> get() = days.flatMap { it.sessions }
}

/**
 * The week starting [start] (a Monday) from recorded [sessions], in [zone]. A counted session belongs to every week it
 * overlaps and adds only the part inside it; one running over midnight adds to each day its share. Unfinished and clock-changed
 * sessions belong to the week they started in and add nothing; an open one shows from its start until [now] and adds nothing.
 */
fun weekPlay(start: LocalDate, zone: ZoneId, sessions: List<SessionEntity>, now: Long): WeekPlay {
    val week = weekWindow(start, zone)
    val dayList = (0L until 7L).map { start.plusDays(it) }
    val dayWindows = dayList.map { dayWindow(it, zone) }
    val included = sessions.mapNotNull { s ->
        val state = when {
            s.endedAt == null -> SessionState.PLAYING
            s.abandoned -> SessionState.UNFINISHED
            s.endedAt < s.startedAt -> SessionState.CLOCK_CHANGED
            else -> SessionState.COUNTED
        }
        val begins = s.startedAt in week
        val end = when (state) {
            SessionState.COUNTED -> s.endedAt!!
            SessionState.PLAYING -> maxOf(now, s.startedAt)
            else -> s.startedAt
        }
        val belongs = when (state) {
            SessionState.COUNTED, SessionState.PLAYING -> begins || (s.startedAt < week.to && end > week.from)
            SessionState.UNFINISHED, SessionState.CLOCK_CHANGED -> begins
        }
        if (!belongs) return@mapNotNull null
        val before = s.startedAt < week.from
        val listedDay = if (before) start else Instant.ofEpochMilli(s.startedAt).atZone(zone).toLocalDate()
        val spans = if (state == SessionState.COUNTED || state == SessionState.PLAYING) {
            dayList.indices.mapNotNull { i ->
                val w = dayWindows[i]
                val length = (w.to - w.from).toFloat()
                when {
                    end > s.startedAt && w.overlap(s.startedAt, end) > 0 ->
                        Span(dayList[i], (maxOf(s.startedAt, w.from) - w.from) / length, (minOf(end, w.to) - w.from) / length, state)
                    end == s.startedAt && s.startedAt in w -> Span(dayList[i], (s.startedAt - w.from) / length, (s.startedAt - w.from) / length, state)
                    else -> null
                }
            }
        } else {
            val i = dayList.indexOf(listedDay)
            val w = dayWindows[i]
            val f = (s.startedAt - w.from) / (w.to - w.from).toFloat()
            listOf(Span(listedDay, f, f, state))
        }
        WeekSession(
            session = s,
            state = state,
            day = listedDay,
            startedBeforeWeek = before,
            endsAfterWeek = (state == SessionState.COUNTED || state == SessionState.PLAYING) && end > week.to,
            durationMs = if (state == SessionState.COUNTED) end - s.startedAt else null,
            weekMs = if (state == SessionState.COUNTED) week.overlap(s.startedAt, end) else 0,
            spans = spans
        )
    }
    val days = dayList.mapIndexed { i, day ->
        val w = dayWindows[i]
        DayPlay(
            day = day,
            countedMs = included.sumOf { if (it.state == SessionState.COUNTED) w.overlap(it.session.startedAt, it.session.endedAt!!) else 0L },
            sessions = included.filter { it.day == day }.sortedWith(compareBy({ it.session.startedAt }, { it.session.rowId }))
        )
    }
    val items = included.groupBy { it.session.gameId }.map { (id, list) ->
        ItemPlay(
            id = id,
            countedMs = list.sumOf { it.weekMs },
            counted = list.count { it.state == SessionState.COUNTED },
            uncounted = list.count { it.state != SessionState.COUNTED },
            lastStart = list.maxOf { it.session.startedAt }
        )
    }.sortedWith(compareByDescending<ItemPlay> { it.countedMs }.thenByDescending { it.lastStart })
    return WeekPlay(start, days, included.sumOf { it.weekMs }, items, included.count { it.state != SessionState.COUNTED })
}

/** Entries dated inside the week starting [start], by day, each day oldest-written first. */
fun entriesOfWeek(entries: List<JournalEntry>, start: LocalDate): Map<LocalDate, List<JournalEntry>> {
    val end = start.plusWeeks(1)
    return entries.mapNotNull { e -> e.localDate()?.takeIf { !it.isBefore(start) && it.isBefore(end) }?.let { it to e } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, list) -> list.sortedWith(compareBy({ it.createdAt }, { it.id })) }
}

/** Whole minutes in [ms], rounded down: "2 h 05 min", "45 min", "under a minute". */
fun formatPlayed(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0 -> "0 min"
        minutes == 0L -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h %02d min".format(minutes % 60)
    }
}
