package com.punchcard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One calendar day's worth of logged hours. Lives entirely in the local
 * on-device database — nothing here ever leaves the phone except via the
 * nightly backup (see backup/BackupWorker.kt), which only reads rows
 * that already have both [startTime] and [endTime] set.
 */
@Entity(tableName = "log_entries")
data class LogEntry(
    @PrimaryKey val date: String,      // "YYYY-MM-DD"
    val startTime: String? = null,     // "HH:MM", 24h, device-local time
    val endTime: String? = null,       // "HH:MM", 24h, device-local time
    val hours: Double? = null,         // End − Start, minus fixed breaks
    val money: Double? = null,         // hours * that day's effective hourly rate
    val backedUp: Boolean = false,     // true once written into a Drive backup CSV
    val lastUpdated: Long = System.currentTimeMillis()
)
