package com.punchcard.app.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.punchcard.app.data.LogEntry
import com.punchcard.app.logic.PayCalculator

/**
 * Reads/writes the backup CSV files through Android's Storage Access
 * Framework, into whatever folder the user picked in Settings (e.g. a
 * folder their Google Drive app syncs). This app never opens a network
 * socket itself — it just writes bytes through the SAF document tree;
 * whichever app owns that folder is responsible for actually uploading
 * it, on its own schedule, under its own permissions.
 *
 * One CSV per month for daily rows, one CSV per month for the (single,
 * only-written-at-month-end) monthly summary. Writes are upserts by key
 * (Date, or Month) so re-running a backup is always safe/idempotent.
 */
object CsvBackup {
    private const val DAILY_HEADER = "Date,Start Time,End Time,Hours,Money"
    private const val SUMMARY_HEADER =
        "Month,Total Hours,Overtime Hours,Hourly Rate,Credit Points,Gross,Income Tax,NI + Health,Pension %,Pension,Net"
    private const val BACKUP_FILE_PREFIX = "HoursBackup-"

    fun writeDailyRows(context: Context, folderUri: Uri, monthStr: String, entries: List<LogEntry>): Boolean {
        val rows = entries.associate { e ->
            e.date to listOf(
                e.date,
                e.startTime.orEmpty(),
                e.endTime.orEmpty(),
                e.hours?.toString().orEmpty(),
                e.money?.toString().orEmpty(),
            )
        }
        return upsertCsv(context, folderUri, "$BACKUP_FILE_PREFIX$monthStr.csv", DAILY_HEADER, rows)
    }

    /**
     * Reads every daily-backup CSV in [folderUri] (e.g. the same folder
     * used for writing backups) and returns every row found, across all
     * months. Used to restore local data after an app uninstall/
     * reinstall wipes the on-device database — the backup files
     * themselves live outside app storage, so they survive.
     */
    fun readAllDailyRows(context: Context, folderUri: Uri): List<LogEntry> {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        val headerKeyCell = DAILY_HEADER.substringBefore(',')
        val result = mutableListOf<LogEntry>()

        val files = folder.listFiles().filter { f ->
            val name = f.name
            name != null && name.startsWith(BACKUP_FILE_PREFIX) && name.endsWith(".csv")
        }
        for (file in files) {
            try {
                resolver.openInputStream(file.uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                        val cols = CsvFormat.parseDataLine(line, headerKeyCell) ?: return@forEachLine
                        val date = cols[0]
                        if (date.isBlank()) return@forEachLine
                        result.add(
                            LogEntry(
                                date = date,
                                startTime = cols.getOrNull(1)?.ifBlank { null },
                                endTime = cols.getOrNull(2)?.ifBlank { null },
                                hours = cols.getOrNull(3)?.toDoubleOrNull()?.takeIf { it.isFinite() },
                                money = cols.getOrNull(4)?.toDoubleOrNull()?.takeIf { it.isFinite() },
                                backedUp = true, // it came FROM a backup file, so it's already there
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                // Unreadable/corrupt file — skip it, restore what we can from the rest.
            }
        }
        return result
    }

    fun writeMonthlySummary(context: Context, folderUri: Uri, summary: PayCalculator.MonthSummary): Boolean {
        val row = listOf(
            summary.month,
            summary.totalHours.toString(),
            summary.overtimeHours.toString(),
            summary.hourlyRate.toString(),
            summary.creditPoints.toString(),
            summary.gross.toString(),
            summary.incomeTax.toString(),
            summary.niHealth.toString(),
            summary.pensionPct.toString(),
            summary.pension.toString(),
            summary.net.toString(),
        )
        return upsertCsv(context, folderUri, "MonthlySummary-${summary.month}.csv", SUMMARY_HEADER, mapOf(summary.month to row))
    }

    private fun upsertCsv(
        context: Context,
        folderUri: Uri,
        fileName: String,
        header: String,
        newRowsByKey: Map<String, List<String>>,
    ): Boolean {
        val resolver = context.contentResolver
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return false
        val existing = folder.findFile(fileName)

        val existingLines = mutableListOf<String>()
        if (existing != null) {
            try {
                resolver.openInputStream(existing.uri)?.use { input ->
                    input.bufferedReader(Charsets.UTF_8).forEachLine { existingLines.add(it) }
                }
            } catch (e: Exception) {
                // Existing file unreadable/corrupt — proceed and overwrite with what we have.
            }
        }

        // The actual merge/sort/format logic lives in CsvFormat (plain
        // Kotlin, no Android I/O) so it's covered by CsvFormatTest —
        // this function only handles reading/writing the bytes.
        val sortedRows = CsvFormat.mergeRows(header, existingLines, newRowsByKey)
        val content = CsvFormat.buildCsvContent(header, sortedRows)

        val targetFile = existing ?: folder.createFile("text/csv", fileName) ?: return false
        return try {
            resolver.openOutputStream(targetFile.uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }
}
