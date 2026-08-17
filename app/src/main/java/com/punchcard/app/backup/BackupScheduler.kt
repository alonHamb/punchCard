package com.punchcard.app.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules the periodic background check (real OS-level scheduling via
 * WorkManager/JobScheduler — this runs even if the app isn't open, unlike
 * a web app). 15 minutes is WorkManager's minimum periodic interval;
 * BackupWorker itself decides whether it's actually 18:00–06:00 + wifi
 * before doing anything, so most ticks outside that window are instant
 * no-ops that cost negligible battery.
 */
object BackupScheduler {
    private const val WORK_NAME = "hours_log_backup_check"

    fun schedule(context: Context) {
        // Coarse pre-filter so the OS doesn't even wake us on metered/no
        // connection; BackupWorker.isOnWifi() double-checks it's actually
        // wifi (UNMETERED can technically include some unmetered cellular
        // plans) before writing anything.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        val request = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Manual "Back up now" — bypasses both the time window and the wifi
     *  check (BackupWorker sees KEY_FORCE and skips those gates). */
    fun triggerNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(workDataOf(BackupWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
