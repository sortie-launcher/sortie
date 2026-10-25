package io.github.esko997.sortie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class SystemCount(val systemId: String, val n: Int)

data class PlayStat(val gameId: String, val minutes: Int, val lastPlayed: Long?, val sessions: Int = 0)

data class TileCover(val systemId: String, val cover: String, val fit: Int)

data class NameRow(val id: String, val fileName: String, val placeholder: Boolean)

/** The art a game row already has, keyed by where [io.github.esko997.sortie.data.index.Indexer] looks for it (system + file stem). */
data class MediaRow(val systemId: String, val fileName: String, val cover: String?, val screenshot: String?, val marquee: String?)

/** What the indexer compares to decide whether a row is current: the file's identity, where it lives and how it is grouped. */
data class Signature(val id: String, val path: String, val folder: String, val mtime: Long, val size: Long, val addedAt: Long, val arriving: Boolean)

/** What magazine reference matching needs of a game row (see data.magazine.MagazineGames). */
data class MatchRow(
    val id: String,
    val systemId: String,
    val fileName: String,
    val path: String,
    val title: String,
    val size: Long,
    val placeholder: Boolean,
    val arriving: Boolean,
    val hack: Boolean
)

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

    @Query("SELECT id, path, folder, mtime, size, addedAt, arriving FROM games WHERE systemId = :systemId")
    suspend fun signatures(systemId: String): List<Signature>

    @Query("SELECT systemId, COUNT(*) AS n FROM games WHERE placeholder = 0 AND variantOf IS NULL GROUP BY systemId")
    fun countsBySystem(): Flow<List<SystemCount>>

    @Query("SELECT * FROM games WHERE systemId = :systemId AND variantOf IS NULL ORDER BY sortTitle")
    fun bySystem(systemId: String): Flow<List<GameEntity>>

    /** Shown games of [systemIds], narrow rows for magazine reference matching. */
    @Query("SELECT id, systemId, fileName, path, title, size, placeholder, arriving, hack FROM games WHERE systemId IN (:systemIds) AND variantOf IS NULL")
    suspend fun matchRows(systemIds: List<String>): List<MatchRow>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun byId(id: String): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: String): Flow<GameEntity?>

    @Query("SELECT * FROM games WHERE id IN (:ids)")
    suspend fun byIdsChunk(ids: List<String>): List<GameEntity>

    /** Search results ([LibrarySearch.page]); also re-run when play sessions change, for the "unplayed" filter. */
    @RawQuery(observedEntities = [GameEntity::class, SessionEntity::class])
    fun searchPage(query: SupportSQLiteQuery): Flow<List<GameEntity>>

    /** Number of all search matches ([LibrarySearch.count]), independent of the page limit. */
    @RawQuery(observedEntities = [GameEntity::class, SessionEntity::class])
    fun searchCount(query: SupportSQLiteQuery): Flow<Int>

    /** Search's System picker: shown games per system ([withPlaceholders] = also games not copied to the device). */
    @Query("SELECT systemId AS value, COUNT(*) AS n FROM games WHERE variantOf IS NULL AND (:withPlaceholders OR placeholder = 0) GROUP BY systemId")
    fun systemFacets(withPlaceholders: Boolean): Flow<List<FacetCount>>

    @Query(
        "SELECT genre AS value, COUNT(*) AS n FROM games WHERE variantOf IS NULL AND genre IS NOT NULL AND TRIM(genre) != '' " +
            "AND (:withPlaceholders OR placeholder = 0) GROUP BY genre ORDER BY genre COLLATE NOCASE"
    )
    fun genreFacets(withPlaceholders: Boolean): Flow<List<FacetCount>>

    /** Distinct tag/flag lists for Search's Tags picker; [LibrarySearch.labelFacets] splits them into labels. */
    @Query(
        "SELECT tags, flags, COUNT(*) AS n FROM games WHERE variantOf IS NULL AND (tags != '' OR flags != '') " +
            "AND (:withPlaceholders OR placeholder = 0) GROUP BY tags, flags"
    )
    fun labelCombos(withPlaceholders: Boolean): Flow<List<LabelCombo>>

    @Query("SELECT * FROM games WHERE placeholder = 0 AND variantOf IS NULL")
    suspend fun allPlayable(): List<GameEntity>

    /** Every indexed id, placeholders and variants included: an import resolves its references against the whole index. */
    @Query("SELECT id FROM games")
    suspend fun allIds(): List<String>

    /** Narrow rows for "does Sortie already have art for this game" without loading descriptions. */
    @Query("SELECT systemId, fileName, cover, screenshot, marquee FROM games")
    suspend fun mediaRows(): List<MediaRow>

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

    /** Every session, oldest first (backup export and restore merge). */
    @Query("SELECT * FROM sessions ORDER BY startedAt, rowId")
    suspend fun allSessions(): List<SessionEntity>

    /**
     * Sessions that may belong to the journal week `[from, to)`: started in it, or started earlier and ended (or still open)
     * after [from]. The journal decides which of them count ([io.github.esko997.sortie.data.journal.weekPlay]).
     */
    @Query("SELECT * FROM sessions WHERE startedAt < :to AND (startedAt >= :from OR endedAt IS NULL OR endedAt > :from) ORDER BY startedAt, rowId")
    fun sessionsTouching(from: Long, to: Long): Flow<List<SessionEntity>>

    /** Shown games whose title matches [pattern] (a LIKE pattern built with [likeEscape]), for the journal's game link. */
    @Query("SELECT * FROM games WHERE variantOf IS NULL AND title LIKE :pattern ESCAPE '\\' ORDER BY sortTitle LIMIT :limit")
    suspend fun titleMatches(pattern: String, limit: Int): List<GameEntity>

    @Insert
    suspend fun insertSessions(s: List<SessionEntity>): List<Long>

    @Query("DELETE FROM sessions WHERE rowId IN (:rowIds)")
    suspend fun deleteSessionsChunk(rowIds: List<Long>): Int

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

suspend fun GameDao.deleteSessions(rowIds: List<Long>): Int = rowIds.chunked(SQLITE_CHUNK).sumOf { deleteSessionsChunk(it) }

/** Escapes LIKE wildcards in user input (the queries use ESCAPE '\'). */
fun likeEscape(q: String): String = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun SqlQuery.toRoom(): SupportSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())
