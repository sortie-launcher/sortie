package io.github.esko997.sortie.data.tonight

import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.PlayStat
import io.github.esko997.sortie.data.magazine.GamePresence
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TonightPickerTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val day = 86_400_000L

    private fun game(
        system: String,
        title: String,
        developer: String? = null,
        genre: String? = null,
        rating: Float? = null,
        hook: String? = null,
        placeholder: Boolean = false,
        arriving: Boolean = false
    ) = GameEntity(
        id = "$system/$title.bin", systemId = system, folder = system, fileName = "$title.bin", path = "/x/$system/$title.bin",
        size = if (placeholder) 0 else 1, mtime = 0, title = title, sortTitle = title.lowercase(), desc = null, rating = rating, year = null,
        developer = developer, publisher = null, genre = genre, players = "1", playersMax = 1, fit = 0, hook = hook, flags = "", tags = "",
        handWritten = hook != null, cover = null, screenshot = null, marquee = null, placeholder = placeholder, arriving = arriving,
        hack = false, addedAt = 0
    )

    private fun stat(g: GameEntity, minutes: Int, daysAgo: Int, sessions: Int = 1) =
        PlayStat(g.id, minutes, today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() - daysAgo * day, sessions)

    private fun input(
        games: List<GameEntity>,
        stats: List<PlayStat> = emptyList(),
        hidden: Set<String> = emptySet(),
        shelved: Set<String> = emptySet(),
        wantToPlay: Set<String> = emptySet(),
        bookmarks: Map<String, String> = emptyMap(),
        leads: List<ZineLead> = emptyList(),
        missing: Set<String> = emptySet()
    ) = TonightInput(
        games = games,
        stats = stats.associateBy { it.gameId },
        hidden = hidden,
        shelved = shelved,
        wantToPlay = wantToPlay,
        bookmarks = bookmarks,
        leads = leads,
        systemLabels = emptyMap(),
        zone = ZoneOffset.UTC,
        present = { it.id !in missing }
    )

    private val article = TonightArticle("field-notes-010", "salvage", "Salvage", "Field Notes 10")

    private val metroid = game("snes", "Super Metroid", developer = "Nintendo R&D1", genre = "Action")
    private val castlevania = game("snes", "Castlevania IV", developer = "Konami", genre = "Platform")
    private val fusion = game("gba", "Metroid Fusion", developer = "Nintendo R&D1", genre = "Action")
    private val radiant = game("saturn", "Radiant Silvergun", developer = "Treasure", genre = "Shooter", rating = 0.9f)
    private val ac3 = game("ps2", "Armored Core 3", developer = "FromSoftware", genre = "Action")
    private val tetris = game("gb", "Tetris", developer = "Nintendo", genre = "Puzzle")
    private val library = listOf(metroid, castlevania, fusion, radiant, ac3, tetris)

    /** Each role's rule, one distinct game each, in display order; the bookmark outranks a more recent session. */
    @Test
    fun eachRoleFollowsItsRuleWithDistinctGames() {
        val picks = pickTonight(
            today,
            input(
                library,
                stats = listOf(stat(metroid, 600, daysAgo = 10), stat(castlevania, 30, daysAgo = 1)),
                bookmarks = mapOf(metroid.id to "Maridia, need the grapple"),
                leads = listOf(ZineLead(ac3.id, article, finished = false))
            )
        )
        val byRole = picks.associateBy { it.roleOf }
        assertEquals(TonightRole.entries, picks.map { it.roleOf })
        assertEquals(picks.size, picks.map { it.gameId }.toSet().size)
        assertEquals(metroid.id, byRole.getValue(TonightRole.RESUME).gameId)
        assertEquals(fusion.id, byRole.getValue(TonightRole.FAMILIAR).gameId) // same developer as the most-played game
        assertEquals(ac3.id, byRole.getValue(TonightRole.FIELD_NOTES).gameId)
        assertEquals(article, byRole.getValue(TonightRole.FIELD_NOTES).article)
        // Radiant Silvergun (Saturn, Shooter) and Tetris (Game Boy, Puzzle) both miss the history; the metadata rating breaks the tie
        assertEquals(radiant.id, byRole.getValue(TonightRole.DIFFERENT).gameId)
        assertTrue(picks.all { it.fallback == null })
    }

    /** No history and no Field Notes article: every history/zine role is a labelled discovery, Want to play first. */
    @Test
    fun missingHistoryAndZinesBecomeLabelledDiscoveries() {
        val picks = pickTonight(today, input(library, wantToPlay = setOf(tetris.id)))
        val byRole = picks.associateBy { it.roleOf }
        for (role in listOf(TonightRole.RESUME, TonightRole.FAMILIAR, TonightRole.DIFFERENT, TonightRole.FIELD_NOTES)) {
            assertNotNull("$role is a labelled fallback", byRole.getValue(role).fallback)
            assertNull(byRole.getValue(role).article)
        }
        assertEquals(tetris.id, byRole.getValue(TonightRole.RESUME).gameId)
        assertNull(byRole.getValue(TonightRole.RANDOM).fallback)
        assertEquals(5, picks.map { it.gameId }.toSet().size)
    }

    /** Hidden, shelved, placeholder, arriving and missing files are left out at selection; a small library gets fewer picks. */
    @Test
    fun unplayableGamesAreNeverPickedAndSmallLibrariesGetFewer() {
        val hidden = game("snes", "Hidden")
        val shelved = game("snes", "Shelved")
        val placeholder = game("snes", "Placeholder", placeholder = true)
        val arriving = game("snes", "Arriving", arriving = true)
        val missing = game("snes", "Missing")
        val ok = game("snes", "Playable")
        val all = listOf(hidden, shelved, placeholder, arriving, missing, ok)
        val picks = pickTonight(
            today,
            input(
                all,
                stats = all.map { stat(it, 10, daysAgo = 1) },
                hidden = setOf(hidden.id),
                shelved = setOf(shelved.id),
                wantToPlay = all.map { it.id }.toSet(),
                leads = all.map { ZineLead(it.id, article, finished = false) },
                missing = setOf(missing.id)
            )
        )
        assertEquals(listOf(ok.id), picks.map { it.gameId })
    }

    @Test
    fun shedGamesStillInformFamiliarAndDifferentPicksWithoutBeingSelected() {
        val shed = metroid.copy(placeholder = true, size = 0)
        val picks = pickTonight(
            today,
            input(listOf(shed, fusion, radiant, ac3, tetris), stats = listOf(stat(shed, 120, daysAgo = 1)))
        )
        val familiar = picks.single { it.roleOf == TonightRole.FAMILIAR }
        assertEquals(fusion.id, familiar.gameId)
        assertNull(familiar.fallback)
        assertEquals(radiant.id, picks.single { it.roleOf == TonightRole.DIFFERENT }.gameId)
        assertTrue(picks.none { it.gameId == shed.id })
    }

    /** Two files of one game (regional dumps) never fill two roles. */
    @Test
    fun theSameTitleIsNotPickedTwice() {
        val usa = game("snes", "Chrono Trigger (USA)")
        val eur = game("snes", "Chrono Trigger (Europe)")
        val picks = pickTonight(today, input(listOf(usa, eur)))
        assertEquals(1, picks.size)
    }

    /** The same date and input always give the same set; other dates draw other random picks. */
    @Test
    fun aDateIsDeterministicAndDatesDiffer() {
        val many = (1..40).map { game("nes", "Game $it") }
        assertEquals(pickTonight(today, input(many)), pickTonight(today, input(many)))
        val draws = (0L until 10L).map { d -> pickTonight(today.plusDays(d), input(many)).single { it.roleOf == TonightRole.RANDOM }.gameId }
        assertTrue(draws.toSet().size > 1)
    }

    /** A saved pick whose game left the index, was hidden or lost its file stays in place, disabled with the reason. */
    @Test
    fun savedPicksThatBecameUnavailableAreKeptNotReplaced() {
        val saved = TonightDay(
            today.toString(),
            listOf(
                TonightPick("resume", metroid.id, "Super Metroid", "r"),
                TonightPick("familiar", fusion.id, "Metroid Fusion", "r"),
                TonightPick("different", radiant.id, "Radiant Silvergun", "r"),
                TonightPick("random", tetris.id, "Tetris", "r")
            )
        )
        val cards = tonightCards(
            saved,
            // Super Metroid left the index.
            games = listOf(fusion, radiant, tetris).associateBy { it.id },
            hidden = setOf(fusion.id),
            bookmarks = mapOf(tetris.id to "level 9"),
            presence = { if (it.id == radiant.id) GamePresence.MISSING else GamePresence.AVAILABLE }
        )
        assertEquals(saved.picks, cards.map { it.pick })
        assertEquals(listOf(false, false, false, true), cards.map { it.playable })
        assertNull(cards[0].game)
        assertEquals("level 9", cards[3].bookmark)
    }
}
