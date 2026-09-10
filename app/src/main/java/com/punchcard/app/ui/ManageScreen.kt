package com.punchcard.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.punchcard.app.data.LogEntry
import com.punchcard.app.ui.theme.BrandAccent
import com.punchcard.app.ui.theme.BrandDanger
import com.punchcard.app.ui.theme.BrandTextOnCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.round

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Browse any month, edit any day's start/end time, delete a day entirely,
 * or add a day that was never logged (e.g. you forgot to tap Start/End).
 * All changes go through HoursRepository.setEntryTimes/deleteEntry, which
 * always clear the backedUp flag — an edited or newly-added day always
 * goes out in the next backup, even if that date had already been backed
 * up before.
 */
@Composable
fun ManageScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val manageMonth by viewModel.manageMonth.collectAsState()
    val entries by viewModel.manageEntries.collectAsState()

    var editingEntry by remember { mutableStateOf<LogEntry?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text("Manage entries", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(36.dp).background(BrandAccent, CircleShape),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add day", tint = Color.White)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ManageNavButton("‹") { viewModel.shiftManageMonth(-1) }
            Text(monthTitleFull(manageMonth), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            ManageNavButton("›") { viewModel.shiftManageMonth(1) }
        }

        Spacer(Modifier.height(16.dp))

        if (entries.isEmpty()) {
            Text(
                "No entries logged in this month.",
                color = Color(0xFF94A3B8),
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(entries, key = { it.date }) { entry ->
                    ManageRow(entry = entry, onClick = { editingEntry = entry })
                }
            }
        }
    }

    editingEntry?.let { entry ->
        EntryEditDialog(
            date = entry.date,
            initialStart = entry.startTime,
            initialEnd = entry.endTime,
            dateEditable = false,
            onDismiss = { editingEntry = null },
            onSave = { date, start, end ->
                viewModel.updateEntryTimes(date, start, end)
                editingEntry = null
            },
            onDelete = {
                viewModel.deleteEntry(entry.date)
                editingEntry = null
            },
            onCheckExisting = viewModel::getEntryForDate,
        )
    }

    if (showAddDialog) {
        val today = viewModel.todayLocal()
        // Default to today if you're browsing the current month (the
        // common case: you forgot to log today/yesterday); otherwise
        // default to the 1st of whichever past month you're browsing —
        // either way it's just a starting point, the field itself accepts
        // any date at all, so backdating further is always one edit away.
        val defaultDate = if (manageMonth == today.substring(0, 7)) today else manageMonth + "-01"
        EntryEditDialog(
            date = defaultDate,
            initialStart = null,
            initialEnd = null,
            dateEditable = true,
            onDismiss = { showAddDialog = false },
            onSave = { date, start, end ->
                viewModel.updateEntryTimes(date, start, end)
                if (date.substring(0, 7) != manageMonth) viewModel.setManageMonth(date.substring(0, 7))
                showAddDialog = false
            },
            onDelete = null,
            onCheckExisting = viewModel::getEntryForDate,
        )
    }
}

@Composable
private fun ManageNavButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ManageRow(entry: LogEntry, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(formatDateDisplay(entry.date), color = BrandTextOnCard, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${entry.startTime ?: "—"} → ${entry.endTime ?: "—"}",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                )
            }
            Text(
                buildString {
                    append(entry.hours?.let { fmtNumM(it) + "h" } ?: "—")
                    entry.money?.let { append(" · ₪" + fmtNumM(it)) }
                },
                color = BrandAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * One dialog handles both editing an existing day and adding a new one.
 * For edits [dateEditable] is false (the date is fixed to that row) and a
 * Delete button is shown; for "add" the date is picked via a native
 * calendar. Start/end times are picked via a native clock and can be
 * cleared back to "not logged" with the × next to a filled-in field.
 */
@Composable
private fun EntryEditDialog(
    date: String,
    initialStart: String?,
    initialEnd: String?,
    dateEditable: Boolean,
    onDismiss: () -> Unit,
    onSave: (date: String, start: String?, end: String?) -> Unit,
    onDelete: (() -> Unit)?,
    onCheckExisting: suspend (String) -> LogEntry?,
) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }

    var pickedDate by remember { mutableStateOf(LocalDate.parse(date)) }
    var startTime by remember { mutableStateOf(initialStart?.let { LocalTime.parse(it, TIME_FORMAT) }) }
    var endTime by remember { mutableStateOf(initialEnd?.let { LocalTime.parse(it, TIME_FORMAT) }) }
    var confirmDelete by remember { mutableStateOf(false) }
    var checkingConflict by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    // Set only when Save finds an existing entry for a NEW date being
    // added — holds the entry that would get overwritten plus the
    // already-validated values waiting on the user's confirmation.
    var conflict by remember { mutableStateOf<Pair<LogEntry, Triple<String, String?, String?>>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(showDatePicker) {
        if (showDatePicker) {
            DatePickerDialog(
                context,
                { _, year, month, day -> pickedDate = LocalDate.of(year, month + 1, day) },
                pickedDate.year, pickedDate.monthValue - 1, pickedDate.dayOfMonth,
            ).apply { setOnDismissListener { showDatePicker = false } }.show()
        }
    }
    LaunchedEffect(showStartPicker) {
        if (showStartPicker) {
            val seed = startTime ?: LocalTime.of(9, 0)
            TimePickerDialog(
                context,
                { _, hour, minute -> startTime = LocalTime.of(hour, minute) },
                seed.hour, seed.minute, is24Hour,
            ).apply { setOnDismissListener { showStartPicker = false } }.show()
        }
    }
    LaunchedEffect(showEndPicker) {
        if (showEndPicker) {
            val seed = endTime ?: LocalTime.of(17, 0)
            TimePickerDialog(
                context,
                { _, hour, minute -> endTime = LocalTime.of(hour, minute) },
                seed.hour, seed.minute, is24Hour,
            ).apply { setOnDismissListener { showEndPicker = false } }.show()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (dateEditable) "Add day" else "Edit day",
                        color = BrandTextOnCard,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (dateEditable) {
                    PickerField(
                        label = "Date",
                        value = formatDateDisplay(pickedDate.toString()),
                        icon = Icons.Filled.DateRange,
                        onClick = { showDatePicker = true },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Any date works — last week, last month, any day you forgot to log.",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(formatDateDisplay(date), color = Color(0xFF64748B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                }

                TimeField(label = "Start time", time = startTime, onPick = { showStartPicker = true }, onClear = { startTime = null })
                Spacer(Modifier.height(12.dp))
                TimeField(label = "End time", time = endTime, onPick = { showEndPicker = true }, onClear = { endTime = null })

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = {
                        val normalizedDate = pickedDate.toString()
                        val normalizedStart = startTime?.format(TIME_FORMAT)
                        val normalizedEnd = endTime?.format(TIME_FORMAT)
                        if (dateEditable) {
                            // "Add day" mode: the picked date might collide
                            // with a day that's already logged — check
                            // first instead of silently overwriting it.
                            // ("Edit day" mode never hits this: its date is
                            // fixed to the row being edited, so there's
                            // nothing new to collide with.)
                            checkingConflict = true
                            scope.launch {
                                val existing = onCheckExisting(normalizedDate)
                                checkingConflict = false
                                if (existing == null) {
                                    onSave(normalizedDate, normalizedStart, normalizedEnd)
                                } else {
                                    conflict = existing to Triple(normalizedDate, normalizedStart, normalizedEnd)
                                }
                            }
                        } else {
                            onSave(normalizedDate, normalizedStart, normalizedEnd)
                        }
                    },
                    enabled = !checkingConflict,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandAccent),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (checkingConflict) "Checking…" else "Save", color = Color.White)
                }

                if (onDelete != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (confirmDelete) onDelete() else confirmDelete = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmDelete) BrandDanger else Color(0xFFFEE2E2),
                            contentColor = if (confirmDelete) Color.White else BrandDanger,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (confirmDelete) "Tap again to confirm delete" else "Delete this day")
                    }
                }
            }
        }
    }

    // A second, smaller confirmation dialog stacked on top of the edit
    // dialog — shown only in "Add day" mode, only after Save finds that
    // the typed date already has an entry (see onCheckExisting above).
    // Cancel leaves the edit dialog open with nothing saved; Overwrite
    // saves the already-validated values that were waiting in [conflict].
    conflict?.let { (existing, pending) ->
        val (pendingDate, pendingStart, pendingEnd) = pending
        Dialog(onDismissRequest = { conflict = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Overwrite existing entry?", color = BrandTextOnCard, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${formatDateDisplay(existing.date)} already has an entry logged (${existing.startTime ?: "—"} → ${existing.endTime ?: "—"}" +
                            (existing.hours?.let { ", ${fmtNumM(it)}h" } ?: "") +
                            "). Saving will replace it with what you just entered — this can't be undone.",
                        color = Color(0xFF64748B),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { conflict = null },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = BrandTextOnCard),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                onSave(pendingDate, pendingStart, pendingEnd)
                                conflict = null
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDanger),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text("Overwrite", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun fieldColors(dimText: Boolean = false) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color(0xFFF8FAFC),
    focusedTextColor = if (dimText) Color(0xFF94A3B8) else Color(0xFF0F172A),
    unfocusedTextColor = if (dimText) Color(0xFF94A3B8) else Color(0xFF0F172A),
    cursorColor = BrandAccent,
)

/** A labeled, read-only field that opens a native picker dialog when tapped — used for [date]. */
@Composable
private fun PickerField(label: String, value: String, icon: ImageVector, onClick: () -> Unit) {
    Column {
        Text(label, color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { Icon(icon, contentDescription = null, tint = BrandAccent) },
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.matchParentSize().clickable(onClick = onClick))
        }
    }
}

/**
 * A [PickerField] for an optional clock time — shows "Not logged" in muted
 * text when [time] is null, and a × next to the field to clear it back to
 * null once a time has been picked.
 */
@Composable
private fun TimeField(label: String, time: LocalTime?, onPick: () -> Unit, onClear: () -> Unit) {
    Column {
        Text(label, color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = time?.format(TIME_FORMAT) ?: "Not logged",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Filled.AccessTime, contentDescription = null, tint = BrandAccent) },
                    colors = fieldColors(dimText = time == null),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.matchParentSize().clickable(onClick = onPick))
            }
            if (time != null) {
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onClear, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear $label", tint = Color(0xFF94A3B8))
                }
            }
        }
    }
}

private fun monthTitleFull(monthStr: String): String {
    val parts = monthStr.split("-").map { it.toInt() }
    val date = LocalDate.of(parts[0], parts[1], 1)
    val monthName = date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return "$monthName ${date.year}"
}

private fun fmtNumM(n: Double): String {
    val rounded = round(n).toInt()
    return if (rounded.toDouble() == n) rounded.toString() else "%.2f".format(n)
}
