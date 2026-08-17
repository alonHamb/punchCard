package com.punchcard.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogEntryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: LogEntry)

    @Query("SELECT * FROM log_entries WHERE date = :date LIMIT 1")
    fun observeByDate(date: String): Flow<LogEntry?>

    @Query("SELECT * FROM log_entries WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): LogEntry?

    @Query("SELECT * FROM log_entries WHERE date LIKE :monthPrefix || '%' ORDER BY date ASC")
    fun observeForMonth(monthPrefix: String): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries ORDER BY date DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<LogEntry>>

    @Query("SELECT * FROM log_entries WHERE startTime IS NOT NULL AND endTime IS NOT NULL AND backedUp = 0 ORDER BY date ASC")
    suspend fun getPendingBackup(): List<LogEntry>

    @Query("SELECT COUNT(*) FROM log_entries WHERE startTime IS NOT NULL AND endTime IS NOT NULL AND backedUp = 0")
    fun observePendingBackupCount(): Flow<Int>

    @Query("UPDATE log_entries SET backedUp = 1 WHERE date = :date")
    suspend fun markBackedUp(date: String)

    @Query("SELECT * FROM log_entries WHERE date LIKE :monthPrefix || '%' AND hours IS NOT NULL")
    suspend fun getCompleteForMonth(monthPrefix: String): List<LogEntry>

    @Query("DELETE FROM log_entries WHERE date = :date")
    suspend fun delete(date: String)
}
