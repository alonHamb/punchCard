package com.punchcard.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.punchcard.app.data.AppDatabase
import com.punchcard.app.data.HoursRepository
import com.punchcard.app.data.LogEntry
import com.punchcard.app.backup.BackupPreferences
import com.punchcard.app.logic.PayCalculator
import com.punchcard.app.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.round

private val TextMuted = Color(0xFF94A3B8)
private val TextFaint = Color(0xFF64748B)
private val CardDark = Color(0xFF1E293B)
private val BrandStart = Color(0xFF16A34A)
private val BrandEnd = Color(0xFFEA580C)
private val BrandDanger = Color(0xFFF87171)

/**
 * Home-screen widget: the full Home screen minus Recent Days (the one
 * piece Glance/RemoteViews genuinely can't do well as a nested scroll
 * region within a resizable widget). Today's Start/End button and
 * numbers, the same month breakdown with prev/next navigation, and the
 * backup status/trigger — same data, same rules, as MainScreen.kt.
 *
 * `provideGlance` resolves everything up front (including the
 * persisted "which month is being viewed" state — see
 * [VIEWED_MONTH_KEY] in ShiftMonthAction.kt) and passes plain values
 * into [WidgetContent], since each `update()`/`updateAll()` call fully
 * re-runs `provideGlance` from scratch rather than incrementally
 * recomposing — there's no need for `currentState()`/reactive reads
 * inside the composable itself.
 */
class PunchCardWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.getInstance(context)
        val repo = HoursRepository(db.logEntryDao(), db.paySettingsDao())
        val backupPrefs = BackupPreferences(context)

        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val currentMonth = today.substring(0, 7)
        val todayEntry = repo.getEntry(today)

        val state = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val viewedMonthRaw = state[VIEWED_MONTH_KEY] ?: currentMonth
        val viewedMonth = if (viewedMonthRaw > currentMonth) currentMonth else viewedMonthRaw
        val summary = repo.getMonthSummary(viewedMonth)

        val pendingCount = repo.getPendingBackupEntries().size
        val folderName = backupPrefs.getFolderDisplayName()
        val headerDate = formatWidgetHeaderDate()

        provideContent {
            WidgetContent(
                headerDate = headerDate,
                todayEntry = todayEntry,
                viewedMonth = viewedMonth,
                summary = summary,
                pendingCount = pendingCount,
                folderName = folderName,
            )
        }
    }
}

private fun formatWidgetHeaderDate(): String {
    val now = LocalDate.now()
    val time = LocalTime.now()
    val dayName = now.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
    val monthName = now.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
    return "$dayName ${now.dayOfMonth} $monthName · %02d:%02d".format(time.hour, time.minute)
}

@Composable
private fun WidgetContent(
    headerDate: String,
    todayEntry: LogEntry?,
    viewedMonth: String,
    summary: PayCalculator.MonthSummary,
    pendingCount: Int,
    folderName: String?,
) {
    val isStartMode = todayEntry?.startTime == null

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .cornerRadius(20.dp)
            .padding(14.dp),
    ) {
        Text("PunchCard", style = TextStyle(color = ColorProvider(Color.White), fontSize = 16.sp, fontWeight = FontWeight.Bold))
        Text(headerDate, style = TextStyle(color = ColorProvider(TextMuted), fontSize = 11.sp))
        Spacer(GlanceModifier.height(10.dp))

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(48.dp)
                .background(if (isStartMode) BrandStart else BrandEnd)
                .cornerRadius(12.dp)
                .clickable(actionRunCallback<LogNowAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (isStartMode) "Start Shift" else "End Shift",
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(12.dp))

        Text("TODAY", style = TextStyle(color = ColorProvider(TextFaint), fontSize = 10.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(4.dp))
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            TodayMini("Start", todayEntry?.startTime ?: "—")
            TodayMini("End", todayEntry?.endTime ?: "—")
            TodayMini("Hours", todayEntry?.hours?.let { fmtNum(it) + "h" } ?: "—")
            TodayMini("Money", todayEntry?.money?.let { "₪" + fmtNum(it) } ?: "—")
        }
        Spacer(GlanceModifier.height(12.dp))

        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavBox("‹", shiftMonthParameters(-1))
            Text(
                monthTitle(viewedMonth),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            )
            NavBox("›", shiftMonthParameters(1))
        }
        Spacer(GlanceModifier.height(6.dp))

        when {
            !summary.hasData -> Text("No hours logged this month yet.", style = TextStyle(color = ColorProvider(TextMuted), fontSize = 12.sp))
            !summary.hasSettings -> Box(
                modifier = GlanceModifier.clickable(actionStartActivity<MainActivity>()),
            ) {
                Text(
                    "${fmtNum(summary.totalHours)}h logged — open PunchCard to set an hourly rate.",
                    style = TextStyle(color = ColorProvider(TextMuted), fontSize = 12.sp),
                )
            }
            else -> Column {
                val regularHours = summary.totalHours - summary.overtimeHours
                if (summary.overtimeHours > 0) {
                    WidgetRow("Regular (${fmtNum(regularHours)}h)", "₪" + fmtNum(summary.regularPay), Color.White)
                    WidgetRow("Overtime (${fmtNum(summary.overtimeHours)}h)", "₪" + fmtNum(summary.overtimePay), Color.White)
                    WidgetRow("Gross pay", "₪" + fmtNum(summary.gross), Color.White, emphasize = true)
                } else {
                    WidgetRow("Gross (${fmtNum(summary.totalHours)}h)", "₪" + fmtNum(summary.gross), Color.White)
                }
                WidgetRow("Income tax", "−₪" + fmtNum(summary.incomeTax), BrandDanger)
                WidgetRow("NI + health", "−₪" + fmtNum(summary.niHealth), BrandDanger)
                WidgetRow("Pension (${fmtNum(summary.pensionPct)}%)", "−₪" + fmtNum(summary.pension), BrandDanger)
                WidgetRow("Net income", "₪" + fmtNum(summary.net), BrandStart, emphasize = true)
                if (summary.savingsPct > 0) {
                    WidgetRow("Savings (${fmtNum(summary.savingsPct)}%)", "₪" + fmtNum(summary.savings), Color.White)
                    WidgetRow("Left to spend", "₪" + fmtNum(summary.leftToSpend), BrandStart, emphasize = true)
                }
            }
        }

        Spacer(GlanceModifier.defaultWeight())
        Spacer(GlanceModifier.height(10.dp))

        val backupEnabled = pendingCount > 0 && folderName != null
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pendingCount == 0) "No days waiting to back up" else "$pendingCount day${if (pendingCount != 1) "s" else ""} pending",
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(TextMuted), fontSize = 11.sp),
            )
            Box(
                modifier = run {
                    val base = GlanceModifier.background(CardDark).cornerRadius(8.dp).padding(horizontal = 10.dp, vertical = 6.dp)
                    if (backupEnabled) base.clickable(actionRunCallback<BackupNowAction>()) else base
                },
            ) {
                Text(
                    "Back up now",
                    style = TextStyle(
                        color = ColorProvider(if (backupEnabled) Color.White else TextFaint),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun RowScope.TodayMini(label: String, value: String) {
    Column(modifier = GlanceModifier.defaultWeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), style = TextStyle(color = ColorProvider(TextFaint), fontSize = 9.sp, fontWeight = FontWeight.Bold))
        Spacer(GlanceModifier.height(2.dp))
        Text(value, style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun NavBox(symbol: String, params: ActionParameters) {
    Box(
        modifier = GlanceModifier
            .size(26.dp)
            .background(CardDark)
            .cornerRadius(8.dp)
            .clickable(actionRunCallback<ShiftMonthAction>(params)),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun WidgetRow(label: String, value: String, valueColor: Color, emphasize: Boolean = false) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = ColorProvider(TextMuted), fontSize = if (emphasize) 13.sp else 12.sp),
        )
        Text(
            value,
            style = TextStyle(color = ColorProvider(valueColor), fontSize = if (emphasize) 13.sp else 12.sp, fontWeight = FontWeight.Bold),
        )
    }
}

private fun monthTitle(monthStr: String): String {
    val parts = monthStr.split("-").map { it.toInt() }
    val date = LocalDate.of(parts[0], parts[1], 1)
    val monthName = date.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault())
    return "$monthName ${date.year}"
}

private fun fmtNum(n: Double): String {
    val rounded = round(n).toInt()
    return if (rounded.toDouble() == n) rounded.toString() else "%.2f".format(n)
}
