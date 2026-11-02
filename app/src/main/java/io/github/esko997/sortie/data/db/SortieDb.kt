package io.github.esko997.sortie.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [GameEntity::class, IndexMetaEntity::class, SessionEntity::class], version = 5, exportSchema = true)
abstract class SortieDb : RoomDatabase() {
    abstract fun games(): GameDao

    companion object {
        fun open(context: Context): SortieDb = Room.databaseBuilder(context, SortieDb::class.java, "sortie.db")
            // Version 1 never left the developer's device. From here on, sessions are history: write real migrations.
            .fallbackToDestructiveMigrationFrom(1)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

        // active play time: earlier rows keep null and their wall-clock duration (SessionEntity.playedMs)
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN activeMs INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN activeSince INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = db.execSQL("ALTER TABLE games ADD COLUMN controls TEXT")
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN variantOf TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_games_variantOf ON games(variantOf)")
            }
        }
    }
}
