package io.github.esko997.sortie.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One launchable file. `id` is `<folder>/<filename>`, the same key the desktop uses in guide.json and the play log. */
@Entity(tableName = "games", indices = [Index("systemId"), Index("title"), Index("fit")])
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
    val flags: String, // comma-joined, lower-case
    val tags: String, // comma-joined, lower-case
    val handWritten: Boolean,
    val cover: String?,
    val screenshot: String?,
    val marquee: String?,
    val placeholder: Boolean, // 0-byte shed file, not playable here
    val arriving: Boolean, // Syncthing temp file still being written
    val hack: Boolean,
    val addedAt: Long,
    val scanGen: Long
)

@Entity(tableName = "sessions", indices = [Index("gameId"), Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val gameId: String,
    val profileId: String?,
    val startedAt: Long,
    val endedAt: Long?
)
