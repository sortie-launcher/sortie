package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.db.GameEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private fun game(title: String, system: String = "ps2", fit: Int = 3, tags: String = "", players: Int = 1, year: Int? = 2004, hack: Boolean = false) =
        GameEntity(
            id = "$system/$title.iso", systemId = system, folder = system, fileName = "$title.iso", path = "/x/$title.iso", size = 1, mtime = 0,
            title = title, sortTitle = title.lowercase(), desc = null, rating = null, year = year, developer = "FromSoftware", publisher = null,
            genre = "Action", players = "$players", playersMax = players, fit = fit, hook = null, flags = "", tags = tags, handWritten = false,
            cover = null, screenshot = null, marquee = null, placeholder = false, arriving = false, hack = hack, addedAt = NOW - DAY * 10
        )

    private fun subj(g: GameEntity, minutes: Int = 0, last: Long? = null, fav: Boolean = false) = Subject(g, minutes, last, fav, NOW)

    private fun rule(json: String) = RuleEngine(Json.parseToJsonElement(json))

    @Test
    fun `regex on name matches case-insensitively`() {
        val r = rule("""{"name":{"regex":"(?i)armored core"}}""")
        assertTrue(r.matches(subj(game("Armored Core 3"))))
        assertFalse(r.matches(subj(game("God Hand"))))
        assertTrue(r.problems.isEmpty())
    }

    @Test
    fun `all combinator with numeric predicates`() {
        val r = rule("""{"all":[{"fit":{"gte":4}},{"playMinutes":{"eq":0}}]}""")
        assertTrue(r.matches(subj(game("Trapt", fit = 5))))
        assertFalse(r.matches(subj(game("Trapt", fit = 5), minutes = 30)))
        assertFalse(r.matches(subj(game("SSX 3", fit = 2))))
    }

    @Test
    fun `any with tags has`() {
        val r = rule("""{"any":[{"tags":{"has":"mech"}},{"system":{"in":["ngp"]}}]}""")
        assertTrue(r.matches(subj(game("Gungriffon Blaze", tags = "mech,sim"))))
        assertTrue(r.matches(subj(game("Faselei!", system = "ngp"))))
        assertFalse(r.matches(subj(game("Culdcept"))))
    }

    @Test
    fun `favourite and hack booleans`() {
        assertTrue(rule("""{"favourite":true}""").matches(subj(game("X"), fav = true)))
        assertFalse(rule("""{"favourite":true}""").matches(subj(game("X"))))
        assertTrue(rule("""{"hack":false}""").matches(subj(game("X"))))
        assertTrue(rule("""{"not":{"hack":true}}""").matches(subj(game("X"))))
    }

    @Test
    fun `days since added and played`() {
        assertTrue(rule("""{"daysSinceAdded":{"lte":30}}""").matches(subj(game("New"))))
        assertFalse(rule("""{"daysSinceAdded":{"lte":5}}""").matches(subj(game("Old"))))
        assertTrue(rule("""{"daysSincePlayed":{"lte":1}}""").matches(subj(game("P"), last = NOW - DAY / 2)))
        assertFalse(rule("""{"daysSincePlayed":{"lte":1}}""").matches(subj(game("P")))) // never played -> no value -> false
    }

    @Test
    fun `bad rules report problems instead of throwing`() {
        val r = rule("""{"name":{"regex":"("},"colour":{"eq":"red"},"fit":{"between":[1,2]}}""")
        assertFalse(r.matches(subj(game("X"))))
        assertEquals(3, r.problems.size)
    }

    @Test
    fun `sort comparator orders by fit desc then title`() {
        val c = RuleEngine.comparator("fit:desc,title:asc")
        val l = listOf(subj(game("B", fit = 5)), subj(game("A", fit = 5)), subj(game("C", fit = 2))).sortedWith(c)
        assertEquals(listOf("A", "B", "C"), l.map { it.game.title })
    }

    companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_800_000_000_000L
    }
}
