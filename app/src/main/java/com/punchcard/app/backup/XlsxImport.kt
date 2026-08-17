package com.punchcard.app.backup

import android.content.Context
import android.net.Uri
import com.punchcard.app.data.LogEntry
import java.util.zip.ZipInputStream

/**
 * Reads a .xlsx file the user picked (Settings' "Import from
 * spreadsheet") and turns it into [LogEntry] rows via [XlsxFormat]. A
 * .xlsx is just a zip of XML parts, so this only needs `java.util.zip`
 * — no third-party spreadsheet library, consistent with the rest of the
 * app's "no extra dependencies" approach to file formats (see
 * CsvFormat/CsvBackup for the same split).
 *
 * `ZipInputStream` reads forward-only, which is what SAF's content://
 * streams support (they're not always seekable) — so this does a
 * single linear pass over every entry, keeping only the two parts it
 * needs (the chosen worksheet + shared strings) instead of trying to
 * seek directly to them.
 */
object XlsxImport {

    fun readEntries(context: Context, fileUri: Uri): List<LogEntry> {
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                var sharedStringsXml: String? = null
                val sheetsByName = mutableMapOf<String, String>()
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "xl/sharedStrings.xml" -> sharedStringsXml = zip.readBytes().toString(Charsets.UTF_8)
                            name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml") ->
                                sheetsByName[name] = zip.readBytes().toString(Charsets.UTF_8)
                        }
                        entry = zip.nextEntry
                    }
                }
                val sheetXml = XlsxFormat.pickSheetEntryName(sheetsByName.keys.toList())?.let { sheetsByName[it] }
                    ?: return emptyList()
                XlsxFormat.parseSheet(sheetXml, XlsxFormat.parseSharedStrings(sharedStringsXml))
            } ?: emptyList()
        } catch (e: Exception) {
            // Not a valid .xlsx / unreadable / unexpected internal layout —
            // treat the same as "nothing found" rather than crashing; the
            // caller shows a "no rows found" message either way.
            emptyList()
        }
    }
}
