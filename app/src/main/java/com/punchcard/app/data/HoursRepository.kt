package com.punchcard.app.data

import com.punchcard.app.logic.PayCalculator
import kotlinx.coroutines.flow.Flow

/**
 * Single entry point the UI/ViewModel and the background backup job both
 * go through. Everything here is local — Room reads/writes only. No
 * network call happens anywhere in this class.
 */
class HoursRepository(
    private val logDao: LogEntryDao,
    private val payDao: PaySettingsDao,
) {
    fun observeByDate(date: String): Flow<LogEntry?> = logDao.observeByDate(date)

    fun observeRecent(limit: Int = 10): Flow<List<LogEntry>> = logDao.observeRecent(limit)

    /** Every entry in [monthPrefix] ("YYYY-MM"), complete or not — used by
     *  the Manage screen so partially-logged days (e.g. Start with no End
     *  yet) can still be seen and fixed. */
    fun observeForMonth(monthPrefix: String): Flow<List<LogEntry>> = logDao.observeForMonth(monthPrefix)

    fun observePendingBackupCount(): Flow<Int> = logDao.observePendingBackupCount()

    fun observeLatestPaySettings(): Flow<PaySettings?> = payDao.observeLatest()

    suspend fun getLatestPaySettings(): PaySettings? = payDao.getLatest()

    suspend fun savePaySettings(
        hourlyRate: Double,
        creditPoints: Double,
        pensionPct: Double,
        overtimeEnabled: Boolean,
        savingsPct: Double,
        effectiveDate: String,
    ) {
        payDao.insert(
            PaySettings(effectiveDate, hourlyRate, creditPoints, pensionPct, overtimeEnabled, savingsPct),
        )
    }

    suspend fun hasStartedToday(date: String): Boolean = logDao.getByDate(date)?.startTime != null

    /** Looks up whatever's already logged for [date], if anything — used
     *  by the Manage screen's "Add day" flow to warn before silently
     *  overwriting an existing day. */
    suspend fun getEntry(date: String): LogEntry? = logDao.getByDate(date)

    /** Logs a Start or End tap for [date]/[time], recomputing Hours + Money
     *  once both are present. Returns the resulting row and whether it's
     *  now "complete" (both times logged, eligible for backup). */
    suspend fun logTime(date: String, isStart: Boolean, time: String): Pair<LogEntry, Boolean> {
        val existing = logDao.getByDate(date)
        var entry = (existing ?: LogEntry(date = date)).let {
            if (isStart) it.copy(startTime = time) else it.copy(endTime = time)
        }

        val start = entry.startTime
        val end = entry.endTime
        if (start != null && end != null) {
            val hours = PayCalculator.computeHours(start, end)
            val settings = PayCalculator.settingsForDate(date, payDao::getForDateOrBefore, payDao::getEarliest)
            val money = settings?.let { PayCalculator.computeMoney(hours, it.hourlyRate, it.overtimeEnabled) }
            entry = entry.copy(hours = hours, money = money, backedUp = false, lastUpdated = System.currentTimeMillis())
        } else {
            entry = entry.copy(lastUpdated = System.currentTimeMillis())
        }

        logDao.upsert(entry)
        val complete = entry.startTime != null && entry.endTime != null
        return entry to complete
    }

    /**
     * Logs "now" for [date] without the caller having to decide Start vs
     * End: this is a Start tap if [date] doesn't have a start time yet,
     * otherwise it's an End tap (which also covers "correct a mis-tap" —
     * tapping again once both are set just overwrites the end time).
     * This is what actually enforces "End only after Start" — a fresh
     * read happens on every call, so it's never fooled by stale UI state.
     */
    suspend fun logNext(date: String, time: String): Pair<LogEntry, Boolean> {
        val isStart = !hasStartedToday(date)
        return logTime(date, isStart, time)
    }

    suspend fun getMonthSummary(monthStr: String): PayCalculator.MonthSummary {
        val entries = logDao.getCompleteForMonth(monthStr)
        return PayCalculator.computeMonthSummary(monthStr, entries, payDao::getForDateOrBefore, payDao::getEarliest)
    }

    /** Same as [getMonthSummary], but with every not-yet-logged day from
     *  [today] onward filled in with the month's average logged hours-per-
     *  day so far (or 8.0 if nothing's logged yet) — a "projected" total
     *  for the month rather than just what's actually been recorded. */
    suspend fun getProjectedMonthSummary(monthStr: String, today: String): PayCalculator.MonthSummary {
        val entries = logDao.getCompleteForMonth(monthStr)
        return PayCalculator.computeProjectedMonthSummary(monthStr, entries, today, payDao::getForDateOrBefore, payDao::getEarliest)
    }

    suspend fun getPendingBackupEntries(): List<LogEntry> = logDao.getPendingBackup()

    suspend fun markBackedUp(date: String) = logDao.markBackedUp(date)

    /**
     * Manage-screen edit: overwrites [date]'s start/end time directly (used
     * for both correcting a mistake on an existing day and manually adding
     * a day that was never logged). Recomputes hours/money if both times
     * end up present, clears them if either is missing, and always resets
     * backedUp to false — an edited day must go out in the next backup
     * even if that date was already backed up before.
     */
    suspend fun setEntryTimes(date: String, startTime: String?, endTime: String?) {
        var entry = LogEntry(date = date, startTime = startTime, endTime = endTime)
        if (startTime != null && endTime != null) {
            val hours = PayCalculator.computeHours(startTime, endTime)
            val settings = PayCalculator.settingsForDate(date, payDao::getForDateOrBefore, payDao::getEarliest)
            val money = settings?.let { PayCalculator.computeMoney(hours, it.hourlyRate, it.overtimeEnabled) }
            entry = entry.copy(hours = hours, money = money, backedUp = false, lastUpdated = System.currentTimeMillis())
        } else {
            entry = entry.copy(hours = null, money = null, backedUp = false, lastUpdated = System.currentTimeMillis())
        }
        logDao.upsert(entry)
    }

    suspend fun deleteEntry(date: String) = logDao.delete(date)

    /**
     * Merges parsed backup rows (see CsvBackup.readAllDailyRows) into the
     * local database without clobbering anything already here — this is
     * how data survives an app uninstall/reinstall, since the on-device
     * database itself is wiped along with the app and only the backup
     * files in the user's chosen folder (outside app storage) survive.
     * Only fills in dates that don't already have a local row; a day
     * that's already logged locally is left untouched. Returns how many
     * rows were actually imported.
     */
    suspend fun importFromBackup(entries: List<LogEntry>): Int {
        var imported = 0
        for (e in entries) {
            if (logDao.getByDate(e.date) == null) {
                logDao.upsert(e)
                imported++
            }
        }
        return imported
    }

    /**
     * Merges rows parsed from a user-picked .xlsx spreadsheet (see
     * XlsxImport.readEntries) into the local database — same "only fills
     * in dates that don't already have a local row" rule as
     * [importFromBackup]. Unlike a backup restore, these rows have never
     * been backed up and don't carry a precomputed hours/money figure
     * (spreadsheets don't know this app's pay settings), so each one
     * goes through [setEntryTimes] instead of a raw upsert — that
     * recomputes hours/money against whatever pay settings are in
     * effect on that date and queues the day for the next backup, same
     * as adding it by hand on the Manage screen would. Returns how many
     * rows were actually imported.
     */
    suspend fun importFromSpreadsheet(entries: List<LogEntry>): Int {
        var imported = 0
        for (e in entries) {
            if (logDao.getByDate(e.date) == null) {
                setEntryTimes(e.date, e.startTime, e.endTime)
                imported++
            }
        }
        return imported
    }
}
