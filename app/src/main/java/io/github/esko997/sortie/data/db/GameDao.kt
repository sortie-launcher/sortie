package io.github.esko997.sortie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SystemCount(val systemId: String, val n: Int)

data class PlayStat(val gameId: String, val minutes: Int, val lastPlayed: Long?, val sessions: Int = 0)

data class TileCover(val systemId: String, val cover: String, val fit: Int)

data class NameRow(val id: String, val fileName: String, val placeholder: Boolean)

data class Signature(val id: String, val mtime: Long, val size: Long, val addedAt: Long, val arriving: Boolean)

/** SQLite binds at most 999 variables on older Android builds; every `IN (:ids)` goes through [chunked]. */
const val SQLITE_CHUNK = 500

suspend fun <T> chunked(ids: Collection<String>, block: suspend (List<String>) -> List<T>): List<T> = ids.chunked(SQLITE_CHUNK).flatMap { block(it) }

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM games WHERE systemId NOT IN (:systemIds)")
    suspend fun deleteSystemsNotIn(systemIds: List<String>): Int

    @Query("UPDATE games SET arriving = :arriving WHERE id IN (:ids)")
    suspend fun setArriving(ids: List<String>, arriving: Boolean)

    @Query("UPDATE games SET variantOf = NULL WHERE systemId = :systemId")
    suspend fun clearVariants(systemId: String)

    @Query("UPDATE games SET variantOf = :primary WHERE id IN (:ids)")
    suspend fun setVariantOf(ids: List<String>, primary: String)

    @Query("SELECT id, fileName, placeholder FROM games WHERE systemId = :systemId")
    suspend fun namesOf(systemId: String): List<NameRow>

    @Query("SELECT * FROM games WHERE variantOf = :primary ORDER BY fileName")
    suspend fun variantsOf(primary: String): List<GameEntity>

    @Query("SELECT id, mtime, size, addedAt, arriving FROM games WHERE systemId = :systemId")
    suspend fun signatures(systemId: String): List<Signature>

    @Query("SELECT systemId, COUNT(*) AS n FROM games WHERE placeholder = 0 AND variantOf IS NULL GROUP BY systemId")
    fun countsBySystem(): Flow<List<SystemCount>>

    @Query("SELECT * FROM games WHERE systemId = :systemId AND variantOf IS NULL ORDER BY sortTitle")
    fun bySystem(systemId: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: String): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: String): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id IN (:ids)")
    suspend fun byIdsChunk(ids: List<String>): List<GameEntity>

    @Query(
        "SELECT * FROM games WHERE placeholder = 0 AND variantOf IS NULL AND (title LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR genre LIKE '%' || :q || '%' ESCAPE '\\' " +
            "OR developer LIKE '%' || :q || '%' ESCAPE '\\' OR desc LIKE '%' || :q || '%' ESCAPE '\\') ORDER BY fit DESC, sortTitle LIMIT :limit"
    )
    fun search(q: String, limit: Int = 200): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE placeholder = 0 AND variantOf IS NULL")
    suspend fun allPlayable(): List<GameEntity>

    @Query("SELECT * FROM games WHERE placeholder = 0 AND variantOf IS NULL AND fit >= 4 AND screenshot IS NOT NULL ORDER BY RANDOM() LIMIT 120")
    suspend fun heroes(): List<GameEntity>

    /** One cover per system for the home tiles: the best-rated game that has one. */
    @Query("SELECT systemId, cover, MAX(fit) AS fit FROM games WHERE cover IS NOT NULL AND placeholder = 0 GROUP BY systemId")
    fun tileCovers(): Flow<List<TileCover>>

    @Query("SELECT COUNT(*) FROM games WHERE placeholder = 0 AND variantOf IS NULL")
    fun total(): Flow<Int>

    @Query("SELECT * FROM index_meta WHERE systemId = :systemId")
    suspend fun meta(systemId: String): IndexMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: IndexMetaEntity)

    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Update
    suspend fun updateSession(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query(
        "SELECT gameId, CAST(SUM(CASE WHEN abandoned = 0 AND endedAt IS NOT NULL THEN endedAt - startedAt ELSE 0 END) / 60000 AS INTEGER) AS minutes, " +
            "MAX(startedAt) AS lastPlayed, COUNT(*) AS sessions FROM sessions GROUP BY gameId"
    )
    fun playStats(): Flow<List<PlayStat>>

    @Query(
        "SELECT gameId, CAST(SUM(CASE WHEN abandoned = 0 AND endedAt IS NOT NULL THEN endedAt - startedAt ELSE 0 END) / 60000 AS INTEGER) AS minutes, " +
            "MAX(startedAt) AS lastPlayed, COUNT(*) AS sessions FROM sessions WHERE gameId = :id GROUP BY gameId"
    )
    suspend fun playStat(id: String): PlayStat?

    @Query(
        "SELECT gameId, CAST(SUM(CASE WHEN abandoned = 0 AND endedAt IS NOT NULL THEN endedAt - startedAt ELSE 0 END) / 60000 AS INTEGER) AS minutes, " +
            "MAX(startedAt) AS lastPlayed, COUNT(*) AS sessions FROM sessions GROUP BY gameId ORDER BY lastPlayed DESC LIMIT :limit"
    )
    fun recentlyPlayed(limit: Int = 30): Flow<List<PlayStat>>
}

suspend fun GameDao.byIds(ids: Collection<String>): List<GameEntity> = chunked(ids) { byIdsChunk(it) }

/** Escapes LIKE wildcards in user input (the queries use ESCAPE '\'). */
fun likeEscape(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
