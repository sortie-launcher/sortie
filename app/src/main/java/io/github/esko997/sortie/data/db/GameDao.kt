package io.github.esko997.sortie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SystemCount(val systemId: String, val n: Int)

data class PlayStat(val gameId: String, val minutes: Int, val lastPlayed: Long?)

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<GameEntity>)

    @Query("DELETE FROM games WHERE systemId = :systemId AND scanGen <> :gen")
    suspend fun deleteStale(systemId: String, gen: Long): Int

    @Query("UPDATE games SET scanGen = :gen WHERE systemId = :systemId AND id IN (:ids)")
    suspend fun touch(systemId: String, gen: Long, ids: List<String>)

    @Query("SELECT id, mtime, size, addedAt FROM games WHERE systemId = :systemId")
    suspend fun signatures(systemId: String): List<Signature>

    @Query("SELECT systemId, COUNT(*) AS n FROM games WHERE placeholder = 0 GROUP BY systemId")
    fun countsBySystem(): Flow<List<SystemCount>>

    @Query("SELECT * FROM games WHERE systemId = :systemId ORDER BY sortTitle")
    fun bySystem(systemId: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: String): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: String): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<GameEntity>

    @Query(
        "SELECT * FROM games WHERE placeholder = 0 AND (title LIKE '%' || :q || '%' OR genre LIKE '%' || :q || '%' OR developer LIKE '%' || :q || '%' OR desc LIKE '%' || :q || '%') ORDER BY fit DESC, sortTitle LIMIT :limit"
    )
    fun search(q: String, limit: Int = 200): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE placeholder = 0")
    suspend fun allPlayable(): List<GameEntity>

    @Query("SELECT COUNT(*) FROM games")
    fun total(): Flow<Int>

    @Insert
    suspend fun insertSession(s: SessionEntity): Long

    @Update
    suspend fun updateSession(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun openSession(): SessionEntity?

    @Query(
        "SELECT gameId, CAST(SUM(COALESCE(endedAt, startedAt) - startedAt) / 60000 AS INTEGER) AS minutes, MAX(startedAt) AS lastPlayed FROM sessions GROUP BY gameId"
    )
    fun playStats(): Flow<List<PlayStat>>

    @Query(
        "SELECT gameId, CAST(SUM(COALESCE(endedAt, startedAt) - startedAt) / 60000 AS INTEGER) AS minutes, MAX(startedAt) AS lastPlayed FROM sessions GROUP BY gameId ORDER BY lastPlayed DESC LIMIT :limit"
    )
    fun recentlyPlayed(limit: Int = 30): Flow<List<PlayStat>>
}

data class Signature(val id: String, val mtime: Long, val size: Long, val addedAt: Long)
