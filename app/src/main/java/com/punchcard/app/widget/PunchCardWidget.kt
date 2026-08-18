package com.punchcard.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.Spacer
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.punchcard.app.data.AppDatabase
import com.punchcard.app.data.HoursRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.round

/**
 * Home-screen widget: today's Start/End button plus this month's net
 * income (gross minus income tax minus NI/health minus pension — the
 * same figure MainScreen's "Net income" row shows), so both are visible
 * without opening the app. Reads straight from the same local Room
 * database as the rest of the app via a fresh [HoursRepository] built
 * from [AppDatabase.getInstance] — the same "no DI framework, just
 * construct it from Context each time" pattern MainViewModel and
 * BackupWorker already use.
 *
 * Re-composes (via [update]/[updateAll]) whenever [LogNowAction] runs
 * (its own button tap) or whenever MainViewModel makes a change that
 * could affect what's shown here — see the `updateAll` calls sprinkled
 * through MainViewModel's mutating functions. `updatePeriodMillis` in
 * punchcard_widget_info.xml is only a 30-minute safety-net refresh (the
 * OS-enforced minimum) for the rare case of the calendar day rolling
 * over while nothing else prompts a redraw.
 */
class PunchCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getInstance(context)
        val repo = HoursRepository(db.logEntryDao(), db.paySettingsDao())
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val isStartMode = repo.getEntry(today)?.startTime == null
        val summary = repo.getMonthSummary(today.substring(0, 7))

        provideContent {
            WidgetContent(
                isStartMode = isStartMode,
                netText = when {
                    !summary.hasData -> "No days logged yet"
                    !summary.hasSettings -> "Set pay rate in app"
                    else -> "₪" + fmtNum(summary.net)
                },
            )
        }
    }
}

@Composable
private fun WidgetContent(isStartMode: Boolean, netText: String) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Text(
            text = "This month",
            style = TextStyle(color = ColorProvider(Color(0xFF94A3B8)), fontSize = 12.sp),
        )
        Text(
            text = netText,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(modifier = GlanceModifier.height(10.dp))
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(46.dp)
                .background(if (isStartMode) Color(0xFF16A34A) else Color(0xFFEA580C))
                .cornerRadius(12.dp)
                .clickable(actionRunCallback<LogNowAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isStartMode) "Start Day" else "End Day",
                style = TextStyle(
                    color = ColorProvider(Color.White),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

private fun fmtNum(n: Double): String {
    val rounded = round(n).toInt()
    return if (rounded.toDouble() == n) rounded.toString() else "%.2f".format(n)
}
