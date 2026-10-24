package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.manifest.UserData
import java.time.LocalDate
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProgressTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val ac3 = "ps2/Armored Core 3 (USA).chd"
    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct24 = LocalDate.of(2026, 10, 24)

    /** One status field: a switch moves the game between shelves (re-dated), a verdict edit keeps the date, clear drops the verdict too. */
    @Test
    fun shelvesAreExclusiveAndDatedByTheirLastMove() {
        val finished = emptyMap<String, GameProgress>().withShelf(ac3, Shelf.FINISHED, "  Best garage in the series. ", oct1)
        assertEquals(GameProgress("finished", "2026-10-01", "Best garage in the series."), finished[ac3])
        assertEquals(setOf(ac3), finished.idsOn(Shelf.FINISHED))
        assertTrue(finished.idsOn(Shelf.SHELVED).isEmpty())

        val edited = finished.withShelf(ac3, Shelf.FINISHED, "Best garage.", oct24)
        assertEquals(GameProgress("finished", "2026-10-01", "Best garage."), edited[ac3])

        val shelved = edited.withShelf(ac3, Shelf.SHELVED, "Best garage.", oct24)
        assertEquals(GameProgress("shelved", "2026-10-24", "Best garage."), shelved[ac3])
        assertTrue(shelved.idsOn(Shelf.FINISHED).isEmpty())
        assertEquals(setOf(ac3), shelved.idsOn(Shelf.SHELVED))

        assertTrue(shelved.withShelf(ac3, null, "ignored", oct24).isEmpty())
    }

    @Test
    fun refusesNonGamesAndOverLongVerdicts() {
        assertThrows(IllegalArgumentException::class.java) { emptyMap<String, GameProgress>().withShelf("app:org.ppsspp.ppsspp", Shelf.FINISHED, "", oct24) }
        assertThrows(IllegalArgumentException::class.java) {
            emptyMap<String, GameProgress>().withShelf(ac3, Shelf.FINISHED, "x".repeat(VERDICT_MAX_CHARS + 1), oct24)
        }
        val longest = emptyMap<String, GameProgress>().withShelf(ac3, Shelf.FINISHED, "x".repeat(VERDICT_MAX_CHARS), oct24)
        assertEquals(VERDICT_MAX_CHARS, longest[ac3]?.verdict?.length)
    }

    /** A user.json from before shelves decodes with none; a newer build's status or a partial entry costs nothing else in the file. */
    @Test
    fun oldAndNewerUserJsonDecode() {
        val old = json.decodeFromString(UserData.serializer(), """{"favourites":["a/b"],"wantToPlay":["a/c"]}""")
        assertTrue(old.gameProgress.isEmpty())
        assertEquals(setOf("a/c"), old.wantToPlay)

        val newer = json.decodeFromString(
            UserData.serializer(),
            """{"favourites":["a/b"],"gameProgress":{"a/b":{"status":"abandoned","since":"2026-10-24","mood":"meh"},"a/c":{}}}"""
        )
        assertEquals(setOf("a/b"), newer.favourites)
        assertNull(newer.gameProgress["a/b"]?.shelf)
        assertEquals("abandoned", newer.gameProgress["a/b"]?.status)
        assertTrue(Shelf.entries.all { newer.gameProgress.idsOn(it).isEmpty() })

        val data = UserData(gameProgress = mapOf(ac3 to GameProgress("finished", "2026-10-24", "Yes.")))
        assertEquals(data, json.decodeFromString(UserData.serializer(), json.encodeToString(UserData.serializer(), data)))
    }
}
