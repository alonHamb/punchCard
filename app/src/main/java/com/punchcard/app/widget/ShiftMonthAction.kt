package com.punchcard.app.widget

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Persisted per-widget-instance state: which "YYYY-MM" the month
 *  breakdown is currently showing. Absent (never navigated) means the
 *  current month. `GlanceAppWidget.stateDefinition` defaults to
 *  `PreferencesGlanceStateDefinition`, so no extra setup is needed to
 *  use this. */
internal val VIEWED_MONTH_KEY = stringPreferencesKey("viewed_month")

private val MONTH_DELTA_KEY = ActionParameters.Key<Int>("month_delta")

internal fun shiftMonthParameters(delta: Int): ActionParameters =
    actionParametersOf(MONTH_DELTA_KEY to delta)

/**
 * Runs when the widget's month-nav ‹/› is tapped. Mirrors
 * MainViewModel.shiftMonth(): moves the persisted viewed-month by
 * [MONTH_DELTA_KEY] months, refusing to go past the current month
 * (same "no future months" guard), then re-renders.
 */
class ShiftMonthAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[MONTH_DELTA_KEY] ?: return
        val currentMonth = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE).substring(0, 7)

        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
        val viewedMonth = state[VIEWED_MONTH_KEY] ?: currentMonth
        val (y, m) = viewedMonth.split("-").map { it.toInt() }
        val next = LocalDate.of(y, m, 1).plusMonths(delta.toLong())
        val nextMonth = "%04d-%02d".format(next.year, next.monthValue)
        if (nextMonth > currentMonth) return // no future months

        updateAppWidgetState(context, glanceId) { it[VIEWED_MONTH_KEY] = nextMonth }
        PunchCardWidget().update(context, glanceId)
    }
}
