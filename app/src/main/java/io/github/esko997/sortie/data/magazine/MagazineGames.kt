package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.GameEntity
import io.github.esko997.sortie.data.db.MatchRow
import io.github.esko997.sortie.data.db.chunked
import io.github.esko997.sortie.data.manifest.SystemDef
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Magazine references against the local library. Identity is deliberately narrow: a reference matches a local game only on
 * the same platform (exact systems.json id; "arcade" covers every arcade-generation system) and only when a normalised name
 * of the reference (its title or an explicit match name) equals a normalised name of the game. Normalising forgives case,
 * punctuation, accents, ", The" order, extensions and region/language/disc/revision/reprint tags, but keeps every other
 * tag word for word — (v1.02 Translation), [T-En by X], (Hack Foo), (Special Edition), (Premium Pack), (Beta)… — so
 * editions, and different translations or hacks of one game, never collapse. A game's scraped title only counts when its
 * file name says nothing (a serial/title id) or is an arcade romset name, and never for a hack: a generic scraped title must
 * not make a hack or another edition look like the original. Nothing is fuzzy.
 */

/** Whether a matched file can be opened here. Only [AVAILABLE] counts as "on this device". */
enum class GamePresence { AVAILABLE, PLACEHOLDER, ARRIVING, MISSING }

/** One local file a reference matched. Several matches of one reference are choices for the reader, never auto-picked. */
data class LocalGameMatch(val gameId: String, val systemId: String, val title: String, val fileName: String, val presence: GamePresence) {
    val available: Boolean get() = presence == GamePresence.AVAILABLE
}

/** An article that discusses a game, through [ref]. */
data class MagazineFeature(val issue: ShelfIssue, val article: MagazineArticle, val ref: MagazineGameReference)

class MagazineGames(private val dao: GameDao, private val systems: () -> List<SystemDef>) {
    /**
     * Every reference by id, with the local files it matches: available ones first, then by title and file name; empty when
     * none (or the reference has no platform). Reads only the referenced platforms' rows and checks only matched files.
     */
    suspend fun resolve(refs: Collection<MagazineGameReference>): Map<String, List<LocalGameMatch>> = withContext(Dispatchers.IO) {
        val families = SystemFamilies(systems())
        val systemIds = refs.flatMap { families.localIds(it.systemId) }.distinct()
        val rows = if (systemIds.isEmpty()) emptyList() else chunked(systemIds) { dao.matchRows(it) }
        localMatches(refs, rows, families)
    }

    /** The articles whose references identify [game] by the same rules as [resolve] (names and platform only, no file checks). */
    suspend fun featuredIn(game: GameEntity, shelf: MagazineShelf): List<MagazineFeature> = withContext(Dispatchers.Default) {
        featuresOf(game.systemId, game.fileName, game.title, game.hack, shelf, SystemFamilies(systems()))
    }
}

private val MATCH_ORDER = compareBy<LocalGameMatch>({ it.presence.ordinal }, { it.title.lowercase(Locale.ROOT) }, { it.fileName })

private fun MatchRow.toMatch(presence: GamePresence) = LocalGameMatch(id, systemId, title, fileName, presence)

/** [matchReferences] with each matched file checked: available files first, then by title and file name. Blocking. */
internal fun localMatches(refs: Collection<MagazineGameReference>, rows: List<MatchRow>, families: SystemFamilies): Map<String, List<LocalGameMatch>> =
    matchReferences(refs, rows, families).mapValues { (_, matched) -> matched.map { it.toMatch(presenceOf(it)) }.sortedWith(MATCH_ORDER) }

/** Platform identity: exact system id (case-insensitive), except that a reference's "arcade" covers all arcade systems. */
internal class SystemFamilies(systems: List<SystemDef>) {
    private val known = systems.map { it.id }
    private val arcade = systems.filter { it.generation.equals(ARCADE, ignoreCase = true) }.mapTo(HashSet()) { it.id.lowercase(Locale.ROOT) } + DEFAULT_ARCADE

    fun covers(refSystem: String, localSystem: String): Boolean {
        if (refSystem.isBlank()) return false
        if (refSystem.equals(localSystem, ignoreCase = true)) return true
        return refSystem.equals(ARCADE, ignoreCase = true) && isArcade(localSystem)
    }

    fun isArcade(localSystem: String): Boolean = localSystem.lowercase(Locale.ROOT) in arcade

    /** The local system ids a reference's platform covers (the platform itself when no systems are known yet). */
    fun localIds(refSystem: String): List<String> {
        if (refSystem.isBlank()) return emptyList()
        val ids = known.filter { covers(refSystem, it) }
        if (ids.isNotEmpty()) return ids
        return if (refSystem.equals(ARCADE, ignoreCase = true)) DEFAULT_ARCADE.toList() else listOf(refSystem)
    }

    private companion object {
        const val ARCADE = "arcade"
        val DEFAULT_ARCADE = setOf("arcade", "fbneo", "mame", "neogeo", "cps1", "cps2", "cps3")
    }
}

/** The names a reference answers to: its title and explicit match names, normalised. */
internal fun referenceKeys(ref: MagazineGameReference): Set<String> =
    (listOf(ref.title) + ref.matchNames).map(GameNames::key).filterTo(HashSet()) { it.isNotEmpty() }

/** The names a local game answers to; see the file comment for when its scraped [title] counts. */
internal fun localKeys(fileName: String, title: String, hack: Boolean, arcade: Boolean): Set<String> {
    val stem = GameNames.stem(fileName)
    val fileKey = GameNames.key(stem)
    val keys = HashSet<String>()
    if (fileKey.isNotEmpty()) keys += fileKey
    val qualified = fileKey != GameNames.key(GameNames.bare(stem))
    if (!hack && !qualified && (arcade || GameNames.opaque(fileKey))) GameNames.key(title).takeIf { it.isNotEmpty() }?.let { keys += it }
    return keys
}

/** Every reference id with the rows it matches (empty when none); a row matched through two names is listed once. */
internal fun matchReferences(refs: Collection<MagazineGameReference>, rows: List<MatchRow>, families: SystemFamilies): Map<String, List<MatchRow>> {
    val byKey = HashMap<String, MutableList<MatchRow>>()
    for (r in rows) {
        for (k in localKeys(r.fileName, r.title, r.hack, families.isArcade(r.systemId))) byKey.getOrPut(k) { ArrayList() } += r
    }
    val out = LinkedHashMap<String, List<MatchRow>>()
    for (ref in refs) {
        if (ref.id in out) continue
        out[ref.id] = if (ref.systemId.isBlank()) {
            emptyList()
        } else {
            referenceKeys(ref).flatMap { byKey[it].orEmpty() }.filter { families.covers(ref.systemId, it.systemId) }.distinctBy { it.id }
        }
    }
    return out
}

/** Blocking file checks for one matched row: a placeholder, a Syncthing transfer or a vanished file is not available. */
internal fun presenceOf(row: MatchRow): GamePresence {
    if (row.placeholder || row.size == 0L) return GamePresence.PLACEHOLDER
    if (row.arriving) return GamePresence.ARRIVING
    return try {
        val f = File(row.path)
        when {
            !f.isFile || !f.canRead() -> GamePresence.MISSING
            f.length() == 0L -> GamePresence.PLACEHOLDER
            File(f.parentFile, ".syncthing.${f.name}.tmp").exists() -> GamePresence.ARRIVING
            else -> GamePresence.AVAILABLE
        }
    } catch (e: SecurityException) {
        GamePresence.MISSING
    }
}

internal fun featuresOf(
    systemId: String,
    fileName: String,
    title: String,
    hack: Boolean,
    shelf: MagazineShelf,
    families: SystemFamilies
): List<MagazineFeature> {
    val keys = localKeys(fileName, title, hack, families.isArcade(systemId))
    if (keys.isEmpty()) return emptyList()
    val out = ArrayList<MagazineFeature>()
    for (issue in shelf.issues) {
        for (article in issue.issue.articles) {
            val ref = article.games.firstOrNull { families.covers(it.systemId, systemId) && referenceKeys(it).any(keys::contains) } ?: continue
            out += MagazineFeature(issue, article, ref)
        }
    }
    return out
}

/** Name normalisation shared by references, local files and version 1 pack conversion. */
internal object GameNames {
    private val GROUP = Regex("""[(\[]([^()\[\]]*)[)\]]""")
    private val ARTICLE = Regex("""^(.*?),\s+(the|a|an)(?=$|\s+-\s|\s*:)(.*)$""", RegexOption.IGNORE_CASE)
    private val EXTENSION = Regex("""\.[A-Za-z0-9]{1,5}$""")
    private val CONTAINER_SUFFIX = Regex("""\.(nkit|dec|enc)$""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("""\p{M}+""")
    private val APOSTROPHES = Regex("""['’`´]""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val SPACES = Regex("""\s+""")
    private val OPAQUE = Regex("""^(?:[a-z]{4} ?\d{5}|[0-9a-f]{16}|[^\p{L}]*)$""")
    private val VERSION = Regex("""^(?:v\d[\w.]*|r\d+|\d+(?:\.\d+)*[a-z]?|\d+(?:st|nd|rd|th)|[a-gijksuw]|m\d+|[bofa]\d+)$""")

    /** Words inside a tag that do not change which edition a file is: regions, languages, disc/revision/set, budget reprints. */
    private val NEUTRAL = setOf(
        "usa", "us", "america", "europe", "eu", "japan", "jp", "jpn", "world", "asia", "korea", "china", "taiwan", "hong", "kong",
        "brazil", "australia", "canada", "france", "germany", "spain", "italy", "uk", "netherlands", "sweden", "scandinavia",
        "russia", "en", "ja", "fr", "de", "es", "it", "nl", "pt", "sv", "no", "da", "fi", "zh", "ko", "ru", "pl",
        "ntsc", "pal", "secam", "ue", "ju", "jue", "rev", "revision", "ver", "version", "alt", "alternate", "set", "disc", "disk",
        "cd", "side", "of", "track", "greatest", "hits", "platinum", "players", "player", "choice", "best", "the", "playstation",
        "classics", "essentials", "reprint", "budget", "psn",
        "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul", "july", "aug", "august",
        "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december",
        "mon", "tue", "wed", "thu", "fri", "sat", "sun"
    )

    /** File name without its extension (and a `.nkit`-style container infix). A name without an extension is kept whole. */
    fun stem(fileName: String): String = CONTAINER_SUFFIX.replace(EXTENSION.replace(fileName.trim(), ""), "")

    /**
     * The identity key: main title words (", The" moved to the front) followed by the edition qualifiers of its tags, sorted.
     * Empty when the name has no letters or digits.
     */
    fun key(name: String): String {
        val n = MARKS.replace(Normalizer.normalize(name, Normalizer.Form.NFKD), "")
        val qualifiers = sortedSetOf<String>()
        val main = GROUP.replace(n) { m ->
            qualifier(m.groupValues[1])?.let { qualifiers += it }
            " "
        }
        val title = words(reorder(main.replace(SPACES, " ").trim()))
        return (listOf(title) + qualifiers).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** [stem] with every tag removed, edition qualifiers included: what a generic scraped title would look like. */
    fun bare(stem: String): String = GROUP.replace(stem, " ")

    /** A readable title from a file stem: neutral tags dropped, edition tags kept as written, ", The" moved to the front. */
    fun displayTitle(stem: String): String {
        val kept = GROUP.replace(stem) { m -> if (qualifier(m.groupValues[1]) == null) " " else " ${m.value} " }
        val parts = kept.replace(SPACES, " ").trim()
        val firstTag = parts.indexOfFirst { it == '(' || it == '[' }.takeIf { it > 0 }
        val head = firstTag?.let { parts.substring(0, it).trim() } ?: parts
        val tail = firstTag?.let { " " + parts.substring(it) } ?: ""
        return (reorder(head) + tail).ifBlank { stem }
    }

    /** A lowercase ASCII slug: letters and digits joined by single hyphens. */
    fun slug(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFKD), "").lowercase(Locale.ROOT)
        .replace("&", " and ").replace(APOSTROPHES, "").replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** A file key that names nothing: a product serial, a title id or no letters at all. */
    fun opaque(key: String): Boolean = OPAQUE.matches(key)

    /**
     * A tag's edition meaning: its words as written (normalised), or null when every comma/slash-separated part is neutral.
     * A part with any meaningful word is kept whole, so "[T-En by X]" and "[T-Fr by Y]" stay different.
     */
    private fun qualifier(content: String): String? {
        val kept = content.split(',', '/', ';').map(::words).filter { token ->
            token.isNotEmpty() && token.split(' ').any { it !in NEUTRAL && !VERSION.matches(it) }
        }
        return kept.joinToString(" ").ifEmpty { null }
    }

    private fun reorder(s: String): String = ARTICLE.matchEntire(s)?.let { m -> "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}" } ?: s

    private fun words(s: String): String = s.lowercase(Locale.ROOT).replace("&", " and ").replace(APOSTROPHES, "").replace(NON_WORD, " ").trim()
}
