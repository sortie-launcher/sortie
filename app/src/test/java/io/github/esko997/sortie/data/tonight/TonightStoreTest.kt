package io.github.esko997.sortie.data.tonight

import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TonightStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val monday = LocalDate.of(2026, 10, 26)
    private fun picks(tag: String) = listOf(TonightPick("random", "nes/$tag.nes", tag, "draw"))
    private fun file() = File(tmp.root, "tonight.json")
    private val never: suspend () -> List<TonightPick> = { error("the saved set must be reused, not chosen again") }

    /** The first request of a date chooses and saves; later requests, also from a new process, get that set unchanged. */
    @Test
    fun aDateIsChosenOnceAndSurvivesARestart() = runBlocking {
        val first = TonightStore(file()).day(monday) { picks("a") }
        assertEquals(first, TonightStore(file()).day(monday, never))
        assertEquals(picks("a"), first.picks)
    }

    /** A new date chooses anew; the previous date stays for a visit opened before midnight, older ones are dropped. */
    @Test
    fun aNewDateRefreshesAndYesterdayStaysForAnOpenVisit() = runBlocking {
        val store = TonightStore(file())
        store.day(monday) { picks("mon") }
        assertEquals(picks("tue"), store.day(monday.plusDays(1)) { picks("tue") }.picks)
        assertEquals(picks("mon"), TonightStore(file()).day(monday, never).picks)
        store.day(monday.plusDays(2)) { picks("wed") }
        assertEquals(picks("again"), TonightStore(file()).day(monday) { picks("again") }.picks)
    }

    /** An unreadable file costs only the saved sets: the date is chosen again and saved over it. */
    @Test
    fun anUnreadableFileIsChosenAgain() = runBlocking {
        file().writeText("{ not json")
        assertEquals(picks("b"), TonightStore(file()).day(monday) { picks("b") }.picks)
        assertEquals(picks("b"), TonightStore(file()).day(monday, never).picks)
    }

    /** Nothing playable at the first visit (before the first index, say) is not a choice: a later visit that day chooses. */
    @Test
    fun anEmptySetIsNotSaved() = runBlocking {
        val store = TonightStore(file())
        assertEquals(emptyList<TonightPick>(), store.day(monday) { emptyList() }.picks)
        assertEquals(picks("c"), store.day(monday) { picks("c") }.picks)
    }

    @Test
    fun failedPersistenceIsReportedAndDoesNotCacheAnUnsavedDay() = runBlocking {
        val blockedParent = tmp.newFile("blocked")
        val target = File(blockedParent, "tonight.json")
        val store = TonightStore(target)
        try {
            store.day(monday) { picks("unsaved") }
            fail("an unsaved day must not be presented as persisted")
        } catch (_: IOException) {
            // Make the same storage location writable, then retry through the same store.
        }
        check(blockedParent.delete())
        check(blockedParent.mkdir())
        val saved = store.day(monday) { picks("saved") }
        assertEquals(picks("saved"), saved.picks)
        assertEquals(saved, TonightStore(target).day(monday, never))
    }
}
