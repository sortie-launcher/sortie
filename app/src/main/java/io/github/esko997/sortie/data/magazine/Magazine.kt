package io.github.esko997.sortie.data.magazine

import io.github.esko997.sortie.data.markdown.MdBlock
import io.github.esko997.sortie.data.markdown.parseMarkdown
import java.io.File
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/*
 * Offline magazine packs, format v2: `<issue-id>/issue.json` with sibling `articles/` (Markdown, see parseMarkdown) and
 * `media/` folders. Issues come from two places: packs bundled in the APK (extracted once into app storage, see
 * BundledMagazines) and packs the user copied to `<sortieDir>/magazines/`. Every path in a pack — issue.json fields and
 * Markdown image lines alike — is relative to the issue folder and must stay inside it: no URLs, absolute paths, `..` or
 * symlinks that lead out. A malformed pack is reported with a reason and never stops the other issues from loading; a missing
 * optional image never makes an article unreadable. Nothing here touches the network.
 *
 * Articles name the games they discuss with portable [MagazineGameReference]s (title, platform, edition), never a reader's
 * file names; MagazineGames resolves them against the local library. Version 1 packs (articles listing local `gameIds`) are
 * still read: they are converted to references here, at the input boundary.
 */

const val MAGAZINE_SCHEMA_VERSION = 2
internal const val MAX_ISSUE_JSON_BYTES = 256L * 1024
internal const val MAX_ARTICLE_BYTES = 1024L * 1024

internal val SLUG = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")

/** Globally stable reference id: the same game, platform and edition has the same id in every issue. */
internal val GAME_REF_ID = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")

/**
 * A game an article discusses, independent of anyone's library. [id] names the work/platform/edition (not a file).
 * [systemId] empty: informational only, never matched locally. [edition] is human-readable, not a matching rule.
 * [matchNames]: explicit equivalent title spellings (including edition qualifiers such as `(Translation)`), never a series.
 */
data class MagazineGameReference(
    val id: String,
    val title: String,
    val systemId: String = "",
    val edition: String = "",
    val matchNames: List<String> = emptyList()
)

data class MagazineArticle(
    val id: String,
    val title: String,
    val gameTitle: String = "",
    val system: String = "",
    /** The games the article discusses; listed, not promised to be on this device. */
    val games: List<MagazineGameReference> = emptyList(),
    val deck: String = "",
    /** Issue-relative Markdown file. */
    val body: String,
    /** Issue-relative lead image, if any. */
    val image: String? = null,
    val imageCaption: String = "",
    /** Informational; positive when present. */
    val readingMinutes: Int? = null
)

data class MagazineIssue(
    val id: String,
    val title: String,
    val subtitle: String = "",
    /** As written in the pack; usually an ISO date (see [publishedDate]). */
    val published: String,
    /** Issue-relative cover image, if any. */
    val cover: String? = null,
    val editorial: String = "",
    val articles: List<MagazineArticle>,
    /** Positive; a higher revision of the same issue id replaces a lower one on the shelf. Version 1 packs are revision 1. */
    val revision: Int = 1,
    /** Third-party rights statement shown with the contents; empty when the pack has none. */
    val rightsNotice: String = ""
) {
    val publishedDate: LocalDate? get() = runCatching { LocalDate.parse(published) }.getOrNull()

    /** Every game reference of the issue once, in article order. */
    val games: List<MagazineGameReference> by lazy { articles.flatMap { it.games }.distinctBy { it.id } }

    fun article(id: String?): MagazineArticle? = articles.firstOrNull { it.id == id }

    fun articlesMentioning(refId: String): List<MagazineArticle> = articles.filter { a -> a.games.any { it.id == refId } }
}

sealed interface IssueParse {
    data class Valid(val issue: MagazineIssue) : IssueParse

    data class Invalid(val reason: String) : IssueParse
}

/**
 * Whether [path] may name a file of an issue: relative, `/`-separated, no empty, `.` or `..` segment, no backslash, no colon
 * (URL schemes, drive letters), no control characters. Symlinks are checked separately by [resolveInIssue].
 */
fun isSafeIssuePath(path: String): Boolean {
    if (path.isEmpty() || path.length > 240 || path.startsWith('/') || path.startsWith('~')) return false
    if (path.any { it == '\\' || it == ':' || it < ' ' || it == '\u007f' }) return false
    return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
}

/** [path] inside [issueDir], after following symlinks; null when the path is unsafe or leads outside the issue folder. */
fun resolveInIssue(issueDir: File, path: String): File? {
    if (!isSafeIssuePath(path)) return null
    return try {
        val root = issueDir.canonicalFile
        File(root, path).canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

private class PackException(message: String) : Exception(message)

private fun fail(message: String): Nothing = throw PackException(message)

/** Parses and checks an issue.json text for the issue folder named [folderName], without touching the file system. */
fun parseIssue(text: String, folderName: String): IssueParse = try {
    IssueParse.Valid(readIssue(text.removePrefix("\uFEFF"), folderName))
} catch (e: PackException) {
    IssueParse.Invalid(e.message ?: "issue.json is not a valid issue")
}

private fun readIssue(text: String, folderName: String): MagazineIssue {
    val element: JsonElement = try {
        Json.parseToJsonElement(text)
    } catch (e: Exception) {
        fail("issue.json is not valid JSON")
    }
    val root = element as? JsonObject ?: fail("issue.json must be a JSON object")
    val version = root["schemaVersion"] ?: fail("issue.json has no schemaVersion (this Sortie reads versions 1 to $MAGAZINE_SCHEMA_VERSION)")
    val v = (version as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: fail("schemaVersion must be a whole number")
    if (v !in 1..MAGAZINE_SCHEMA_VERSION) fail("Unsupported schemaVersion $v: this Sortie reads versions 1 to $MAGAZINE_SCHEMA_VERSION")
    val legacy = v == 1
    val id = root.text("id", "issue", required = true)
    if (!SLUG.matches(id)) fail("issue id \"${id.take(80)}\" must be a slug (letters, digits, - or _)")
    if (id != folderName) fail("issue id \"$id\" does not match its folder name \"$folderName\"")
    val title = root.text("title", "issue", required = true)
    val published = root.text("published", "issue", required = true)
    val subtitle = root.text("subtitle", "issue", required = false)
    val cover = root.path("cover", "issue", required = false)
    val editorial = root.text("editorial", "issue", required = false)
    val revision = if (legacy) 1 else positiveInt(root["revision"], "issue: \"revision\"", required = true)!!
    val rightsNotice = if (legacy) "" else root.text("rightsNotice", "issue", required = false)
    val list = when (val a = root["articles"]) {
        null, is JsonNull -> fail("issue: \"articles\" is required")
        is JsonArray -> a
        else -> fail("issue: \"articles\" must be a list")
    }
    if (list.isEmpty()) fail("issue: \"articles\" must list at least one article")
    val seen = HashSet<String>()
    val articles = list.mapIndexed { i, element -> readArticleEntry(element, i, seen, legacy) }
    return MagazineIssue(id, title, subtitle, published, cover, editorial, unifyRefs(articles, strict = !legacy), revision, rightsNotice)
}

private fun readArticleEntry(element: JsonElement, index: Int, seen: MutableSet<String>, legacy: Boolean): MagazineArticle {
    val o = element as? JsonObject ?: fail("article ${index + 1} must be a JSON object")
    val id = o.text("id", "article ${index + 1}", required = true)
    if (!SLUG.matches(id)) fail("article ${index + 1}: id \"${id.take(80)}\" must be a slug (letters, digits, - or _)")
    if (!seen.add(id)) fail("article id \"$id\" is used twice")
    val where = "article \"$id\""
    val gameTitle = o.text("gameTitle", where, required = false)
    val games = if (legacy) {
        val gameIds = textList(o["gameIds"], "$where: \"gameIds\"")
        gameIds.map { legacyReference(it, gameTitle.takeIf { gameIds.size == 1 }) }
            .groupBy { it.id }.values.map { same -> same[0].copy(matchNames = same.flatMap { it.matchNames }.distinct()) }
    } else {
        if (o["gameIds"] != null) fail("$where: \"gameIds\" is a version 1 field; version 2 articles list \"games\"")
        readGames(o["games"], where)
    }
    return MagazineArticle(
        id = id,
        title = o.text("title", where, required = true),
        gameTitle = gameTitle,
        system = o.text("system", where, required = false),
        games = games,
        deck = o.text("deck", where, required = false),
        body = o.path("body", where, required = true)!!,
        image = o.path("image", where, required = false),
        imageCaption = o.text("imageCaption", where, required = false),
        readingMinutes = positiveInt(o["readingMinutes"], "$where: \"readingMinutes\"", required = false)
    )
}

private fun readGames(element: JsonElement?, where: String): List<MagazineGameReference> {
    val list = when (element) {
        null, is JsonNull -> return emptyList()
        is JsonArray -> element
        else -> fail("$where: \"games\" must be a list")
    }
    val seen = HashSet<String>()
    return list.mapIndexed { i, e ->
        val o = e as? JsonObject ?: fail("$where: game ${i + 1} must be a JSON object")
        val at = "$where, game ${i + 1}"
        val id = o.text("id", at, required = true)
        if (!GAME_REF_ID.matches(id)) fail("$at: id \"${id.take(120)}\" must be letters, digits, - or _ (at most 120)")
        if (!seen.add(id)) fail("$where: game \"$id\" is listed twice")
        MagazineGameReference(
            id = id,
            title = o.text("title", "$where, game \"$id\"", required = true),
            systemId = o.text("systemId", "$where, game \"$id\"", required = false),
            edition = o.text("edition", "$where, game \"$id\"", required = false),
            matchNames = textList(o["matchNames"], "$where, game \"$id\": \"matchNames\"").distinct()
        )
    }
}

/**
 * One id is one game: every article's copy of a reference id gets the issue's union of match names. [strict]: an issue that
 * gives one id a different title, platform or edition in two articles is rejected (converted version 1 references, named
 * from file names, keep the first article's description instead).
 */
private fun unifyRefs(articles: List<MagazineArticle>, strict: Boolean): List<MagazineArticle> {
    val first = HashMap<String, Pair<String, MagazineGameReference>>()
    val names = HashMap<String, LinkedHashSet<String>>()
    for (a in articles) {
        for (g in a.games) {
            val (where, ref) = first.getOrPut(g.id) { a.id to g }
            if (strict && (ref.title != g.title || ref.systemId != g.systemId || ref.edition != g.edition)) {
                fail("game \"${g.id}\" has a different title, systemId or edition in articles \"$where\" and \"${a.id}\"")
            }
            names.getOrPut(g.id) { LinkedHashSet() } += g.matchNames
        }
    }
    return articles.map { a -> a.copy(games = a.games.map { first.getValue(it.id).second.copy(matchNames = names.getValue(it.id).toList()) }) }
}

/**
 * A version 1 `system/file name` id as a reference: the platform is the id's system, the file stem (with its edition
 * qualifiers) is the match name, so the same file in another container or region still matches but a hack or translation of
 * it does not. [articleTitle]: the article's own gameTitle when the article named exactly one game.
 */
internal fun legacyReference(gameId: String, articleTitle: String?): MagazineGameReference {
    val slash = gameId.indexOf('/')
    val systemId = if (slash > 0) gameId.substring(0, slash) else ""
    val stem = GameNames.stem(if (slash > 0) gameId.substring(slash + 1) else gameId)
    val title = GameNames.displayTitle(stem)
    val slug = GameNames.slug(title).ifEmpty { "game" }
    val id = (if (systemId.isEmpty()) slug else "${GameNames.slug(systemId).ifEmpty { "system" }}-$slug").take(120).trimEnd('-')
    return MagazineGameReference(
        id = id,
        title = articleTitle?.takeIf { it.isNotBlank() && GameNames.key(it) == GameNames.key(stem) } ?: title,
        systemId = systemId,
        matchNames = listOf(stem)
    )
}

private fun positiveInt(element: JsonElement?, what: String, required: Boolean): Int? = when (element) {
    null, is JsonNull -> if (required) fail("$what is required") else null
    else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } ?: fail("$what must be a positive whole number")
}

/** A list of non-empty texts, trimmed; absent or null reads as empty. */
private fun textList(element: JsonElement?, what: String): List<String> = when (element) {
    null, is JsonNull -> emptyList()
    is JsonArray -> element.map { e ->
        val p = e as? JsonPrimitive
        if (p == null || !p.isString || p.content.isBlank()) fail("$what: every entry must be non-empty text")
        p.content.trim()
    }
    else -> fail("$what must be a list")
}

/** A text field, trimmed; an optional one that is absent or null reads as "". */
private fun JsonObject.text(key: String, where: String, required: Boolean): String {
    val e = this[key]
    if (e == null || e is JsonNull) {
        if (required) fail("$where: \"$key\" is required")
        return ""
    }
    val p = e as? JsonPrimitive
    if (p == null || !p.isString) fail("$where: \"$key\" must be text")
    val s = p.content.trim()
    if (required && s.isEmpty()) fail("$where: \"$key\" must not be empty")
    return s
}

/** An issue-relative path field; null when optional and absent or empty. */
private fun JsonObject.path(key: String, where: String, required: Boolean): String? {
    val s = text(key, where, required)
    if (s.isEmpty()) return null
    if (!isSafeIssuePath(s)) fail("$where: \"$key\" path \"${s.take(120)}\" must be relative to the issue folder (no URLs, absolute paths or ..)")
    return s
}

/** An image an issue refers to. [file] is set only for a regular file inside the issue; otherwise [problem] says why not. */
data class IssueImage(val path: String, val file: File?, val problem: String?)

/** Where a shelf issue was read from: the APK's bundled packs, or the user's card folder. */
enum class IssueSource { BUNDLED, CARD }

sealed interface ShelfEntry

/** A readable issue. [dir] is the canonical issue folder; [images] holds each article's lead image by article id. */
data class ShelfIssue(
    val dir: File,
    val issue: MagazineIssue,
    val cover: IssueImage?,
    val images: Map<String, IssueImage>,
    val source: IssueSource = IssueSource.CARD
) : ShelfEntry

/** An issue folder that could not be read, and why. */
data class BrokenIssue(val folder: String, val reason: String) : ShelfEntry

/**
 * Every issue on the shelf: one entry per issue id, readable issues newest first, broken ones by folder name. [dir] is the
 * card's magazines folder; [problem]: the card folder or the built-in packs as a whole could not be read (whatever could be
 * read from the other source is still listed).
 */
data class MagazineShelf(val dir: File, val issues: List<ShelfIssue>, val broken: List<BrokenIssue>, val problem: String? = null) {
    fun issue(id: String?): ShelfIssue? = issues.firstOrNull { it.issue.id == id }
}

sealed interface ArticleContent {
    /** [images] resolves every Markdown image line's path, by path as written. */
    data class Ready(val blocks: List<MdBlock>, val images: Map<String, IssueImage>) : ArticleContent

    data class Failed(val reason: String) : ArticleContent
}

/**
 * The shelf: bundled issues (prepared once by [bundled]) merged with the card's `magazines/` folder by issue id — a higher
 * revision wins, an equal revision prefers the card copy. Card folders are only ever read. The merged shelf is reused while
 * the card folder looks unchanged (a few stat calls), so screens may ask for it freely.
 */
class MagazineRepository(sortieDir: File, private val bundled: BundledMagazines? = null) {
    val dir = File(sortieDir, "magazines")
    private val lock = Mutex()
    private var cached: Pair<List<Any>, MagazineShelf>? = null

    suspend fun shelf(): MagazineShelf = withContext(Dispatchers.IO) {
        lock.withLock {
            val bundledShelf = bundled?.shelf()
            val signature = cardSignature(dir) + listOfNotNull(bundledShelf?.dir?.path)
            cached?.takeIf { it.first == signature }?.second ?: run {
                val card = readShelf(dir)
                val merged = if (bundledShelf == null) card else mergeShelves(card, bundledShelf)
                cached = signature to merged
                merged
            }
        }
    }

    suspend fun article(issue: ShelfIssue, article: MagazineArticle): ArticleContent = withContext(Dispatchers.IO) { readArticle(issue.dir, article) }
}

/** What a reload would notice: issue folders, their issue.json and media folders. Article text is read fresh on open anyway. */
private fun cardSignature(dir: File): List<Any> {
    val children = dir.listFiles() ?: return listOf(dir.isDirectory)
    return children.sortedBy { it.name }.flatMap { c ->
        listOf(c.name, c.lastModified(), File(c, "issue.json").let { "${it.lastModified()}:${it.length()}" }, File(c, "media").lastModified())
    }
}

/**
 * [card] and [bundled] as one shelf, one entry per issue id: the higher revision wins and an equal revision prefers the card.
 * A card folder that could not be read never hides a readable bundled issue of the same id; its reason is kept, prefixed.
 */
internal fun mergeShelves(card: MagazineShelf, bundled: MagazineShelf): MagazineShelf {
    val byId = LinkedHashMap<String, ShelfIssue>()
    for (b in bundled.issues) byId[b.issue.id] = b
    for (c in card.issues) {
        val b = byId[c.issue.id]
        if (b == null || c.issue.revision >= b.issue.revision) byId[c.issue.id] = c
    }
    val broken = card.broken.map {
        if (it.folder in byId) it.copy(reason = "The copy on the card could not be read, so the built-in edition is shown. ${it.reason}") else it
    } + bundled.broken.filter { it.folder !in byId && card.broken.none { c -> c.folder == it.folder } }
    val problem = listOfNotNull(card.problem, bundled.problem).joinToString("\n").ifEmpty { null }
    return MagazineShelf(card.dir, byId.values.sortedWith(NEWEST_FIRST), broken.sortedBy { it.folder }, problem)
}

private val NEWEST_FIRST = compareByDescending<ShelfIssue> { it.issue.publishedDate ?: LocalDate.MIN }.thenBy { it.issue.id }

/** Blocking; see [MagazineRepository.shelf]. Folders starting with `.` (e.g. a deploy in progress) are skipped. */
internal fun readShelf(dir: File, source: IssueSource = IssueSource.CARD): MagazineShelf {
    if (!dir.isDirectory) return MagazineShelf(dir, emptyList(), emptyList())
    val root = try {
        dir.canonicalFile
    } catch (e: IOException) {
        return MagazineShelf(dir, emptyList(), emptyList(), "Could not read ${dir.path}: ${e.message}")
    }
    val children = dir.listFiles() ?: return MagazineShelf(dir, emptyList(), emptyList(), "Could not list ${dir.path}; check that Sortie has file access")
    val issues = ArrayList<ShelfIssue>()
    val broken = ArrayList<BrokenIssue>()
    for (child in children.sortedBy { it.name }) {
        if (child.name.startsWith('.') || !child.isDirectory) continue
        when (val entry = readIssueDir(root, child, source)) {
            is ShelfIssue -> issues += entry
            is BrokenIssue -> broken += entry
        }
    }
    issues.sortWith(NEWEST_FIRST)
    return MagazineShelf(dir, issues, broken)
}

internal fun readIssueDir(magazinesRoot: File, folder: File, source: IssueSource = IssueSource.CARD): ShelfEntry {
    val name = folder.name
    return try {
        val root = folder.canonicalFile
        if (root.parentFile != magazinesRoot) return BrokenIssue(name, "The folder links outside magazines/; copy the issue in as a real folder")
        val json = File(root, "issue.json")
        if (!json.isFile) return BrokenIssue(name, "No issue.json in magazines/$name")
        if (json.canonicalFile.parentFile != root) return BrokenIssue(name, "issue.json links outside the issue folder")
        if (json.length() > MAX_ISSUE_JSON_BYTES) return BrokenIssue(name, "issue.json is larger than ${MAX_ISSUE_JSON_BYTES / 1024} KB")
        val issue = when (val parsed = parseIssue(json.readText(), name)) {
            is IssueParse.Invalid -> return BrokenIssue(name, parsed.reason)
            is IssueParse.Valid -> parsed.issue
        }
        // every article must be openable: an incomplete copy is broken here, so it cannot outrank a complete one by revision
        for (a in issue.articles) {
            val body = resolveInIssue(root, a.body) ?: return BrokenIssue(name, "article \"${a.id}\": ${a.body} leads outside the issue folder")
            if (!body.isFile || !body.canRead()) return BrokenIssue(name, "article \"${a.id}\": ${a.body} is missing or unreadable")
            if (body.length() > MAX_ARTICLE_BYTES) return BrokenIssue(name, "article \"${a.id}\": ${a.body} is larger than ${MAX_ARTICLE_BYTES / 1024} KB")
        }
        val cover = issue.cover?.let { imageIn(root, it) ?: return BrokenIssue(name, "cover ${issue.cover} leads outside the issue folder") }
        val images = HashMap<String, IssueImage>()
        for (a in issue.articles) {
            val path = a.image ?: continue
            images[a.id] = imageIn(root, path) ?: return BrokenIssue(name, "article \"${a.id}\": image $path leads outside the issue folder")
        }
        ShelfIssue(root, issue, cover, images, source)
    } catch (e: IOException) {
        BrokenIssue(name, "Could not read magazines/$name: ${e.message}")
    } catch (e: SecurityException) {
        BrokenIssue(name, "Could not read magazines/$name: ${e.message}")
    }
}

/** A safe [path]'s image: null if it leads outside the issue, else a file or a "missing" problem. */
private fun imageIn(issueDir: File, path: String): IssueImage? {
    val file = resolveInIssue(issueDir, path) ?: return null
    return if (file.isFile) IssueImage(path, file, null) else IssueImage(path, null, "Image missing: $path")
}

/** Blocking; see [MagazineRepository.article]. */
internal fun readArticle(issueDir: File, article: MagazineArticle): ArticleContent {
    val file = resolveInIssue(issueDir, article.body) ?: return ArticleContent.Failed("${article.body} leads outside this issue's folder.")
    return try {
        if (!file.isFile) return ArticleContent.Failed("${article.body} is missing from this issue.")
        if (file.length() > MAX_ARTICLE_BYTES) return ArticleContent.Failed("${article.body} is larger than ${MAX_ARTICLE_BYTES / 1024} KB.")
        val blocks = parseMarkdown(file.readText())
        if (blocks.isEmpty()) return ArticleContent.Failed("${article.body} is empty.")
        val images = blocks.filterIsInstance<MdBlock.Image>().associate { b ->
            b.src to (imageIn(issueDir, b.src) ?: IssueImage(b.src, null, "Image left out: only pictures inside this issue's folder are shown"))
        }
        ArticleContent.Ready(blocks, images)
    } catch (e: IOException) {
        ArticleContent.Failed("Could not read ${article.body}: ${e.message}")
    } catch (e: SecurityException) {
        ArticleContent.Failed("Could not read ${article.body}: ${e.message}")
    }
}
