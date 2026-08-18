package com.punchcard.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.punchcard.app.ui.theme.BrandAccent

@Composable
fun SettingsScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val paySettings by viewModel.paySettings.collectAsState()
    val folderName by viewModel.folderName.collectAsState()

    var hourlyRate by remember { mutableStateOf("") }
    var creditPoints by remember { mutableStateOf("") }
    var pensionPct by remember { mutableStateOf("6") }
    var savingsPct by remember { mutableStateOf("0") }
    var overtimeEnabled by remember { mutableStateOf(true) }
    var prefilled by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    val restoreStatus by viewModel.restoreStatus.collectAsState()
    val spreadsheetImportStatus by viewModel.spreadsheetImportStatus.collectAsState()

    LaunchedEffect(paySettings) {
        if (!prefilled && paySettings != null) {
            hourlyRate = paySettings!!.hourlyRate.toString()
            creditPoints = paySettings!!.creditPoints.toString()
            pensionPct = paySettings!!.pensionPct.toString()
            savingsPct = paySettings!!.savingsPct.toString()
            overtimeEnabled = paySettings!!.overtimeEnabled
            prefilled = true
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setBackupFolder(uri)
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.restoreFromFolder(uri)
    }

    val spreadsheetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFromSpreadsheet(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard {
            Text("Pay & tax settings", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Used to estimate net income (Israel, salaried employee): income tax, National Insurance + health tax, and pension are calculated automatically. Changing these only affects today onward — past months keep using the settings active at the time.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            LabeledField("Hourly rate (₪)", hourlyRate, { hourlyRate = it }, KeyboardType.Decimal)
            Spacer(Modifier.height(12.dp))
            LabeledField("Tax credit points", creditPoints, { creditPoints = it }, KeyboardType.Decimal)
            Spacer(Modifier.height(12.dp))
            LabeledField("Pension deduction (%)", pensionPct, { pensionPct = it }, KeyboardType.Decimal)
            Spacer(Modifier.height(12.dp))
            LabeledField("Savings target (% of net income)", savingsPct, { savingsPct = it }, KeyboardType.Decimal)
            Spacer(Modifier.height(4.dp))
            Text(
                "Doesn't change net income — just splits it into \"Savings\" and \"Left to spend\" on the Home screen, as a target for what to set aside. Leave at 0 to turn this off.",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Overtime pay", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "125% after 8h/day, 150% after 10h/day.",
                        color = Color(0xFF64748B),
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = overtimeEnabled,
                    onCheckedChange = { overtimeEnabled = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = BrandAccent),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val rate = hourlyRate.toDoubleOrNull()
                    val points = creditPoints.toDoubleOrNull()
                    val pension = pensionPct.toDoubleOrNull()
                    val savings = savingsPct.toDoubleOrNull()
                    saveMessage = when {
                        rate == null || !rate.isFinite() || rate <= 0 -> "Enter a valid hourly rate."
                        points == null || !points.isFinite() || points < 0 -> "Enter valid credit points."
                        pension == null || !pension.isFinite() || pension < 0 || pension >= 100 ->
                            "Enter a valid pension % (0–99)."
                        savings == null || !savings.isFinite() || savings < 0 || savings >= 100 ->
                            "Enter a valid savings % (0–99)."
                        else -> {
                            viewModel.savePaySettings(rate, points, pension, overtimeEnabled, savings)
                            "Saved — applies from today onward."
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandAccent),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save pay settings", color = Color.White)
            }
            saveMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFF64748B), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard {
            Text("Backup folder", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Choose a folder for the nightly CSV backup — e.g. a folder your Google Drive app keeps synced. This app never connects to the internet itself; it only writes files into this folder, and only between 18:00–06:00 while on wifi (or when you tap \"Back up now\").",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                folderName?.let { "Current folder: $it" } ?: "No folder chosen yet",
                color = if (folderName != null) Color(0xFF0F172A) else Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { folderPicker.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(if (folderName == null) "Choose folder" else "Change folder")
            }
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard {
            Text("Restore from an old backup", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "If you reinstalled the app (or set it up on a new phone), pick the folder your old backups are in — usually the same folder as above. Any day found there that isn't already in the app gets added back; days already here are never touched or overwritten.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { restorePicker.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Restore from backup folder")
            }
            restoreStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFF64748B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Note: this only restores logged days (start/end/hours/money). Your hourly rate and tax settings aren't stored in the backup files, so re-enter those above if needed.",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(20.dp))
        SettingsCard {
            Text("Import from spreadsheet", color = Color(0xFF0F172A), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Bring in days from an Excel (.xlsx) spreadsheet with a header row containing \"date\", \"start of day\", and \"end of day\" columns — any other columns are ignored. Any date found there that isn't already logged in the app gets added; days already here are never touched or overwritten.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    spreadsheetPicker.launch(
                        arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9), contentColor = Color(0xFF0F172A)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Choose spreadsheet (.xlsx)")
            }
            spreadsheetImportStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFF64748B), fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Note: only the date/start/end columns are read. Your hourly rate and tax settings aren't read from the file, so this app's current Settings apply to the imported days.",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType) {
    Column {
        Text(label, color = Color(0xFF64748B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color(0xFFF8FAFC),
                // The app's theme is a dark scheme (white is the default
                // "onSurface" text color), which made typed text invisible
                // against these white/near-white fields. Pin it dark here.
                focusedTextColor = Color(0xFF0F172A),
                unfocusedTextColor = Color(0xFF0F172A),
                cursorColor = BrandAccent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
