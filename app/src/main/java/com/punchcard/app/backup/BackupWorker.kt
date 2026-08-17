package com.punchcard.app.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.punchcard.app.data.AppDatabase
import com.punchcard.app.data.HoursRepository
import com.punchcard.app.logic.PayCalculator
import java.time.LocalTime

/**
 * Runs periodically in the background (scheduled by [BackupScheduler]).
 * Unless [KEY_FORCE] is set (the manual "Back up now" button), it's a
 * no-op outside 18:00–06:00 or when not on wifi — it just returns
 * success and waits for the next periodic tick. When conditions are met
 * and there are completed days waiting, it writes them into this
 * month's backup CSV (and the monthly summary CSV, only once the
 * month's last day has been logged) via the SAF folder chosen in
 * Settings, then marks those days as backed up.
 */
class BackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        if (!force) {
            if (!isNightWindow()) return Result.success()
            if (!isOnWifi()) return Result.success()
        }

        val prefs = BackupPreferences(applicationContext)
        val folderUri = prefs.getFolderUri() ?: return Result.success() // no backup folder chosen yet

        val db = AppDatabase.getInstance(applicationContext)
        val repo = HoursRepository(db.logEntryDao(), db.paySettingsDao())

        val pending = repo.getPendingBackupEntries()
        if (pending.isEmpty()) return Result.success()

        val byMonth = pending.groupBy { it.date.substring(0, 7) }
        var allOk = true

        for ((monthStr, entries) in byMonth) {
            val wrote = CsvBackup.writeDailyRows(applicationContext, folderUri, monthStr, entries)
            if (!wrote) {
                allOk = false
                continue
            }
            for (e in entries) repo.markBackedUp(e.date)

            if (entries.any { PayCalculator.isLastDayOfMonth(it.date) }) {
                val summary = repo.getMonthSummary(monthStr)
                if (summary.hasData && summary.hasSettings) {
                    CsvBackup.writeMonthlySummary(applicationContext, folderUri, summary)
                }
            }
        }

        return if (allOk) Result.success() else Result.retry()
    }

    private fun isNightWindow(): Boolean = isNightWindowForHour(LocalTime.now().hour)

    private fun isOnWifi(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    companion object {
        const val KEY_FORCE = "force"

        /**
         * True for the 18:00–06:00 backup window, given an explicit
         * device-local hour (0–23). Pulled out as a pure function of
         * [hour] rather than reading the clock directly so it's covered
         * by a plain-JVM unit test (BackupWorkerLogicTest) without
         * needing to fake the system clock.
         */
        internal fun isNightWindowForHour(hour: Int): Boolean = hour >= 18 || hour < 6
    }
}
