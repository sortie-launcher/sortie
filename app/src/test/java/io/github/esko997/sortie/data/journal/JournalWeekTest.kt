package io.github.esko997.sortie.data.journal

import io.github.esko997.sortie.data.db.SessionEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalWeekTest {
    private val zone = ZoneId.of("Europe/London")
    private val monday = LocalDate.of(2026, 10, 19)
    private val min = 60_000L

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, end: Long?, abandoned: Boolean = false, id: String = "ps2/game.iso", row: Long = 1) =
        SessionEntity(rowId = row, gameId = id, profileId = null, startedAt = start, endedAt = end, abandoned = abandoned)

    @Test
    fun weeksStartOnMonday() {
        assertEquals(monday, weekStartOf(monday))
        assertEquals(monday, weekStartOf(LocalDate.of(2026, 10, 25))) // Sunday
        assertEquals(LocalDate.of(2026, 10, 26), weekStartOf(LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun sessionOverMidnightSplitsBetweenDays() {
        val s = session(at(2026, 10, 21, 23, 30), at(2026, 10, 22, 1, 0))
        val week = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 25, 12))
        assertEquals(90 * min, week.countedMs)
        assertEquals(30 * min, week.days[2].countedMs) // Wednesday
        assertEquals(60 * min, week.days[3].countedMs) // Thursday
        assertEquals(listOf(s), week.days[2].sessions.map { it.session }) // listed once, under its start day
        assertTrue(week.days[3].sessions.isEmpty())
        assertEquals(2, week.sessions.single().spans.size)
    }

    @Test
    fun sessionOverSundayMidnightCountsInBothWeeksOnlyItsOwnPart() {
        val s = session(at(2026, 10, 25, 23, 0), at(2026, 10, 26, 0, 45))
        val first = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 27, 12))
        val second = weekPlay(monday.plusWeeks(1), zone, listOf(s), now = at(2026, 10, 27, 12))
        assertEquals(60 * min, first.countedMs)
        assertEquals(45 * min, second.countedMs)
        assertEquals(105 * min, second.sessions.single().durationMs)
        assertTrue(second.sessions.single().startedBeforeWeek)
        assertEquals(monday.plusWeeks(1), second.sessions.single().day)
        assertTrue(first.sessions.single().endsAfterWeek)
        assertEquals(45 * min, second.items.single().countedMs)
    }

    @Test
    fun unfinishedSessionIsListedInItsStartWeekAndAddsNothing() {
        // Sortie closed it as abandoned two days later, in the next week; that close time is not a real end
        val s = session(at(2026, 10, 24, 20, 0), at(2026, 10, 27, 9, 0), abandoned = true)
        val week = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 28, 12))
        val listed = week.sessions.single()
        assertEquals(SessionState.UNFINISHED, listed.state)
        assertNull(listed.durationMs)
        assertEquals(0L, week.countedMs)
        assertEquals(1, week.uncounted)
        assertEquals(LocalDate.of(2026, 10, 24), listed.day)
        assertTrue(weekPlay(monday.plusWeeks(1), zone, listOf(s), now = at(2026, 10, 28, 12)).sessions.isEmpty())
    }

    @Test
    fun openSessionShowsUntilNowWithoutCounting() {
        val s = session(at(2026, 10, 18, 22, 0), null) // started the Sunday before
        val week = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 19, 1, 0))
        val listed = week.sessions.single()
        assertEquals(SessionState.PLAYING, listed.state)
        assertEquals(0L, week.countedMs)
        assertEquals(1, week.items.single().uncounted)
        assertTrue(listed.startedBeforeWeek)
        val span = listed.spans.single()
        assertEquals(monday, span.day)
        assertEquals(0f, span.from, 0f)
        assertEquals(1f / 24, span.to, 1e-4f)
    }

    @Test
    fun clockChangeDuringPlayAddsNothing() {
        val s = session(at(2026, 10, 20, 12, 0), at(2026, 10, 20, 11, 0))
        val week = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 25, 12))
        assertEquals(SessionState.CLOCK_CHANGED, week.sessions.single().state)
        assertEquals(0L, week.countedMs)
    }

    @Test
    fun daylightSavingWeekCountsElapsedTime() {
        // clocks went back at 02:00 BST on Sunday 25 Oct 2026: 00:30 to 03:30 local is four real hours
        val s = session(at(2026, 10, 25, 0, 30), at(2026, 10, 25, 3, 30))
        val week = weekPlay(monday, zone, listOf(s), now = at(2026, 10, 26, 12))
        assertEquals(240 * min, week.countedMs)
        assertEquals(240 * min, week.days[6].countedMs)
        assertEquals(25 * 60 * min, dayWindow(LocalDate.of(2026, 10, 25), zone).let { it.to - it.from })
        assertEquals((7 * 24 + 1) * 60 * min, weekWindow(monday, zone).let { it.to - it.from })
    }

    @Test
    fun sessionsOfOtherWeeksAreLeftOut() {
        val before = session(at(2026, 10, 12, 20, 0), at(2026, 10, 12, 21, 0), row = 1)
        val after = session(at(2026, 10, 26, 0, 0), at(2026, 10, 26, 1, 0), row = 2)
        val week = weekPlay(monday, zone, listOf(before, after), now = at(2026, 10, 27, 12))
        assertTrue(week.sessions.isEmpty())
        assertFalse(week.days.any { it.countedMs > 0 })
    }

    @Test
    fun entriesFiledByTheirDateInsideTheWeek() {
        val inWeek = JournalEntry(id = "a", date = "2026-10-25", createdAt = 2)
        val earlier = JournalEntry(id = "b", date = "2026-10-25", createdAt = 1)
        val nextWeek = JournalEntry(id = "c", date = "2026-10-26")
        val broken = JournalEntry(id = "d", date = "yesterday")
        val filed = entriesOfWeek(listOf(inWeek, nextWeek, broken, earlier), monday)
        assertEquals(listOf("b", "a"), filed[LocalDate.of(2026, 10, 25)]?.map { it.id })
        assertEquals(1, filed.size)
        assertNull(broken.localDate())
    }
}
