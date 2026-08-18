package com.punchcard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LogEntry::class, PaySettings::class], version = 3, exportSchema = false)
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

        // v2 -> v3: added PaySettings.savingsPct — a set-aside-from-net
        // savings target, purely informational (never changes what "net
        // income" means, see PayCalculator's Savings section). Existing
        // rows default to 0 (no savings target set), so nothing changes
        // for anyone until they set one in Settings.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pay_settings ADD COLUMN savingsPct REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hours_log.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }
    }
}
