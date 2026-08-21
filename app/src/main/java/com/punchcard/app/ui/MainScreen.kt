package com.punchcard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punchcard.app.data.LogEntry
import com.punchcard.app.logic.PayCalculator
import com.punchcard.app.ui.theme.BrandAccent
import com.punchcard.app.ui.theme.BrandDanger
import com.punchcard.app.ui.theme.BrandEnd
import com.punchcard.app.ui.theme.BrandEndDark
import com.punchcard.app.ui.theme.BrandStart
import com.punchcard.app.ui.theme.BrandStartDark
import com.punchcard.app.ui.theme.BrandTextOnCard
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.round

@Composable
fun MainScreen(viewModel: MainViewModel, onOpenSettings: () -> Unit, onOpenManage: () -> Unit) {
    val today by viewModel.today.collectAsState()
    val recent by viewModel.recent.collectAsState()
    val pendingCount by viewModel.pendingBackupCount.collectAsState()
    val viewMonth by viewModel.viewMonth.collectAsState()
    val monthSummary by viewModel.monthSummary.collectAsState()
    val projectedMonthSummary by viewModel.projectedMonthSummary.collectAsState()
    val backupStatus by viewModel.backupStatus.collectAsState()
    val folderName by viewModel.folderName.collectAsState()

    var clockText by remember { mutableStateOf(nowClockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            clockText = nowClockText()
            viewModel.refreshDateIfChanged() // rolls "today" over to a new day if the app's been left open past midnight
            kotlinx.coroutines.delay(30_000)
        }
    }

    // End only ever shows once Start has actually been logged for today —
    // no clock cutoff. A new day (fresh, empty "today" row) always starts
    // back at Start.
    val isStartMode = today?.startTime == null

    // Projection only means anything for the month still in progress —
    // shiftMonth already forbids viewing months past the current one, so
    // this is really just "is the viewed month the current month".
    val isCurrentMonth = viewMonth == viewModel.currentMonthStr()
    var showProjected by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 24.dp, bottom = 40.dp),
    ) {
        item { HeaderRow(clockText, onOpenManage, onOpenSettings) }
        item { Spacer(Modifier.height(18.dp)) }
        item {
            BigLogButton(isStartMode = isStartMode, onClick = { viewModel.logNow() })
        }
        if (backupStatus != null) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text(
                    backupStatus ?: "",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
        item { TodayCard(today) }
        item { Spacer(Modifier.height(14.dp)) }
        item {
            BackupCard(
                pendingCount = pendingCount,
                folderName = folderName,
                onBackupNow = { viewModel.backupNow() },
                onOpenSettings = onOpenSettings,
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            MonthCard(
                monthStr = viewMonth,
                summary = monthSummary,
                onPrev = { viewModel.shiftMonth(-1) },
                onNext = { viewModel.shiftMonth(1) },
                onOpenSettings = onOpenSettings,
            )
        }
        if (isCurrentMonth && monthSummary?.hasSettings == true) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                ProjectedIncomeToggle(
                    expanded = showProjected,
                    onClick = { showProjected = !showProjected },
                )
            }
            if (showProjected) {
                item { Spacer(Modifier.height(12.dp)) }
                item { ProjectedMonthCard(monthStr = viewMonth, actual = monthSummary, projected = projectedMonthSummary) }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item { SectionLabel("RECENT DAYS") }
        if (recent.isEmpty()) {
            item { EmptyRow("No entries yet") }
        } else {
            items(recent, key = { it.date }) { entry -> RecentRow(entry) }
        }
    }
}

private fun nowClockText(): String {
    val now = LocalDate.now()
    val time = LocalTime.now()
    val dayName = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val monthName = now.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    // Day before month, matching the app's DD/MM/YYYY convention everywhere else.
    return "$dayName, ${now.dayOfMonth} $monthName · " + "%02d:%02d".format(time.hour, time.minute)
}

@Composable
private fun HeaderRow(clockText: String, onOpenManage: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("PunchCard", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(clockText, color = Color(0xFF94A3B8), fontSize = 13.sp)
        }
        Row {
            IconButton(
                onClick = onOpenManage,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
            ) {
                Icon(Icons.Filled.DateRange, contentDescription = "Manage entries", tint = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), CircleShape),
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
            }
        }
    }
}

@Composable
private fun BigLogButton(isStartMode: Boolean, onClick: () -> Unit) {
    val gradient = if (isStartMode) {
        Brush.verticalGradient(listOf(Color(0xFF22C55E), BrandStartDark))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFB923C), BrandEndDark))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(28.dp))
            .background(gradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (isStartMode) "Start Shift" else "End Shift",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isStartMode) "Tap to record your start time" else "Tap to record your end time",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun TodayCard(today: LogEntry?) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            TodayCol("Start", today?.startTime ?: "—")
            TodayCol("End", today?.endTime ?: "—")
            TodayCol("Hours", today?.hours?.let { fmtNum(it) + "h" } ?: "—")
            TodayCol("Money", today?.money?.let { "₪" + fmtNum(it) } ?: "—")
        }
    }
}

@Composable
private fun TodayCol(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(value, color = if (value == "—") Color(0xFFCBD5E1) else BrandTextOnCard, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BackupCard(pendingCount: Int, folderName: String?, onBackupNow: () -> Unit, onOpenSettings: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (pendingCount == 0) "No completed days waiting to back up."
                    else "$pendingCount day${if (pendingCount > 1) "s" else ""} waiting to back up",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onBackupNow,
                    enabled = pendingCount > 0 && folderName != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = BrandTextOnCard),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text("Back up now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (folderName == null) {
                Text(
                    "No backup folder chosen yet — open Settings to pick one.",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    "Backs up automatically 18:00–06:00 while on wifi, into \"$folderName\".",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun MonthCard(
    monthStr: String,
    summary: PayCalculator.MonthSummary?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NavCircleButton("‹", onPrev)
                Text(monthTitle(monthStr), color = BrandTextOnCard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                NavCircleButton("›", onNext)
            }
            Spacer(Modifier.height(12.dp))

            val s = summary
            when {
                s == null -> Text("Loading…", color = Color(0xFF94A3B8), fontSize = 13.sp)
                !s.hasData -> Text("No hours logged this month yet.", color = Color(0xFF94A3B8), fontSize = 13.sp)
                !s.hasSettings -> Column {
                    Text(
                        "${fmtNum(s.totalHours)}h logged, but no hourly rate is set yet.",
                        color = Color(0xFF94A3B8), fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = BrandAccent)) {
                        Text("Set pay settings", color = Color.White, fontSize = 13.sp)
                    }
                }
                else -> Column {
                    val regularHours = s.totalHours - s.overtimeHours
                    if (s.overtimeHours > 0) {
                        // With overtime present, "Regular pay" only covers the
                        // capped-at-8h/day portion, so it's noticeably less
                        // than the month's actual total hours worked — show
                        // the real total up top so it's never just implied
                        // by (and mistaken for) the regular-hours figure.
                        MonthRow("Hours logged", fmtNum(s.totalHours) + "h", BrandTextOnCard, emphasize = true)
                        MonthRow("Regular pay (${fmtNum(regularHours)}h, up to 8h/day)", "₪" + fmtNum(s.regularPay), BrandTextOnCard)
                        MonthRow("Overtime pay (${fmtNum(s.overtimeHours)}h @ 125%/150%)", "₪" + fmtNum(s.overtimePay), BrandTextOnCard)
                        MonthRow("Gross pay", "₪" + fmtNum(s.gross), BrandTextOnCard, emphasize = true)
                    } else {
                        MonthRow("Gross pay (${fmtNum(s.totalHours)}h logged)", "₪" + fmtNum(s.gross), BrandTextOnCard)
                    }
                    MonthRow("Income tax", "−₪" + fmtNum(s.incomeTax), BrandDanger)
                    MonthRow("Nat'l Insurance + health", "−₪" + fmtNum(s.niHealth), BrandDanger)
                    MonthRow("Pension (${fmtNum(s.pensionPct)}%)", "−₪" + fmtNum(s.pension), BrandDanger)
                    MonthRow("Net income", "₪" + fmtNum(s.net), BrandStart, emphasize = true)
                    if (s.savingsPct > 0) {
                        MonthRow("Savings (${fmtNum(s.savingsPct)}% of net)", "₪" + fmtNum(s.savings), BrandTextOnCard)
                        MonthRow("Left to spend", "₪" + fmtNum(s.leftToSpend), BrandStart, emphasize = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Estimate for Israeli salaried employees using 2026 tax brackets, ${fmtNum(s.creditPoints)} credit points, National Insurance + health tax rates" +
                            (if (s.overtimeEnabled) ", and 125%/150% overtime pay after 8h/day" else "") +
                            ". Not tax advice — actual payslip figures may differ.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectedIncomeToggle(expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (expanded) "Hide projected monthly income" else "See projected monthly income",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ProjectedMonthCard(
    monthStr: String,
    actual: PayCalculator.MonthSummary?,
    projected: PayCalculator.MonthSummary?,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Projected — ${monthTitle(monthStr)}", color = BrandTextOnCard, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))

            val s = projected
            when {
                s == null -> Text("Loading…", color = Color(0xFF94A3B8), fontSize = 13.sp)
                !s.hasSettings -> Text("No hourly rate is set yet.", color = Color(0xFF94A3B8), fontSize = 13.sp)
                else -> Column {
                    val regularHours = s.totalHours - s.overtimeHours
                    if (s.overtimeHours > 0) {
                        MonthRow("Hours (projected)", fmtNum(s.totalHours) + "h", BrandTextOnCard, emphasize = true)
                        MonthRow("Regular pay (${fmtNum(regularHours)}h, up to 8h/day)", "₪" + fmtNum(s.regularPay), BrandTextOnCard)
                        MonthRow("Overtime pay (${fmtNum(s.overtimeHours)}h @ 125%/150%)", "₪" + fmtNum(s.overtimePay), BrandTextOnCard)
                        MonthRow("Gross pay", "₪" + fmtNum(s.gross), BrandTextOnCard, emphasize = true)
                    } else {
                        MonthRow("Gross pay (${fmtNum(s.totalHours)}h projected)", "₪" + fmtNum(s.gross), BrandTextOnCard)
                    }
                    MonthRow("Income tax", "−₪" + fmtNum(s.incomeTax), BrandDanger)
                    MonthRow("Nat'l Insurance + health", "−₪" + fmtNum(s.niHealth), BrandDanger)
                    MonthRow("Pension (${fmtNum(s.pensionPct)}%)", "−₪" + fmtNum(s.pension), BrandDanger)
                    MonthRow("Net income (projected)", "₪" + fmtNum(s.net), BrandStart, emphasize = true)
                    if (s.savingsPct > 0) {
                        MonthRow("Savings (${fmtNum(s.savingsPct)}% of net)", "₪" + fmtNum(s.savings), BrandTextOnCard)
                        MonthRow("Left to spend", "₪" + fmtNum(s.leftToSpend), BrandStart, emphasize = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    val remainingHours = s.totalHours - (actual?.totalHours ?: 0.0)
                    Text(
                        if (remainingHours > 0) {
                            "Assumes about ${fmtNum(remainingHours)}h more, at your average logged day so far this month " +
                                "(or 8h/day if nothing's logged yet), for every day left in the month — skipping Israeli " +
                                "work holidays (Rosh Hashana, Yom Kippur, Sukkot, Pesach, Yom Ha'atzmaut, Shavuot). " +
                                "Not a guarantee — just a projection."
                        } else {
                            "Every remaining day this month is already logged (or a work holiday), so this matches the actual monthly report."
                        },
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavCircleButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(Color(0xFFF1F5F9), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = BrandTextOnCard, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MonthRow(label: String, amount: String, amountColor: Color, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFF64748B), fontSize = if (emphasize) 15.sp else 14.sp)
        Text(amount, color = amountColor, fontSize = if (emphasize) 17.sp else 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color(0xFFCBD5E1),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp, top = 4.dp),
    )
}

@Composable
private fun RecentRow(entry: LogEntry) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatDateDisplay(entry.date), color = BrandTextOnCard, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "${entry.startTime ?: "—"} → ${entry.endTime ?: "—"}",
                color = Color(0xFF64748B),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center,
            )
            Text(
                buildString {
                    append(entry.hours?.let { fmtNum(it) + "h" } ?: "—")
                    entry.money?.let { append(" · ₪" + fmtNum(it)) }
                },
                color = BrandAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = Color(0xFF94A3B8), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(20.dp))
}

private fun monthTitle(monthStr: String): String {
    val parts = monthStr.split("-").map { it.toInt() }
    val date = LocalDate.of(parts[0], parts[1], 1)
    val monthName = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return "$monthName ${date.year}"
}

private fun fmtNum(n: Double): String {
    val rounded = round(n).toInt()
    return if (rounded.toDouble() == n) rounded.toString() else "%.2f".format(n)
}
