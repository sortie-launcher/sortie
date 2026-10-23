package io.github.esko997.sortie.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GameEntity::class, SessionEntity::class], version = 1, exportSchema = true)
abstract class SortieDb : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun open(context: Context): SortieDb = Room.databaseBuilder(context, SortieDb::class.java, "sortie.db")
            .fallbackToDestructiveMigration() // the index is a cache of files on the card; rebuilding is cheap
            .build()
    }
}
