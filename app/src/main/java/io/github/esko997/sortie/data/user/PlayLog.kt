package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.SessionEntity
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Sessions in Room for queries, mirrored as JSON lines to `<root>/sortie/playlog.jsonl` (docs/formats/playlog.md).
 * A session Sortie never saw end (process killed, device slept) is closed as abandoned with `minutes: null` and not counted.
 * [current] mirrors the open session (null when nothing is being played) for the companion's "now playing" panel.
 */
class PlayLog(private val dao: GameDao, private val file: File, private val device: String) {
    private val mutex = Mutex()

    private val _current = MutableStateFlow<SessionEntity?>(null)

    /** The open session, or null. Seeded from the database once, then updated by [start] and [end]. */
    val current: StateFlow<SessionEntity?> = _current

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            mutex.withLock { runCatching { _current.value = dao.openSession() } }
        }
    }

    suspend fun start(gameId: String, profileId: String?, display: String) = mutex.withLock {
        closeOpen(abandoned = true)
        val session = SessionEntity(gameId = gameId, profileId = profileId, startedAt = System.currentTimeMillis(), endedAt = null)
        val rowId = dao.insertSession(session)
        _current.value = session.copy(rowId = rowId)
        append("start", gameId, mapOf("profile" to JsonPrimitive(profileId ?: ""), "display" to JsonPrimitive(display)))
    }

    /** Called when Sortie regains the foreground. */
    suspend fun end() = mutex.withLock { closeOpen(abandoned = false) }

    /**
     * Runs [block] with session logging held: a [start] or [end] arriving meanwhile waits until it returns, so a backup sees
     * (and a restore writes) the sessions table without a session opening or closing underneath. [block] gets the open session.
     */
    suspend fun <T> paused(block: suspend (open: SessionEntity?) -> T): T = mutex.withLock { block(dao.openSession()) }

    private suspend fun closeOpen(abandoned: Boolean) {
        val open = dao.openSession()
        _current.value = null
        if (open == null) return
        val now = System.currentTimeMillis()
        dao.updateSession(open.copy(endedAt = now, abandoned = abandoned))
        val minutes = if (abandoned) JsonNull else JsonPrimitive(((now - open.startedAt) / 60000).toInt())
        append("end", open.gameId, mapOf("minutes" to minutes))
    }

    private suspend fun append(event: String, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            val obj = buildJsonObject {
                put("ts", JsonPrimitive(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                put("event", JsonPrimitive(event))
                put("id", JsonPrimitive(id))
                fields.forEach { (k, v) -> put(k, v) }
                put("device", JsonPrimitive(device))
            }
            file.appendText(obj.toString() + "\n")
        }
    }
}
