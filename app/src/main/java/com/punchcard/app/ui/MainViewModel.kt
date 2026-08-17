package com.punchcard.app.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.punchcard.app.backup.BackupPreferences
import com.punchcard.app.backup.BackupScheduler
import com.punchcard.app.backup.CsvBackup
import com.punchcard.app.data.AppDatabase
import com.punchcard.app.data.HoursRepository
import com.punchcard.app.data.LogEntry
import com.punchcard.app.data.PaySettings
import com.punchcard.app.logic.PayCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val repo = HoursRepository(db.logEntryDao(), db.paySettingsDao())
    private val prefs = BackupPreferences(application)

    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

    fun todayLocal(): String = LocalDate.now().format(dateFmt)
    fun nowTimeLocal(): String = LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }
    fun currentMonthStr(): String = todayLocal().substring(0, 7)

    // The date "today" currently refers to. Re-pointed by refreshDateIfChanged()
    // so the Start/End button and Today card roll over automatically at
    // midnight even if the app is left open across the date change —
    // "today" always means the current calendar date, never a stale one.
    private val _todayDate = MutableStateFlow(todayLocal())

    val today: StateFlow<LogEntry?> =
        _todayDate.flatMapLatest { date -> repo.observeByDate(date) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Call periodically (e.g. from the UI's clock tick) to catch the
     *  date rolling over to a new day while the app stays open. */
    fun refreshDateIfChanged() {
        val current = todayLocal()
        if (_todayDate.value != current) _todayDate.value = current
    }

    val recent: StateFlow<List<LogEntry>> =
        repo.observeRecent(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingBackupCount: StateFlow<Int> =
        repo.observePendingBackupCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val paySettings: StateFlow<PaySettings?> =
        repo.observeLatestPaySettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _viewMonth = MutableStateFlow(currentMonthStr())
    val viewMonth: StateFlow<String> = _viewMonth.asStateFlow()

    private val _monthSummary = MutableStateFlow<PayCalculator.MonthSummary?>(null)
    val monthSummary: StateFlow<PayCalculator.MonthSummary?> = _monthSummary.asStateFlow()

    private val _backupStatus = MutableStateFlow<String?>(null)
    val backupStatus: StateFlow<String?> = _backupStatus.asStateFlow()

    private val _folderName = MutableStateFlow(prefs.getFolderDisplayName())
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    init {
        loadMonth(_viewMonth.value)
    }

    fun loadMonth(monthStr: String) {
        _viewMonth.value = monthStr
        viewModelScope.launch {
            _monthSummary.value = repo.getMonthSummary(monthStr)
        }
    }

    fun shiftMonth(delta: Int) {
        val (y, m) = _viewMonth.value.split("-").map { it.toInt() }
        val d = LocalDate.of(y, m, 1).plusMonths(delta.toLong())
        val next = "%04d-%02d".format(d.year, d.monthValue)
        if (next > currentMonthStr()) return // no future months
        loadMonth(next)
    }

    /**
     * Logs "now" for today. Whether this counts as Start or End is
     * decided fresh from the database each time (see
     * HoursRepository.logNext): if today has no start yet, this is a
     * Start; End only ever happens after a Start has already been
     * logged for today. Refreshes whichever month card is in view if
     * it happens to be this month.
     */
    fun logNow() {
        refreshDateIfChanged()
        viewModelScope.launch {
            val date = todayLocal()
            val time = nowTimeLocal()
            val (_, complete) = repo.logNext(date, time)
            if (_viewMonth.value == date.substring(0, 7)) loadMonth(_viewMonth.value)
            if (complete) _backupStatus.value = "Day complete — queued for tonight's backup."
        }
    }

    // ----- Manage screen: browse/edit/delete any day's entry, any month -----

    private val _manageMonth = MutableStateFlow(currentMonthStr())
    val manageMonth: StateFlow<String> = _manageMonth.asStateFlow()

    val manageEntries: StateFlow<List<LogEntry>> =
        _manageMonth.flatMapLatest { month -> repo.observeForMonth(month) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun shiftManageMonth(delta: Int) {
        val (y, m) = _manageMonth.value.split("-").map { it.toInt() }
        val d = LocalDate.of(y, m, 1).plusMonths(delta.toLong())
        _manageMonth.value = "%04d-%02d".format(d.year, d.monthValue)
    }

    /** Jump straight to a specific "YYYY-MM" — used after adding an entry
     *  whose date falls in a different month than the one being browsed. */
    fun setManageMonth(monthStr: String) {
        _manageMonth.value = monthStr
    }

    /**
     * Overwrites a day's start/end time (Manage screen edit, or adding a
     * day that was never logged). Also refreshes the Home screen's month
     * summary if the edited date falls in the month currently shown there
     * — "today" and "recent" update on their own since those are live
     * database Flows, but the month summary is a one-shot snapshot.
     */
    fun updateEntryTimes(date: String, startTime: String?, endTime: String?) {
        viewModelScope.launch {
            repo.setEntryTimes(date, startTime, endTime)
            if (_viewMonth.value == date.substring(0, 7)) loadMonth(_viewMonth.value)
        }
    }

    /** Whatever's already logged for [date], if anything — used to warn
     *  before "Add day" silently overwrites an existing entry. */
    suspend fun getEntryForDate(date: String): LogEntry? = repo.getEntry(date)

    fun deleteEntry(date: String) {
        viewModelScope.launch {
            repo.deleteEntry(date)
            if (_viewMonth.value == date.substring(0, 7)) loadMonth(_viewMonth.value)
        }
    }

    fun savePaySettings(hourlyRate: Double, creditPoints: Double, pensionPct: Double, overtimeEnabled: Boolean) {
        viewModelScope.launch {
            repo.savePaySettings(hourlyRate, creditPoints, pensionPct, overtimeEnabled, todayLocal())
            loadMonth(_viewMonth.value)
        }
    }

    fun setBackupFolder(uri: Uri) {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        prefs.setFolderUri(uri)
        val name = DocumentFile.fromTreeUri(app, uri)?.name ?: uri.lastPathSegment ?: "Chosen folder"
        prefs.setFolderDisplayName(name)
        _folderName.value = name
    }

    fun backupNow() {
        BackupScheduler.triggerNow(getApplication())
        _backupStatus.value = "Backup requested — check your folder in a few seconds."
    }

    fun clearBackupStatus() {
        _backupStatus.value = null
    }

    private val _restoreStatus = MutableStateFlow<String?>(null)
    val restoreStatus: StateFlow<String?> = _restoreStatus.asStateFlow()

    /**
     * Reads every HoursBackup-*.csv found in [uri] (e.g. the same
     * Drive-synced folder used for backups) and fills in any date that's
     * missing from the local database — this is how logged days survive
     * an app uninstall/reinstall, since Android wipes the on-device
     * database along with the app itself; only files outside app
     * storage (like these backup CSVs) survive that. Never overwrites a
     * day that's already logged locally. If no backup folder is set yet,
     * this folder is also adopted as the backup folder going forward,
     * since restoring from a folder you'll keep backing up to is the
     * common case.
     */
    fun restoreFromFolder(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            // readAllDailyRows does blocking file I/O — push it off the
            // main dispatcher (viewModelScope.launch defaults to Main)
            // so restoring a folder with many months of CSVs can't
            // freeze the UI.
            val found = withContext(Dispatchers.IO) { CsvBackup.readAllDailyRows(app, uri) }
            val imported = repo.importFromBackup(found)
            _restoreStatus.value = when {
                found.isEmpty() -> "No backup files found in that folder."
                imported == 0 -> "Found ${found.size} backed-up day${if (found.size != 1) "s" else ""}, but everything was already here."
                else -> "Restored $imported day${if (imported != 1) "s" else ""} from backup."
            }
            if (prefs.getFolderUri() == null) {
                prefs.setFolderUri(uri)
                val name = DocumentFile.fromTreeUri(app, uri)?.name ?: uri.lastPathSegment ?: "Chosen folder"
                prefs.setFolderDisplayName(name)
                _folderName.value = name
            }
            loadMonth(_viewMonth.value)
        }
    }

    fun clearRestoreStatus() {
        _restoreStatus.value = null
    }
}
