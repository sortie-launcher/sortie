package io.github.esko997.sortie.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One launchable file. `id` is `<systemId>/<filename>`, the same key the desktop uses in guide.json and the play log. */
@Entity(tableName = "games", indices = [Index("systemId", "sortTitle"), Index("fit"), Index("variantOf")])
data class GameEntity(
    @PrimaryKey val id: String,
    val systemId: String,
    val folder: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val mtime: Long,
    val title: String,
    val sortTitle: String,
    val desc: String?,
    val rating: Float?,
    val year: Int?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val players: String?,
    val playersMax: Int,
    val fit: Int,
    val hook: String?,
    // flags and tags are comma-joined, lower-case
    val flags: String,
    val tags: String,
    val handWritten: Boolean,
    val cover: String?,
    val screenshot: String?,
    val marquee: String?,
    // placeholder: 0-byte shed file, not playable here; arriving: Syncthing temp file still being written
    val placeholder: Boolean,
    val arriving: Boolean,
    val hack: Boolean,
    val addedAt: Long,
    // id of the file shown instead of this one (disc 1 of a set, or the preferred container format); null = shown
    val variantOf: String? = null,
    /** Hand-written control notes from guide.json (see GuideEntry.controls). */
    val controls: String? = null
)

/** Per-system bookkeeping so the indexer knows when metadata (gamelist, guide) changed since the last pass. */
@Entity(tableName = "index_meta")
data class IndexMetaEntity(
    @PrimaryKey val systemId: String,
    val gamelistMtime: Long,
    /** guide.json's stamp plus the hack classification rules (see Indexer.metaStamp); either changing rebuilds the system's rows. */
    val guideStamp: String,
    val mediaStamp: Long
)

@Entity(tableName = "sessions", indices = [Index("gameId"), Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val gameId: String,
    val profileId: String?,
    val startedAt: Long,
    val endedAt: Long?,
    // abandoned: Sortie never saw the session end (process killed, device slept); its duration is not counted
    val abandoned: Boolean = false
)
