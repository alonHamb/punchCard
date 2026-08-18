package com.punchcard.app.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.punchcard.app.data.AppDatabase
import com.punchcard.app.data.HoursRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Runs when the widget's Start/End box is tapped. Mirrors
 * MainViewModel.logNow(): logs "now" for today, letting
 * HoursRepository.logNext decide fresh whether that's a Start or an
 * End (never trusts stale widget state — same race-safety reasoning as
 * the in-app button). Re-renders the widget immediately afterward so
 * the button flips to its new mode and the month figure updates
 * without waiting for the periodic refresh.
 */
class LogNowAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val db = AppDatabase.getInstance(context)
        val repo = HoursRepository(db.logEntryDao(), db.paySettingsDao())
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val time = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
        repo.logNext(today, time)
        PunchCardWidget().update(context, glanceId)
    }
}
