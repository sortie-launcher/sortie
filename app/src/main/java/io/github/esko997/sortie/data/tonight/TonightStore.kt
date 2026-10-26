package io.github.esko997.sortie.data.tonight

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tonight's chosen sets, by local date, in one small app-private JSON file (device-local like the reading positions; not part of
 * user.json or backups). The first request for a date chooses its set and saves it before returning; every later request for
 * that date — after a restart, a re-index or new play history — gets the saved set unchanged, so nothing is ever re-rolled.
 * Only the newest [KEPT_DAYS] dates are kept: today's, and yesterday's for a visit that started before midnight and is still
 * open (its screen asks for the date it opened on). One instance per process; both windows share it.
 */
class TonightStore(private val file: File) {
    private val lock = Mutex()
    private var days: List<TonightDay>? = null

    /**
     * The saved set for [date], or [choose]'s result saved as that date's set. An empty result (nothing playable yet, say before
     * the first index) is not saved, so a later request that day can choose once there is a library. A failed save is reported
     * to the caller rather than presenting an unpersisted selection as the day's fixed set.
     */
    suspend fun day(date: LocalDate, choose: suspend () -> List<TonightPick>): TonightDay = withContext(Dispatchers.IO) {
        lock.withLock {
            val known = days ?: read().also { days = it }
            val key = date.toString()
            known.firstOrNull { it.date == key }?.let { return@withLock it }
            val chosen = TonightDay(key, choose())
            if (chosen.picks.isEmpty()) return@withLock chosen
            val next = (known + chosen).sortedByDescending { it.date }.take(KEPT_DAYS)
            write(next)
            days = next
            chosen
        }
    }

    private fun read(): List<TonightDay> {
        if (!file.isFile) return emptyList()
        return try {
            json.decodeFromString(TonightFile.serializer(), file.readText()).days
                // a newer build's role is dropped rather than shown under a wrong label
                .map { d -> d.copy(picks = d.picks.filter { it.roleOf != null }) }
        } catch (e: Exception) {
            // derived, private state: an unreadable file only costs the saved sets, which are chosen again
            Log.w(TAG, "read ${file.path}", e)
            emptyList()
        }
    }

    private fun write(days: List<TonightDay>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(TonightFile.serializer(), TonightFile(days = days)).toByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("could not replace ${file.path}")
    }

    @Serializable
    private data class TonightFile(val version: Int = 1, val days: List<TonightDay> = emptyList())

    private companion object {
        const val TAG = "SortieTonight"
        const val KEPT_DAYS = 2
        val json = Json { ignoreUnknownKeys = true }
    }
}
