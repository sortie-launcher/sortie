package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.SessionEntity
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Sessions in Room for queries, mirrored as JSON lines to `<root>/sortie/playlog.jsonl` for the desktop. */
class PlayLog(private val dao: GameDao, private val file: File, private val device: String) {
    suspend fun start(gameId: String, profileId: String?, display: String) {
        closeOpen(abandoned = true)
        dao.insertSession(
            SessionEntity(
                gameId = gameId,
                profileId = profileId,
                startedAt = System.currentTimeMillis(),
                endedAt = null
            )
        )
        append(mapOf("event" to "start", "id" to gameId, "profile" to (profileId ?: ""), "display" to display))
    }

    /** Called when Sortie regains the foreground. */
    suspend fun end() = closeOpen(abandoned = false)

    private suspend fun closeOpen(abandoned: Boolean) {
        val open = dao.openSession() ?: return
        val now = System.currentTimeMillis()
        dao.updateSession(open.copy(endedAt = now))
        val minutes = ((now - open.startedAt) / 60000).toInt()
        append(
            mapOf(
                "event" to "end",
                "id" to open.gameId,
                "minutes" to minutes.toString(),
                "abandoned" to abandoned.toString()
            )
        )
    }

    private fun append(fields: Map<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = buildJsonObject {
                put("ts", JsonPrimitive(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                put("device", JsonPrimitive(device))
            }
            file.appendText(obj.toString() + "\n")
        }
    }
}
