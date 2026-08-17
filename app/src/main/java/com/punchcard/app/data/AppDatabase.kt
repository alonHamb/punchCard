package com.punchcard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LogEntry::class, PaySettings::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logEntryDao(): LogEntryDao
    abstract fun paySettingsDao(): PaySettingsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        // v1 -> v2: added PaySettings.overtimeEnabled (125%/150% pay
        // after 8h/day). Existing rows default to enabled (1), matching
        // both the entity's Kotlin default and Israeli law's default for
        // salaried employees who aren't in an overtime-exempt role.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pay_settings ADD COLUMN overtimeEnabled INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hours_log.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
