package com.punchcard.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HoursRepository tests against in-memory fake DAOs — no Room/SQLite/
 * Android runtime needed, so these run as fast plain-JVM unit tests.
 * Exercises the same logic the real app relies on: Start/End time
 * logging, Manage-screen edits, deletes, and merging restored backup
 * rows without clobbering anything already logged locally.
 */
class HoursRepositoryTest {

    private class FakeLogEntryDao : LogEntryDao {
        val table = LinkedHashMap<String, LogEntry>()

        override suspend fun upsert(entry: LogEntry) { table[entry.date] = entry }
        override fun observeByDate(date: String): Flow<LogEntry?> = flowOf(table[date])
        override suspend fun getByDate(date: String): LogEntry? = table[date]
        override fun observeForMonth(monthPrefix: String): Flow<List<LogEntry>> =
            flowOf(table.values.filter { it.date.startsWith(monthPrefix) }.sortedBy { it.date })
        override fun observeRecent(limit: Int): Flow<List<LogEntry>> =
            flowOf(table.values.sortedByDescending { it.date }.take(limit))
        override suspend fun getPendingBackup(): List<LogEntry> =
            table.values.filter { it.startTime != null && it.endTime != null && !it.backedUp }.sortedBy { it.date }
        override fun observePendingBackupCount(): Flow<Int> =
            flowOf(table.values.count { it.startTime != null && it.endTime != null && !it.backedUp })
        override suspend fun markBackedUp(date: String) {
            table[date]?.let { table[date] = it.copy(backedUp = true) }
        }
        override suspend fun getCompleteForMonth(monthPrefix: String): List<LogEntry> =
            table.values.filter { it.date.startsWith(monthPrefix) && it.hours != null }
        override suspend fun delete(date: String) { table.remove(date) }
    }

    private class FakePaySettingsDao : PaySettingsDao {
        val rows = mutableListOf<PaySettings>()

        override suspend fun insert(settings: PaySettings) {
            rows.removeAll { it.effectiveDate == settings.effectiveDate }
            rows.add(settings)
        }
        override fun observeLatest(): Flow<PaySettings?> = flowOf(rows.maxByOrNull { it.effectiveDate })
        override suspend fun getLatest(): PaySettings? = rows.maxByOrNull { it.effectiveDate }
        override suspend fun getForDateOrBefore(date: String): PaySettings? =
            rows.filter { it.effectiveDate <= date }.maxByOrNull { it.effectiveDate }
        override suspend fun getEarliest(): PaySettings? = rows.minByOrNull { it.effectiveDate }
    }

    private lateinit var logDao: FakeLogEntryDao
    private lateinit var payDao: FakePaySettingsDao
    private lateinit var repo: HoursRepository

    @Before
    fun setUp() {
        logDao = FakeLogEntryDao()
        payDao = FakePaySettingsDao()
        repo = HoursRepository(logDao, payDao)
        payDao.rows.add(PaySettings(effectiveDate = "2026-01-01", hourlyRate = 60.0, creditPoints = 2.25, pensionPct = 6.0))
    }

    @Test
    fun `logTime start then end computes hours and money`() = runTest {
        val (afterStart, completeAfterStart) = repo.logTime("2026-08-17", isStart = true, time = "09:00")
        assertEquals("09:00", afterStart.startTime)
        assertNull(afterStart.hours)
        assertTrue(!completeAfterStart)

        // 09:00-17:00 = 8 raw hours minus the three fixed breaks (1h) = 7h.
        val (afterEnd, completeAfterEnd) = repo.logTime("2026-08-17", isStart = false, time = "17:00")
        assertEquals(7.0, afterEnd.hours!!, 0.001)
        assertEquals(420.0, afterEnd.money!!, 0.001) // 7h * 60
        assertTrue(completeAfterEnd)
    }

    @Test
    fun `logNext picks Start then End automatically`() = runTest {
        assertTrue(!repo.hasStartedToday("2026-08-17"))
        repo.logNext("2026-08-17", "09:00")
        assertTrue(repo.hasStartedToday("2026-08-17"))
        val (entry, complete) = repo.logNext("2026-08-17", "17:00")
        assertEquals("09:00", entry.startTime)
        assertEquals("17:00", entry.endTime)
        assertTrue(complete)
    }

    @Test
    fun `logNext tapping again after complete overwrites the end time`() = runTest {
        repo.logNext("2026-08-17", "09:00")
        repo.logNext("2026-08-17", "17:00")
        val (entry, _) = repo.logNext("2026-08-17", "18:00")
        assertEquals("18:00", entry.endTime)
    }

    @Test
    fun `setEntryTimes clears hours and money when a time is removed`() = runTest {
        repo.setEntryTimes("2026-08-17", "09:00", "17:00")
        assertTrue(repo.getEntry("2026-08-17")!!.hours != null)

        repo.setEntryTimes("2026-08-17", "09:00", null)
        val entry = repo.getEntry("2026-08-17")!!
        assertNull(entry.hours)
        assertNull(entry.money)
    }

    @Test
    fun `setEntryTimes always clears backedUp so an edit gets re-backed-up`() = runTest {
        repo.setEntryTimes("2026-08-17", "09:00", "17:00")
        repo.markBackedUp("2026-08-17")
        assertTrue(repo.getEntry("2026-08-17")!!.backedUp)

        repo.setEntryTimes("2026-08-17", "09:00", "18:00")
        assertTrue(!repo.getEntry("2026-08-17")!!.backedUp)
    }

    @Test
    fun `deleteEntry removes the row entirely`() = runTest {
        repo.setEntryTimes("2026-08-17", "09:00", "17:00")
        repo.deleteEntry("2026-08-17")
        assertNull(repo.getEntry("2026-08-17"))
    }

    @Test
    fun `getEntry returns null for a date with nothing logged`() = runTest {
        assertNull(repo.getEntry("2099-01-01"))
    }

    @Test
    fun `importFromBackup fills in missing dates only, never overwrites local data`() = runTest {
        // Already logged locally — must NOT be clobbered by a conflicting
        // backup row for the same date (this is what makes restoring
        // safe to run any time, including after fresh local activity).
        repo.setEntryTimes("2026-08-17", "09:00", "17:00")

        val fromBackup = listOf(
            LogEntry(date = "2026-08-17", startTime = "08:00", endTime = "16:00", hours = 8.0, backedUp = true),
            LogEntry(date = "2026-08-16", startTime = "09:00", endTime = "17:00", hours = 8.0, backedUp = true),
        )
        val imported = repo.importFromBackup(fromBackup)

        assertEquals(1, imported) // only the 16th — the 17th already existed locally
        assertEquals("09:00", repo.getEntry("2026-08-17")!!.startTime) // untouched
        assertEquals("09:00", repo.getEntry("2026-08-16")!!.startTime) // restored from backup
    }

    @Test
    fun `importFromBackup into a completely empty database imports everything`() = runTest {
        val fromBackup = listOf(
            LogEntry(date = "2026-08-01", startTime = "09:00", endTime = "17:00", hours = 7.0, backedUp = true),
            LogEntry(date = "2026-08-02", startTime = "09:00", endTime = "17:00", hours = 7.0, backedUp = true),
        )
        val imported = repo.importFromBackup(fromBackup)
        assertEquals(2, imported)
    }

    @Test
    fun `getMonthSummary uses the settings in effect on each entry's own date`() = runTest {
        // A mid-month rate change must apply only to entries on/after it.
        payDao.rows.add(PaySettings(effectiveDate = "2026-08-15", hourlyRate = 80.0, creditPoints = 2.25, pensionPct = 6.0))
        repo.setEntryTimes("2026-08-03", "09:00", "17:00") // before the raise: 7h * 60 = 420
        repo.setEntryTimes("2026-08-20", "09:00", "17:00") // after the raise: 7h * 80 = 560

        val summary = repo.getMonthSummary("2026-08")

        assertTrue(summary.hasData)
        assertEquals(980.0, summary.gross, 0.001)
    }

    @Test
    fun `getMonthSummary with no logged days has no data`() = runTest {
        val summary = repo.getMonthSummary("2099-01")
        assertTrue(!summary.hasData)
    }
}
