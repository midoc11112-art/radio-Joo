package com.example.radiojooo.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.radiojooo.data.model.StationEntity

@Database(entities = [StationEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stationDao(): StationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                try { db.execSQL("ALTER TABLE stations ADD COLUMN lastUrlWorks INTEGER") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE stations ADD COLUMN failCount INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
                try { db.execSQL("ALTER TABLE stations ADD COLUMN lastCheckedAt INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "radio_joo_db"
                )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}