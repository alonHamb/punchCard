package com.punchcard.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.punchcard.app.backup.BackupScheduler

/** Runs when the widget's "Back up now" is tapped. Same as the Home
 *  screen's button: bypasses the 18:00–06:00/wifi gate. The actual
 *  backup runs asynchronously via WorkManager, so this doesn't
 *  re-render the widget itself — the pending-day count only changes
 *  once the backup finishes and the app or another widget refresh
 *  picks it up, same as the in-app button doesn't refresh anything
 *  synchronously either. */
class BackupNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        BackupScheduler.triggerNow(context)
    }
}
