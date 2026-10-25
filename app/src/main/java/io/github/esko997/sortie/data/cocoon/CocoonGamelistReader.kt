package io.github.esko997.sortie.data.cocoon

import java.io.File
import java.io.IOException
import java.io.StringReader
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** One <game> of a Cocoon gamelist. Times are epoch millis (Cocoon writes local "yyyyMMdd'T'HHmmss"; parse with system default zone). */
data class CocoonGame(
    /** `<path>` with "./" removed, last path segment only (as GamelistParser does). */
    val file: String,
    val name: String?,
    val favourite: Boolean,
    val hidden: Boolean,
    /** `<playcount>`, 0 when absent. */
    val playCount: Int,
    val lastPlayed: Long?,
    /** `<cocoon:playtime>`, 0 when absent. */
    val playMinutes: Int,
    /** Raw `<cocoon:boxartlocal>` reference (usually a content URI). */
    val boxArt: String?,
    /** Raw `<cocoon:logolocal>`. */
    val logo: String?,
    /** Raw `<cocoon:screenshotgameplaylocal>`. */
    val screenshot: String?
)

/** One gamelists/<platform>/gamelist.xml. [id] is the folder name; [defaultPlayer] is the raw <cocoon:defaultplayerid>, null for "__none__"/absent. */
data class CocoonPlatform(val id: String, val defaultPlayer: String?, val games: List<CocoonGame>, val file: File)

/** Everything read from <cocoonDir>/gamelists. [dir] is the Cocoon directory; [problems] lists per-file failures (the rest still loads). */
data class CocoonLibrary(val dir: File, val platforms: List<CocoonPlatform>, val problems: List<String>)

/**
 * Reader for Cocoon's gamelist.xml: an XmlPullParser loop like data.gamelist.GamelistParser, first over the file exactly as
 * written; only when that fails are bare `&` characters escaped in memory and the text parsed again. Sortie's strict
 * GamelistParser is NOT changed. The non-namespace-aware parser takes the undeclared `cocoon:` prefix as part of the tag
 * name. The file on disk is never touched.
 */
object CocoonGamelistReader {
    private const val NONE = "__none__"
    private const val GAMELISTS = "gamelists"
    private const val GAMELIST = "gamelist.xml"

    /** Enough of a gamelist to hold the XML declaration, the root and `<cocoon:defaultplayerid>`. */
    private const val SIGNATURE_BYTES = 512

    /**
     * Cocoon writes `<path>` (and sometimes names) without escaping, so `&` that does not start one of XML's five predefined
     * entities or a character reference is literal text: `Rock &Roll; Racing` or `Caf&eacute;` would otherwise stay unresolvable.
     */
    private val REPAIR_TOKEN = Regex(
        "<!\\[CDATA\\[.*?]]>|<!--.*?-->|<\\?.*?\\?>|&(?!(?:amp|lt|gt|quot|apos);|#[0-9]+;|#x[0-9a-fA-F]+;)",
        RegexOption.DOT_MATCHES_ALL
    )

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.ROOT)

    /** Parses [file] as written; a file the parser rejects is parsed once more after [repair]. Throws when both fail. */
    @Throws(IOException::class)
    fun read(file: File, platformId: String = file.absoluteFile.parentFile?.name ?: file.nameWithoutExtension): CocoonPlatform {
        val raw = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        return try {
            parse(raw, platformId, file)
        } catch (_: XmlPullParserException) {
            try {
                parse(repair(raw), platformId, file)
            } catch (e: XmlPullParserException) {
                throw IOException("${file.path}: ${e.message}", e)
            }
        }
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun parse(xml: String, platformId: String, file: File): CocoonPlatform {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        val top = HashMap<String, String>()
        val games = ArrayList<CocoonGame>()
        parser.setInput(StringReader(xml))
        var fields: HashMap<String, String>? = null
        var current: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when {
                    parser.name == "game" -> fields = HashMap()
                    fields != null -> current = parser.name
                    // children of the root (cocoon:defaultplayerid) live outside every <game>
                    parser.depth == 2 -> current = parser.name
                }
                XmlPullParser.TEXT -> current?.let { tag ->
                    val into = fields ?: top
                    into[tag] = (into[tag] ?: "") + parser.text
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "game" -> {
                        fields?.toGame()?.let { games += it }
                        fields = null
                        current = null
                    }
                    else -> current = null
                }
            }
            event = parser.next()
        }
        val player = top["cocoon:defaultplayerid"]?.trim()?.takeIf { it.isNotEmpty() && it != NONE }
        return CocoonPlatform(platformId, player, games, file)
    }

    /** Reads every `gamelists/<platform>/gamelist.xml`; one unreadable file is reported in [CocoonLibrary.problems], never thrown. */
    fun readLibrary(cocoonDir: File): CocoonLibrary {
        val dirs = File(cocoonDir, GAMELISTS).listFiles()
            ?: return CocoonLibrary(cocoonDir, emptyList(), listOf("${File(cocoonDir, GAMELISTS).path} is missing or unreadable."))
        val platforms = ArrayList<CocoonPlatform>()
        val problems = ArrayList<String>()
        for (dir in dirs.sortedBy { it.name }) {
            if (dir.name.startsWith(".") || !dir.isDirectory) continue
            val file = File(dir, GAMELIST)
            if (!file.isFile) continue
            try {
                platforms += read(file, dir.name)
            } catch (e: Exception) {
                // any parser or I/O failure loses this platform only
                problems += "${dir.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return CocoonLibrary(cocoonDir, platforms, problems)
    }

    /**
     * The directory holds a Cocoon library: `gamelists/<x>/gamelist.xml` whose head carries Cocoon's `cocoon:` extension tags.
     * An ES-DE tree (`scraped-esde/gamelists/…`) has the same layout but plain ES-DE files, and must not be offered as Cocoon.
     */
    fun looksLikeCocoonDir(dir: File): Boolean =
        File(dir, GAMELISTS).listFiles()?.any { !it.name.startsWith(".") && isCocoonGamelist(File(it, GAMELIST)) } == true

    /** Cocoon writes `<cocoon:defaultplayerid>` right after the root element, so the signature sits in the first bytes. */
    private fun isCocoonGamelist(file: File): Boolean {
        if (!file.isFile) return false
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(SIGNATURE_BYTES)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (_: IOException) {
            return false
        }
        return "<cocoon:" in head
    }

    /** Repairs text/attribute ampersands, leaving literal CDATA, comments and processing instructions untouched. */
    internal fun repair(raw: String): String = REPAIR_TOKEN.replace(raw.removePrefix("\uFEFF")) { if (it.value == "&") "&amp;" else it.value }

    /** Cocoon's local "yyyyMMdd'T'HHmmss" in the device's zone, as epoch millis; null when absent or not that shape. */
    internal fun parseTime(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDateTime.parse(text, STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun Map<String, String>.toGame(): CocoonGame? {
        val file = this["path"]?.trim()?.removePrefix("./")?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return null
        fun text(tag: String) = this[tag]?.trim()?.takeIf { it.isNotEmpty() }
        return CocoonGame(
            file = file,
            name = text("name"),
            favourite = text("favorite").equals("true", ignoreCase = true),
            hidden = text("hidden").equals("true", ignoreCase = true),
            playCount = text("playcount")?.toIntOrNull() ?: 0,
            lastPlayed = parseTime(text("lastplayed")),
            playMinutes = text("cocoon:playtime")?.toIntOrNull() ?: 0,
            boxArt = text("cocoon:boxartlocal"),
            logo = text("cocoon:logolocal"),
            screenshot = text("cocoon:screenshotgameplaylocal")
        )
    }
}
