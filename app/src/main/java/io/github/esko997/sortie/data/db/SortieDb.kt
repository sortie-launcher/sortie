package io.github.esko997.sortie.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GameEntity::class, IndexMetaEntity::class, SessionEntity::class], version = 3, exportSchema = true)
abstract class SortieDb : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun open(context: Context): SortieDb = Room.databaseBuilder(context, SortieDb::class.java, "sortie.db")
            // Version 1 never left the developer's device. From here on, sessions are history: write real migrations.
            .fallbackToDestructiveMigrationFrom(1)
            .build()
    }
}
