package io.github.esko997.sortie.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs [LibrarySearch]'s statements against a real SQLite with the schema Room exported for the current database version, so
 * the filter semantics (OR within a dimension, AND across, exact labels, limit after filtering) are checked as executed SQL.
 */
class LibrarySearchTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val latest = File("schemas/io.github.esko997.sortie.data.db.SortieDb").listFiles { f -> f.extension == "json" }!!
            .maxBy { it.nameWithoutExtension.toInt() }
        val entities = Json.parseToJsonElement(latest.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        db.createStatement().use { st ->
            for (e in entities) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                val statements = listOf(e.jsonObject["createSql"]!!) + e.jsonObject["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!! }
                statements.forEach { st.execute(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
        }
    }

    @After
    fun close() = db.close()

    private fun game(
        id: String,
        title: String = id.substringAfter('/').substringBeforeLast('.'),
        fit: Int = 3,
        tags: String = "",
        flags: String = "",
        genre: String? = null,
        desc: String? = null,
        developer: String? = null,
        placeholder: Boolean = false,
        variantOf: String? = null
    ) {
        db.prepareStatement(
            "INSERT INTO games (id, systemId, folder, fileName, path, size, mtime, title, sortTitle, `desc`, developer, genre, " +
                "playersMax, fit, flags, tags, handWritten, placeholder, arriving, hack, addedAt, variantOf) " +
                "VALUES (?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, 1, ?, ?, ?, 0, ?, 0, 0, 0, ?)"
        ).use { st ->
            val system = id.substringBefore('/')
            listOf(
                id, system, system, id.substringAfter('/'), "/card/$id", title, title.lowercase(), desc, developer, genre,
                fit, flags, tags, if (placeholder) 1 else 0, variantOf
            ).forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun session(gameId: String, abandoned: Boolean = false) {
        db.prepareStatement("INSERT INTO sessions (gameId, profileId, startedAt, endedAt, abandoned) VALUES (?, NULL, 0, NULL, ?)").use {
            it.setString(1, gameId)
            it.setInt(2, if (abandoned) 1 else 0)
            it.executeUpdate()
        }
    }

    private fun select(q: SqlQuery, read: (java.sql.ResultSet) -> Unit) = db.prepareStatement(q.sql).use { st ->
        q.args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
        st.executeQuery().use { rs -> while (rs.next()) read(rs) }
    }

    private fun ids(f: SearchFilters, favourites: Set<String> = emptySet(), queue: Set<String> = emptySet(), limit: Int = 200): List<String> =
        mutableListOf<String>().also { out -> select(LibrarySearch.page(f, favourites, queue, limit)) { out += it.getString("id") } }

    private fun count(f: SearchFilters, favourites: Set<String> = emptySet(), queue: Set<String> = emptySet()): Int {
        var n = -1
        select(LibrarySearch.count(f, favourites, queue)) { n = it.getInt(1) }
        return n
    }

    @Test
    fun filtersAloneFindGamesWithoutText() {
        game("ps2/Armored Core 3.iso", tags = "robot,mech,customise")
        game("ps2/Katamari Damacy.iso", tags = "puzzle")
        game("psx/Front Mission 3.bin", tags = "mech,strategy")
        val f = SearchFilters(systems = setOf("ps2"), tags = setOf("mech"))
        assertTrue(f.hasCriteria)
        assertEquals(listOf("ps2/Armored Core 3.iso"), ids(f))
        assertEquals(1, count(f))
    }

    @Test
    fun valuesWithinADimensionOrAndDimensionsAnd() {
        game("ps2/Armored Core 3.iso", tags = "mech", genre = "Action")
        game("psp/Armored Core Formula Front.iso", tags = "mech,strategy", genre = "Strategy")
        game("psp/Gundam vs Gundam.iso", tags = "mech", genre = "Action")
        game("psx/Ring of Red.bin", tags = "mech", genre = "Strategy")
        game("ps2/Tenchu Wrath of Heaven.iso", tags = "ninja,stealth", genre = "Action")
        // two systems: either
        assertEquals(4, count(SearchFilters(systems = setOf("ps2", "psp"))))
        // two tags: either; with the systems: both dimensions
        assertEquals(
            setOf("ps2/Armored Core 3.iso", "psp/Armored Core Formula Front.iso", "psp/Gundam vs Gundam.iso", "ps2/Tenchu Wrath of Heaven.iso"),
            ids(SearchFilters(systems = setOf("ps2", "psp"), tags = setOf("mech", "stealth"))).toSet()
        )
        // and a genre on top
        assertEquals(
            setOf("ps2/Armored Core 3.iso", "psp/Gundam vs Gundam.iso", "ps2/Tenchu Wrath of Heaven.iso"),
            ids(SearchFilters(systems = setOf("ps2", "psp"), tags = setOf("mech", "stealth"), genres = setOf("Action"))).toSet()
        )
        // text narrows the filters further
        assertEquals(listOf("psp/Gundam vs Gundam.iso"), ids(SearchFilters(text = " gundam ", tags = setOf("mech"), genres = setOf("Action"))))
    }

    @Test
    fun labelsMatchWholeEntriesInTagsOrFlags() {
        game("ps2/Armored Core 3.iso", tags = "robot,mech")
        game("snes/Mechanic Sim.sfc", tags = "mechanic,robot-mech")
        game("gba/Pokemon Hack.gba", tags = "rpg", flags = "hack,pokemon")
        game("gba/Hacker Story.gba", tags = "story", flags = "hacker")
        assertEquals(listOf("ps2/Armored Core 3.iso"), ids(SearchFilters(tags = setOf("mech"))))
        // a flag-only badge is selectable; a label in a substring of another flag is not hit
        assertEquals(listOf("gba/Pokemon Hack.gba"), ids(SearchFilters(tags = setOf("hack"))))
        // labels OR across both columns
        assertEquals(2, count(SearchFilters(tags = setOf("hack", "mech"))))
        // selections are lower-cased like the indexed columns
        assertEquals(1, count(SearchFilters(tags = setOf("Hack"))))
    }

    @Test
    fun countAndLimitApplyAfterEveryFilter() {
        // higher-fit non-matching games sort first; they must not take page slots or hide matches
        repeat(300) { game("ps2/Other $it.iso", fit = 5, tags = "sports") }
        repeat(250) { game("ps2/Mech $it.iso", fit = 3, tags = "mech") }
        val f = SearchFilters(tags = setOf("mech"))
        val page = ids(f, limit = 200)
        assertEquals(200, page.size)
        assertTrue(page.all { it.startsWith("ps2/Mech ") })
        assertEquals(250, count(f))
        assertEquals(250, ids(f, limit = 400).size)
    }

    @Test
    fun onDeviceIsTheDefaultAndVariantsNeverShow() {
        game("ps2/Shown.iso", tags = "mech")
        game("ps2/Placeholder.iso", tags = "mech", placeholder = true)
        game("ps2/Shown (Disc 2).iso", tags = "mech", variantOf = "ps2/Shown.iso")
        assertEquals(listOf("ps2/Shown.iso"), ids(SearchFilters(tags = setOf("mech"))))
        assertEquals(setOf("ps2/Shown.iso", "ps2/Placeholder.iso"), ids(SearchFilters(tags = setOf("mech"), onDeviceOnly = false)).toSet())
        assertFalse("on device alone is not a question", SearchFilters().hasCriteria)
        assertFalse(SearchFilters().hasFilters)
        assertTrue(SearchFilters(onDeviceOnly = false).hasFilters)
        assertTrue(SearchFilters(onDeviceOnly = false).hasCriteria)
    }

    @Test
    fun favouritesAndQueueMatchExactIdsAndEmptySetsMatchNothing() {
        game("ps2/Core.iso")
        game("ps2/Armored Core.iso")
        game("ps2/Trapt.iso")
        assertEquals(listOf("ps2/Core.iso"), ids(SearchFilters(favourites = true), favourites = setOf("ps2/Core.iso", "app:com.example")))
        assertEquals(listOf("ps2/Trapt.iso"), ids(SearchFilters(wantToPlay = true), queue = setOf("ps2/Trapt.iso")))
        // both toggles: in both sets
        assertEquals(
            listOf("ps2/Core.iso"),
            ids(SearchFilters(favourites = true, wantToPlay = true), favourites = setOf("ps2/Core.iso", "ps2/Trapt.iso"), queue = setOf("ps2/Core.iso"))
        )
        assertEquals(0, count(SearchFilters(favourites = true)))
        assertEquals(emptyList<String>(), ids(SearchFilters(wantToPlay = true)))
    }

    @Test
    fun largeQueuesAndControlCharactersDoNotCreatePartialIdMatches() {
        val oddId = "ps2/Core.iso\u001ftail.iso"
        game("ps2/Core.iso")
        game(oddId)
        val queue = (1..1500).mapTo(HashSet()) { "ps2/Other $it.iso" }.also { it += oddId }
        assertEquals(listOf(oddId), ids(SearchFilters(wantToPlay = true), queue = queue))
        assertEquals(1, count(SearchFilters(wantToPlay = true), queue = queue))
    }

    @Test
    fun unplayedMeansNeverLaunchedAndFourStarsMeansFitFourOrMore() {
        game("ps2/Played.iso", fit = 4)
        game("ps2/Abandoned.iso", fit = 5)
        game("ps2/Fresh.iso", fit = 4)
        game("ps2/Weak.iso", fit = 2)
        session("ps2/Played.iso")
        session("ps2/Abandoned.iso", abandoned = true)
        assertEquals(setOf("ps2/Fresh.iso", "ps2/Weak.iso"), ids(SearchFilters(unplayed = true)).toSet())
        assertEquals(listOf("ps2/Fresh.iso"), ids(SearchFilters(unplayed = true, fourStars = true)))
    }

    @Test
    fun textSearchesTagsAndFlagsEscapesWildcardsAndRanksTitleHitsFirst() {
        game("ps2/Zone of the Enders.iso", fit = 3, tags = "mech")
        game("ps2/Some Brawler.iso", fit = 5, desc = "Features a mech boss.")
        game("ps2/Mech Assault.iso", fit = 2)
        game("gba/Fire Red.gba", flags = "fan translation")
        game("ps2/Score 1000.iso")
        game("ps2/Odd_Name.iso")
        game("ps2/OddXName.iso")
        // title hits lead whatever their fit; the rest follow by fit
        assertEquals(listOf("ps2/Mech Assault.iso", "ps2/Some Brawler.iso", "ps2/Zone of the Enders.iso"), ids(SearchFilters(text = "mech")))
        assertEquals(listOf("gba/Fire Red.gba"), ids(SearchFilters(text = "translation")))
        assertEquals(0, count(SearchFilters(text = "100%")))
        assertEquals(listOf("ps2/Odd_Name.iso"), ids(SearchFilters(text = "odd_")))
        // one character is a valid search (no minimum length)
        assertTrue(count(SearchFilters(text = "z")) >= 1)
    }

    @Test
    fun noMatchesIsAnEmptyPageAndZeroCount() {
        game("ps2/Armored Core 3.iso", tags = "mech")
        val f = SearchFilters(text = "zzz", systems = setOf("psvita"), tags = setOf("mech"), genres = setOf("Nope"))
        assertEquals(emptyList<String>(), ids(f))
        assertEquals(0, count(f))
    }

    @Test
    fun clearedFiltersKeepTheText() {
        val f = SearchFilters(text = "core", systems = setOf("ps2"), unplayed = true, onDeviceOnly = false)
        assertEquals(SearchFilters(text = "core"), f.clearedFilters())
        assertFalse(f.clearedFilters().hasFilters)
    }

    @Test
    fun labelFacetsCountGamesOnceAndMarkFlagOnlyLabels() {
        val facets = LibrarySearch.labelFacets(
            listOf(
                LabelCombo(tags = "puzzle,action", flags = "puzzle", n = 3),
                LabelCombo(tags = "mech", flags = "hack", n = 2),
                LabelCombo(tags = "", flags = "japanese only", n = 1)
            )
        )
        assertEquals(
            listOf(
                LabelFacet("action", 3, caution = false),
                LabelFacet("hack", 2, caution = true),
                LabelFacet("japanese only", 1, caution = true),
                LabelFacet("mech", 2, caution = false),
                LabelFacet("puzzle", 3, caution = false)
            ),
            facets
        )
    }
}
