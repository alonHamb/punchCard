package com.punchcard.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [LogEntry::class, PaySettings::class], version = 5, exportSchema = false)
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

        // v3 -> v4: added PaySettings.transportationCosts — a per-day
        // transportation reimbursement added to gross pay for every day
        // worked (see PayCalculator.computeMonthSummary). Existing rows
        // default to 0 (no reimbursement), matching the entity's default.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pay_settings ADD COLUMN transportationCosts REAL NOT NULL DEFAULT 0.0")
            }
        }

        // v4 -> v5: added PaySettings.dailySpending — a per-day spending
        // constant subtracted from gross pay for every day worked (see
        // PayCalculator.computeMonthSummary). Existing rows default to 0
        // (no deduction), matching the entity's default.
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pay_settings ADD COLUMN dailySpending REAL NOT NULL DEFAULT 0.0")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hours_log.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
        }
    }
}
