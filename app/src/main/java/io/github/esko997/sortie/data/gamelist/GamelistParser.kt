package io.github.esko997.sortie.data.gamelist

import java.io.File
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class GamelistEntry(
    val path: String,
    val name: String?,
    val desc: String?,
    val rating: Float?,
    val releaseYear: Int?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val players: String?
)

/** Minimal ES-DE gamelist.xml reader. Only the fields Sortie displays; everything else is skipped. */
object GamelistParser {
    fun parse(file: File): Map<String, GamelistEntry> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<String, GamelistEntry>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        file.inputStream().buffered().use { input ->
            parser.setInput(input, null)
            var fields: HashMap<String, String>? = null
            var current: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "game" -> fields = HashMap()
                        else -> if (fields != null) current = parser.name
                    }
                    XmlPullParser.TEXT -> if (fields != null && current != null) fields[current] = (fields[current] ?: "") + parser.text
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "game" -> {
                            fields?.toEntry()?.let { out[it.path] = it }
                            fields = null
                        }
                        else -> current = null
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun Map<String, String>.toEntry(): GamelistEntry? {
        val raw = this["path"]?.trim() ?: return null
        val path = raw.removePrefix("./").substringAfterLast('/')
        return GamelistEntry(
            path = path,
            name = this["name"]?.trim(),
            desc = this["desc"]?.trim(),
            rating = this["rating"]?.trim()?.toFloatOrNull(),
            releaseYear = this["releasedate"]?.trim()?.take(4)?.toIntOrNull(),
            developer = this["developer"]?.trim(),
            publisher = this["publisher"]?.trim(),
            genre = this["genre"]?.trim(),
            players = this["players"]?.trim()
        )
    }
}
