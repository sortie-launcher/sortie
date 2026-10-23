package io.github.esko997.sortie.data.folders

import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.manifest.FolderDef
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
        assertEquals(listOf("A", "B", "C"), l.map { it.label })
    }

    @Test
    fun `package rule feeds shortcuts into a folder only when apps is true`() {
        val rule = Json.parseToJsonElement("""{"package":{"eq":"com.droiddeck.launcher"}}""")
        val subjects = listOf(subj(game("Armored Core 3")), app("com.droiddeck.launcher", "DroidDeck"), shortcut(DROIDDECK, "7", "Gungriffon"))
        val off = RuleEngine.evaluate(FolderDef("dd", rule = rule), subjects)
        assertTrue(off.subjects.isEmpty())
        assertTrue(off.problems.isEmpty())
        val on = RuleEngine.evaluate(FolderDef("dd", rule = rule, apps = true), subjects)
        assertEquals(listOf("app:$DROIDDECK", "shortcut:$DROIDDECK/7"), on.subjects.map { it.id })
    }

    @Test
    fun `source matches shortcuts but not the publishing app`() {
        val r = rule("""{"source":{"eq":"com.droiddeck.launcher"}}""")
        assertTrue(r.matches(shortcut(DROIDDECK, "7", "Gungriffon")))
        assertFalse(r.matches(app(DROIDDECK, "DroidDeck")))
        assertFalse(r.matches(subj(game("Gungriffon"))))
        assertTrue(rule("""{"kind":{"eq":"shortcut"}}""").matches(shortcut(DROIDDECK, "7", "Gungriffon")))
    }

    @Test
    fun `game-only fields never match an app`() {
        val a = app("com.android.chrome", "Chrome")
        listOf(
            """{"fit":{"gte":0}}""",
            """{"system":{"regex":".*"}}""",
            """{"genre":{"regex":".*"}}""",
            """{"daysSinceAdded":{"gte":0}}""",
            """{"tags":{"hasAll":[]}}""",
            """{"hack":false}""",
            """{"handWritten":false}"""
        ).forEach { json ->
            val r = rule(json)
            assertFalse(json, r.matches(a))
            assertTrue(json, r.problems.isEmpty())
        }
        assertTrue(rule("""{"name":{"regex":"(?i)chrome"}}""").matches(a))
        assertTrue(rule("""{"favourite":true}""").matches(a.copy(favourite = true)))
        assertFalse(rule("""{"package":{"regex":".*"}}""").matches(subj(game("X"))))
    }

    @Test
    fun `include adds an app even without apps true, and fit sort puts apps last`() {
        val subjects = listOf(app("com.android.chrome", "Chrome"), subj(game("B", fit = 2)))
        val def = FolderDef("mix", rule = Json.parseToJsonElement("""{"fit":{"gte":1}}"""), include = listOf("app:com.android.chrome"), sort = "fit:desc")
        assertEquals(listOf("ps2/B.iso", "app:com.android.chrome"), RuleEngine.evaluate(def, subjects).subjects.map { it.id })
    }

    private fun app(pkg: String, label: String) = Subject("app:$pkg", Subject.Kind.APP, label, pkg = pkg, now = NOW)

    private fun shortcut(pkg: String, id: String, label: String) =
        Subject("shortcut:$pkg/$id", Subject.Kind.SHORTCUT, label, pkg = pkg, source = pkg, now = NOW)

    companion object {
        const val DAY = 86_400_000L
        const val NOW = 1_800_000_000_000L
        const val DROIDDECK = "com.droiddeck.launcher"
    }
}
