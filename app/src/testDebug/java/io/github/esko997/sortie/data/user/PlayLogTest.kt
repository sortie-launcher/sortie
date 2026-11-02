package io.github.esko997.sortie.data.user

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.data.db.SortieDb
import io.github.esko997.sortie.data.db.playedMs
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Play time is active time: [PlayLog] against a real Room database, its inputs driven the way the screen receiver, the
 * navigation's window focus and the switcher overlay drive them on a device, with a hand-moved clock.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PlayLogTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dir = Files.createTempDirectory("sortie-playlog").toFile()
    private val file = File(dir, "playlog.jsonl")
    private lateinit var db: SortieDb
    private var now = 1_000_000_000L

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, SortieDb::class.java).build()
    }

    @After
    fun close() {
        db.close()
        dir.deleteRecursively()
    }

    private fun log(on: SortieDb = db) = PlayLog(on.games(), file, "thor", clock = { now })

    private fun minutes(m: Long) {
        now += m * MINUTE
    }

    /** The `minutes` of every `end` in playlog.jsonl, in order. */
    private fun ends(): List<Long?> = file.readLines().map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["event"]!!.jsonPrimitive.content == "end" }
        .map { it["minutes"]!!.jsonPrimitive.longOrNull }

    @Test
    fun sleepSwitcherAndNavigationPauseWhileWakingStraightIntoTheGameCounts() = runBlocking {
        val log = log()
        log.navigationFocused(true)?.join()
        log.start(GAME, "armsx2", "same") // started from the focused navigation: paused until the game takes focus
        minutes(1)
        log.navigationFocused(false)?.join()
        minutes(30)
        log.screenOn(false)?.join()
        minutes(8 * 60) // the device sleeps overnight with the emulator open
        log.screenOn(true)?.join() // waking returns straight to the game; Sortie never comes forward
        minutes(15)
        log.switcherOverlay(true)?.join() // Home: the switcher overlay over the running game
        minutes(5)
        log.switcherOverlay(false)?.join()
        minutes(4)
        log.navigationFocused(true)?.join() // back in Sortie with the game still running
        assertEquals(49 * MINUTE, log.current.value!!.playedMs(now)) // what the companion shows
        minutes(20)
        log.end()
        assertEquals(listOf<Long?>(49), ends())
        assertEquals(49, db.games().playStat(GAME)!!.minutes)
    }

    @Test
    fun aLaunchWhileAnotherRunsClosesItWithItsPlayedTime() = runBlocking {
        val log = log()
        log.start(GAME, "armsx2", "same") // from the companion: no navigation has focus, so it counts at once
        minutes(40)
        log.start(OTHER, "ppsspp", "same")
        minutes(10)
        log.end()
        assertEquals(listOf<Long?>(40, 10), ends())
        assertEquals(mapOf(GAME to 40, OTHER to 10), db.games().playStats().first().associate { it.gameId to it.minutes })
        assertTrue(db.games().allSessions().none { it.abandoned })
    }

    @Test
    fun aRestartLosesOnlyTheRunningIntervalAndWaitsForTheNextSignal() = runBlocking {
        val before = log()
        before.start(GAME, "armsx2", "same")
        minutes(20)
        before.screenOn(false)?.join() // the 20 minutes are on the row now
        before.screenOn(true)?.join()
        minutes(30) // Sortie's process dies during this interval: its end is unknown, so it is lost
        val after = log()
        val seeded = after.current.first { it != null }!!
        assertEquals(20 * MINUTE, seeded.activeMs)
        assertNull(seeded.activeSince)
        minutes(60) // nothing reported yet: no time is invented
        assertEquals(20 * MINUTE, after.current.value!!.playedMs(now))
        after.screenOn(false)?.join()
        minutes(5)
        after.screenOn(true)?.join() // the next wake resumes
        minutes(10)
        after.end()
        assertEquals(listOf<Long?>(30), ends())
    }

    /** Version 4 rows (no active time) keep their wall-clock minutes; a session left open across the upgrade adds none. */
    @Test
    fun upgradedHistoryKeepsItsMinutesAndAnOpenSessionStartsAtZero() = runBlocking {
        val schema = Json.parseToJsonElement(File("schemas/io.github.esko997.sortie.data.db.SortieDb/4.json").readText())
            .jsonObject["database"]!!.jsonObject
        val path = context.getDatabasePath("sortie.db").apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (e in schema["entities"]!!.jsonArray) {
                val table = e.jsonObject["tableName"]!!.jsonPrimitive.content
                val create = listOf(e.jsonObject["createSql"]!!) + e.jsonObject["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!! }
                create.forEach { old.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
            }
            schema["setupQueries"]!!.jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
            old.execSQL("INSERT INTO sessions (gameId, profileId, startedAt, endedAt, abandoned) VALUES ('$GAME', NULL, 0, ${90 * MINUTE}, 0)")
            old.execSQL("INSERT INTO sessions (gameId, profileId, startedAt, endedAt, abandoned) VALUES ('$OTHER', NULL, ${now - 600 * MINUTE}, NULL, 0)")
            old.version = 4
        }
        val upgraded = SortieDb.open(context)
        try {
            val dao = upgraded.games()
            dao.insertSession(SessionEntity(gameId = GAME, profileId = null, startedAt = 100 * MINUTE, endedAt = 400 * MINUTE, activeMs = 25 * MINUTE))
            assertEquals(115, dao.playStat(GAME)!!.minutes)
            val log = log(upgraded)
            log.current.first { it != null }
            log.end()
            assertEquals(listOf<Long?>(0), ends())
            assertEquals(0, dao.playStat(OTHER)!!.minutes)
        } finally {
            upgraded.close()
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val GAME = "ps2/Onimusha - Dawn of Dreams (USA).chd"
        const val OTHER = "psp/ClaDun x2 (USA).iso"
    }
}
