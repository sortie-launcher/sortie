package io.github.esko997.sortie.data.index

import java.util.Locale

/**
 * Groups files that are the same game: later discs of a multi-disc set, and the same stem in a second container format
 * (`.pbp` next to `.chd`, `.7z` next to `.zip`). Returns id -> id of the primary file to show instead.
 * Pure function over (id, fileName) pairs so it is unit-testable and knows nothing about the device.
 */
object Variants {
    private val DISC_RX = Regex("""\s*[(\[](?:Disc|Disk|CD|Side)\s*(\d+)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val TRAILING_TAGS = Regex("""\s*[(\[][^)\]]*[)\]]""")

    /** Lower rank wins when two files share a game key. */
    private val FORMAT_RANK = mapOf(
        "chd" to 0, "iso" to 1, "cso" to 1, "ciso" to 1, "rvz" to 1, "wbfs" to 2, "gcz" to 2, "cue" to 3, "gdi" to 3, "cdi" to 4, "m3u" to 0,
        "pbp" to 5, "bin" to 6, "zip" to 1, "7z" to 2
    )

    data class Entry(val id: String, val fileName: String, val playable: Boolean)

    fun resolve(entries: List<Entry>): Map<String, String> {
        val out = HashMap<String, String>()
        // 1) multi-disc: every "Disc N>1" points at Disc 1 of the same title (same tags otherwise)
        val byDiscKey = entries.groupBy { discKey(it.fileName) }
        for ((key, group) in byDiscKey) {
            if (key == null || group.size < 2) continue
            val primary = group.minByOrNull { discNumber(it.fileName) ?: 1 } ?: continue
            group.filter { it !== primary }.forEach { out[it.id] = primary.id }
        }
        // 2) same stem (tags included, disc tag stripped) in two container formats
        val byStem = entries.filter { it.id !in out }.groupBy { stemKey(it.fileName) }
        for ((_, group) in byStem) {
            if (group.size < 2) continue
            val primary = group.sortedWith(
                compareBy<Entry> { if (it.playable) 0 else 1 }.thenBy { FORMAT_RANK[ext(it.fileName)] ?: 9 }.thenBy { it.fileName }
            ).first()
            group.filter { it !== primary }.forEach { out[it.id] = primary.id }
        }
        return out
    }

    fun discNumber(fileName: String): Int? = DISC_RX.find(fileName)?.groupValues?.get(1)?.toIntOrNull()

    private fun discKey(fileName: String): String? {
        if (discNumber(fileName) == null) return null
        return fileName.substringBeforeLast('.').replace(DISC_RX, "").trim().lowercase(Locale.ROOT)
    }

    private fun stemKey(fileName: String): String = fileName.substringBeforeLast('.').replace(DISC_RX, "").trim().lowercase(Locale.ROOT)

    private fun ext(fileName: String) = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)

    /** Display title for a disc entry when the primary is shown: "Colony Wars · 2 discs". */
    fun stripTags(stem: String): String = stem.replace(TRAILING_TAGS, "").trim()
}
