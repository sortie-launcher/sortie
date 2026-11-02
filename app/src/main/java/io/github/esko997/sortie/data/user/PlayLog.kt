package io.github.esko997.sortie.data.user

import android.content.Intent
import io.github.esko997.sortie.data.db.GameDao
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.db.playedMs
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Sessions in Room for queries, mirrored as JSON lines to `<root>/sortie/playlog.jsonl` (docs/formats/playlog.md).
 * A session's time is its *active* time: the open session runs only while the screen is on and unlocked, and neither is
 * Sortie's navigation the top resumed activity nor its switcher overlay up ([active]). Each change folds the running
 * interval into [SessionEntity.activeMs] ([timed]), so the row always holds every finished interval; a process death loses
 * at most the running one, and a session found open at startup stays paused until the next input change.
 * [current] mirrors the open session (null when nothing is being played) for the companion's "now playing" panel.
 */
class PlayLog(
    private val dao: GameDao,
    private val file: File,
    private val device: String,
    screenOn: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _current = MutableStateFlow<SessionEntity?>(null)

    /** The open session, or null. Seeded from the database once, then updated by [start], [end] and every pause or resume. */
    val current: StateFlow<SessionEntity?> = _current

    private data class Inputs(val screenOn: Boolean, val navigationInFront: Boolean = false, val switcherOverlay: Boolean = false)

    private val inputs = MutableStateFlow(Inputs(screenOn))

    init {
        // undispatched: the lock is taken before the constructor returns, so nothing can start or end a session before this
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                runCatching {
                    _current.value = dao.openSession()?.let { open ->
                        // the running interval's end is unknown: drop it (never add time); older rows start counting from here
                        open.copy(activeMs = open.activeMs ?: 0, activeSince = null).also { if (it != open) dao.updateSession(it) }
                    }
                }
            }
        }
    }

    /** The screen is on and past the lock screen, or not ([screenInUse]; SortieApp's receiver). The job is for tests. */
    fun screenOn(on: Boolean): Job? = input { it.copy(screenOn = on) }

    /** Whether one of Sortie's navigation windows is the top resumed activity (MainActivity). */
    fun navigationInFront(inFront: Boolean): Job? = input { it.copy(navigationInFront = inFront) }

    /** Whether the switcher overlay is up over the running app (SortieApp, from TaskSwitcher's state). */
    fun switcherOverlay(open: Boolean): Job? = input { it.copy(switcherOverlay = open) }

    private fun input(change: (Inputs) -> Inputs): Job? {
        val before = inputs.getAndUpdate(change)
        if (change(before) == before) return null
        return scope.launch { mutex.withLock { runCatching { retime() } } }
    }

    /** The single rule: the open session is being played. */
    private fun active(): Boolean = inputs.value.let { it.screenOn && !it.navigationInFront && !it.switcherOverlay }

    private suspend fun retime() {
        val open = dao.openSession() ?: return
        val timed = open.timed(clock(), active())
        if (timed != open) dao.updateSession(timed)
        _current.value = timed
    }

    /** Opens a session for [gameId], closing any open one with the time it was played. */
    suspend fun start(gameId: String, profileId: String?, display: String) = mutex.withLock {
        closeOpen()
        val now = clock()
        val session = SessionEntity(gameId = gameId, profileId = profileId, startedAt = now, endedAt = null, activeMs = 0).timed(now, active())
        val rowId = dao.insertSession(session)
        _current.value = session.copy(rowId = rowId)
        append("start", gameId, mapOf("profile" to JsonPrimitive(profileId ?: ""), "display" to JsonPrimitive(display)))
    }

    /** Called when Sortie regains the foreground and the session's app is gone. */
    suspend fun end() = mutex.withLock { closeOpen() }

    /**
     * The open session's app closed right after starting ([io.github.esko997.sortie.launch.SessionReturn.QUICK_EXIT]): nothing
     * was played, so the session leaves the history (no time, session count, "last played" or salvage credit). `playlog.jsonl`
     * keeps its `start` and gets an `end` with `minutes: null` and `quickExit: true`.
     */
    suspend fun endQuickExit() = mutex.withLock {
        val open = dao.openSession()
        _current.value = null
        if (open == null) return@withLock
        dao.deleteSessionsChunk(listOf(open.rowId))
        append("end", open.gameId, mapOf("minutes" to JsonNull, "quickExit" to JsonPrimitive(true)))
    }

    /**
     * Runs [block] with session logging held: a [start] or [end] arriving meanwhile waits until it returns, so a backup sees
     * (and a restore writes) the sessions table without a session opening or closing underneath. [block] gets the open session.
     */
    suspend fun <T> paused(block: suspend (open: SessionEntity?) -> T): T = mutex.withLock { block(dao.openSession()) }

    private suspend fun closeOpen() {
        val open = dao.openSession()
        _current.value = null
        if (open == null) return
        val now = clock()
        val closed = open.timed(now, active = false).copy(endedAt = now)
        dao.updateSession(closed)
        append("end", open.gameId, mapOf("minutes" to JsonPrimitive(closed.playedMs(now) / 60000)))
    }

    private suspend fun append(event: String, id: String, fields: Map<String, kotlinx.serialization.json.JsonElement>) = withContext(Dispatchers.IO) {
        runCatching {
            // best-effort sidecar (the database holds the history): never recreates a missing library folder
            if (file.parentFile?.isDirectory != true) return@runCatching
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

/** [this] with an active interval running from [now] exactly when [active]; stopping one adds it to [SessionEntity.activeMs]. */
internal fun SessionEntity.timed(now: Long, active: Boolean): SessionEntity = when {
    active == (activeSince != null) -> this
    active -> copy(activeSince = now)
    else -> copy(activeMs = playedMs(now), activeSince = null)
}

/**
 * Whether a screen broadcast leaves the screen in use for play: unlocked ([Intent.ACTION_USER_PRESENT]), or lit without the
 * lock screen up (a wake within the lock delay sends no USER_PRESENT). Screen off, or lit on the lock screen, is not.
 */
internal fun screenInUse(action: String?, keyguardLocked: Boolean): Boolean =
    action == Intent.ACTION_USER_PRESENT || (action == Intent.ACTION_SCREEN_ON && !keyguardLocked)
