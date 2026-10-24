package io.github.esko997.sortie.data.db

import java.util.Locale

/**
 * What the Search screen asks the games table for. [text] matches title, genre, developer, description, tags and flags as a
 * substring; [systems], [tags] and [genres] are exact values from the library. [tags] are the badge labels a game's page
 * shows: guide tags and guide flags (cautions and notes such as `hack`), a label matching when either column holds it.
 * Values selected within one dimension match either (OR); the dimensions and the toggles all have to hold (AND).
 * [onDeviceOnly] hides 0-byte placeholders and is on by default, like the folder screens' "on device" chip.
 */
data class SearchFilters(
    val text: String = "",
    val systems: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val favourites: Boolean = false,
    val wantToPlay: Boolean = false,
    val unplayed: Boolean = false,
    val fourStars: Boolean = false,
    val onDeviceOnly: Boolean = true
) {
    val query: String get() = text.trim()

    /** Anything that narrows the library. The on-device default alone does not: Search then waits for input instead of listing everything. */
    val hasCriteria: Boolean
        get() = query.isNotEmpty() || hasFilters

    /** Any filter differing from the defaults (the text is not a filter). */
    val hasFilters: Boolean
        get() = systems.isNotEmpty() || tags.isNotEmpty() || genres.isNotEmpty() || favourites || wantToPlay || unplayed || fourStars || !onDeviceOnly

    /** The same text with every filter back at its default. */
    fun clearedFilters(): SearchFilters = SearchFilters(text = text)
}

/** One SQL statement with its positional `?` arguments (String or Int). */
data class SqlQuery(val sql: String, val args: List<Any>)

/** A distinct library value (system id, badge label or genre) and how many shown games carry it. */
data class FacetCount(val value: String, val n: Int)

/**
 * One distinct pair of `tags` and `flags` column values and how many shown games have it; [LibrarySearch.labelFacets] splits
 * them into badge labels.
 */
data class LabelCombo(val tags: String, val flags: String, val n: Int)

/** A badge label with its game count; [caution] when it only ever appears as a guide flag (a note, not a genre-like tag). */
data class LabelFacet(val value: String, val n: Int, val caution: Boolean)

/**
 * Builds the Search statements. Every filter is part of the WHERE clause, so [page]'s LIMIT and [count] see exactly the same
 * matches: a limit never hides a game that a later page would not show. Favourites and Want to play live in user.json, not
 * the table; their ids travel as one delimited argument (`instr`), so a long queue never hits SQLite's variable limit.
 */
object LibrarySearch {
    /** Separates ids in the membership argument; it cannot occur in a file name. */
    private const val SEP = '\u0000'

    // `desc` is also an SQL keyword
    private val TEXT_FIELDS = listOf("title", "genre", "developer", "`desc`", "tags", "flags")

    fun page(f: SearchFilters, favourites: Set<String>, wantToPlay: Set<String>, limit: Int): SqlQuery {
        val where = where(f, favourites, wantToPlay)
        val order = mutableListOf<Any>()
        // with text, title matches lead: a tag or description mention is a weaker hit than the name itself
        val rank = if (f.query.isEmpty()) {
            ""
        } else {
            order += like(f.query)
            "CASE WHEN title LIKE ? ESCAPE '\\' THEN 0 ELSE 1 END, "
        }
        return SqlQuery(
            "SELECT * FROM games WHERE ${where.sql} ORDER BY ${rank}fit DESC, sortTitle, id LIMIT ?",
            where.args + order + limit.coerceAtLeast(0)
        )
    }

    fun count(f: SearchFilters, favourites: Set<String>, wantToPlay: Set<String>): SqlQuery {
        val where = where(f, favourites, wantToPlay)
        return SqlQuery("SELECT COUNT(*) FROM games WHERE ${where.sql}", where.args)
    }

    private fun where(f: SearchFilters, favourites: Set<String>, wantToPlay: Set<String>): SqlQuery {
        val clauses = mutableListOf("variantOf IS NULL")
        val args = mutableListOf<Any>()
        if (f.onDeviceOnly) clauses += "placeholder = 0"
        if (f.query.isNotEmpty()) {
            val pattern = like(f.query)
            clauses += TEXT_FIELDS.joinToString(" OR ", "(", ")") { "$it LIKE ? ESCAPE '\\'" }
            repeat(TEXT_FIELDS.size) { args += pattern }
        }
        if (f.systems.isNotEmpty()) {
            clauses += f.systems.joinToString(", ", "systemId IN (", ")") { "?" }
            args.addAll(f.systems.sorted())
        }
        if (f.genres.isNotEmpty()) {
            clauses += f.genres.joinToString(", ", "genre IN (", ")") { "?" }
            args.addAll(f.genres.sorted())
        }
        val labels = f.tags.map { it.lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().sorted()
        if (labels.isNotEmpty()) {
            // whole comma-separated entries only, in either column: "mech" must not match "mechanic" or "robot-mech"
            clauses += labels.joinToString(" OR ", "(", ")") { "instr(',' || tags || ',', ?) > 0 OR instr(',' || flags || ',', ?) > 0" }
            labels.forEach {
                args += ",$it,"
                args += ",$it,"
            }
        }
        if (f.favourites) member(favourites, clauses, args)
        if (f.wantToPlay) member(wantToPlay, clauses, args)
        if (f.unplayed) clauses += "NOT EXISTS (SELECT 1 FROM sessions WHERE sessions.gameId = games.id)"
        if (f.fourStars) clauses += "fit >= 4"
        return SqlQuery(clauses.joinToString(" AND "), args)
    }

    private fun member(ids: Set<String>, clauses: MutableList<String>, args: MutableList<Any>) {
        if (ids.isEmpty()) {
            clauses += "0"
            return
        }
        clauses += "instr(?, char(0) || id || char(0)) > 0"
        args += ids.joinToString(SEP.toString(), SEP.toString(), SEP.toString())
    }

    private fun like(q: String) = "%${likeEscape(q)}%"

    /**
     * Badge labels with per-game counts from distinct `tags`/`flags` pairs, by name. A label in both columns of one game counts
     * once; it is a [LabelFacet.caution] only if no game has it as a tag.
     */
    fun labelFacets(combos: List<LabelCombo>): List<LabelFacet> {
        val counts = HashMap<String, Int>()
        val asTag = HashSet<String>()
        for (row in combos) {
            val tags = row.tags.split(',').filter { it.isNotBlank() }
            asTag += tags
            (tags + row.flags.split(',').filter { it.isNotBlank() }).distinct().forEach { counts[it] = (counts[it] ?: 0) + row.n }
        }
        return counts.map { LabelFacet(it.key, it.value, it.key !in asTag) }.sortedBy { it.value }
    }
}
