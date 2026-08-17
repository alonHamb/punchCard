package com.punchcard.app.backup

/**
 * Pure, Android-free CSV building/parsing/merging logic used by
 * [CsvBackup] — kept in its own object (no `Context`/`Uri`/SAF calls)
 * purely so it's covered by fast JVM unit tests (CsvFormatTest) that
 * don't need a device or emulator. [CsvBackup] itself is left thin: it
 * just does the file I/O and hands raw lines/rows to these functions.
 */
internal object CsvFormat {

    fun escapeCsv(value: String): String =
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }

    // Minimal CSV parser — sufficient for our own comma/quote-escaped output.
    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            sb.append('"'); i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        sb.append(c)
                    }
                }
                c == '"' -> inQuotes = true
                c == ',' -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    /**
     * Parses one already-read CSV line into columns, or returns null if
     * it's blank or is the header row itself — matched by comparing only
     * the first cell of [headerKeyCell] ("Date"/"Month") against the
     * row's first cell, not the whole line. That's what lets a file
     * written by an older/newer app version with a different column
     * count still have its header correctly recognized and skipped,
     * instead of being misread as a bogus data row (see CHANGELOG v6 —
     * this was a real bug once, now regression-tested).
     */
    fun parseDataLine(line: String, headerKeyCell: String): List<String>? {
        if (line.isBlank()) return null
        val cols = parseCsvLine(line)
        if (cols.isEmpty() || cols[0] == headerKeyCell) return null
        return cols
    }

    /**
     * Merges [newRowsByKey] into whatever data rows are found in
     * [existingLines] (header/blank lines skipped per [parseDataLine]),
     * new rows replacing any existing row with the same key, and returns
     * the full set sorted by key — ready for [buildCsvContent]. This is
     * what makes re-running a backup, or backing up an edited day,
     * idempotent: it overwrites the row in place rather than duplicating it.
     */
    fun mergeRows(
        header: String,
        existingLines: List<String>,
        newRowsByKey: Map<String, List<String>>,
    ): List<List<String>> {
        val headerKeyCell = header.substringBefore(',')
        val rowsByKey = LinkedHashMap<String, List<String>>()
        for (line in existingLines) {
            val cols = parseDataLine(line, headerKeyCell) ?: continue
            rowsByKey[cols[0]] = cols
        }
        for ((key, row) in newRowsByKey) rowsByKey[key] = row
        return rowsByKey.values.sortedBy { it.getOrNull(0).orEmpty() }
    }

    /** Renders [header] followed by [rows] (already merged/sorted) as full CSV text. */
    fun buildCsvContent(header: String, rows: List<List<String>>): String = buildString {
        append(header).append('\n')
        for (row in rows) append(row.joinToString(",") { escapeCsv(it) }).append('\n')
    }
}
