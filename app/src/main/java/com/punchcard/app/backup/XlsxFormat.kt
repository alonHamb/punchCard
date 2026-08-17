package com.punchcard.app.backup

import com.punchcard.app.data.LogEntry
import java.time.LocalDate

/**
 * Pure, Android-free .xlsx parsing logic used by [XlsxImport] — kept in
 * its own object (no `Context`/`Uri`/zip-stream calls) purely so it's
 * covered by fast JVM unit tests (XlsxFormatTest) that don't need a
 * device or emulator. [XlsxImport] itself is left thin: it just unzips
 * the file and hands the raw `sheet*.xml` / `sharedStrings.xml` text to
 * these functions.
 *
 * .xlsx is a zip of XML parts (OOXML). We only ever need three columns
 * out of one worksheet — matched by *header text*, not fixed column
 * letters, so a sheet with extra/reordered columns (like the one this
 * was built against, which also has computed pay/tax columns after the
 * three we care about) still imports correctly. Everything else in the
 * file — formulas, other columns, other sheets — is ignored. Pay/tax
 * settings are never read from the file, same as CSV restore: the
 * app's current Settings apply to imported days.
 */
internal object XlsxFormat {

    // Excel's "1900 date system" epoch: serial 1 = 1900-01-01, and the
    // conventional epoch used to reproduce that (including its
    // intentional fictitious-Feb-29-1900 quirk) is 1899-12-30.
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private const val EXCEL_MAX_SERIAL = 2_958_465.0 // 9999-12-31, sanity ceiling

    private val ROW_REGEX = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL_REGEX = Regex(
        """<c r="([A-Z]+)\d+"[^>]*?/>|<c r="([A-Z]+)\d+"([^>]*)>(.*?)</c>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val VALUE_REGEX = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val SI_REGEX = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val T_REGEX = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)

    /** Picks which worksheet part to read: "sheet1.xml" if present (the
     *  overwhelmingly common case — a single-sheet export), else
     *  whichever `xl/worksheets/sheet*.xml` sorts first. */
    fun pickSheetEntryName(entryNames: List<String>): String? {
        val sheets = entryNames.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        return sheets.firstOrNull { it == "xl/worksheets/sheet1.xml" } ?: sheets.minOrNull()
    }

    /** Parses `xl/sharedStrings.xml` into an index-ordered list of plain
     *  text — cells of type `t="s"` store an index into this list rather
     *  than their text inline. Each `<si>` entry's text runs (`<t>`,
     *  possibly split across multiple `<r>` rich-text runs) are
     *  concatenated. Returns an empty list if [xml] is null/blank/absent
     *  (a sheet with no text cells at all doesn't need this part). */
    fun parseSharedStrings(xml: String?): List<String> {
        if (xml.isNullOrBlank()) return emptyList()
        return SI_REGEX.findAll(xml).map { si ->
            T_REGEX.findAll(si.groupValues[1]).joinToString("") { unescapeXml(it.groupValues[1]) }
        }.toList()
    }

    internal fun unescapeXml(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x?[0-9A-Fa-f]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> runCatching {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
            }
        }
    }

    /** Excel serial date (days since the 1900 epoch) → ISO "YYYY-MM-DD",
     *  or null if [serial] isn't a sane finite date. */
    internal fun excelSerialToIsoDate(serial: Double): String? {
        if (!serial.isFinite() || serial < 1.0 || serial > EXCEL_MAX_SERIAL) return null
        return runCatching { EXCEL_EPOCH.plusDays(serial.toLong()).toString() }.getOrNull()
    }

    /** Excel time-of-day fraction (0.0..1.0, e.g. 0.5 = noon) → "HH:mm",
     *  or null if [fraction] is outside that range. Rounds to the
     *  nearest minute. */
    internal fun excelFractionToTime(fraction: Double): String? {
        if (!fraction.isFinite() || fraction < 0.0 || fraction >= 1.0) return null
        val totalMinutes = Math.round(fraction * 24.0 * 60.0)
        if (totalMinutes !in 0..1439) return null
        return "%02d:%02d".format(totalMinutes / 60, totalMinutes % 60)
    }

    private data class Cell(val col: String, val attrs: String, val content: String)

    private fun parseCells(rowContent: String): List<Cell> =
        CELL_REGEX.findAll(rowContent).map { m ->
            val selfClosingCol = m.groupValues[1]
            if (selfClosingCol.isNotEmpty()) {
                Cell(selfClosingCol, "", "")
            } else {
                Cell(m.groupValues[2], m.groupValues[3], m.groupValues[4])
            }
        }.toList()

    private fun cellText(cell: Cell, sharedStrings: List<String>): String? {
        val raw = VALUE_REGEX.find(cell.content)?.groupValues?.get(1) ?: return null
        return if (cell.attrs.contains("t=\"s\"")) {
            raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }
        } else {
            unescapeXml(raw)
        }
    }

    /** Numeric cell value, or null if the cell is blank, a shared-string
     *  reference (dates/times must be real numbers, not text), or not a
     *  parseable number. */
    private fun cellNumeric(cell: Cell): Double? {
        if (cell.attrs.contains("t=\"s\"")) return null
        return VALUE_REGEX.find(cell.content)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Parses one worksheet's raw XML into log entries. The first row
     * found is treated as the header and searched (case-insensitively,
     * by substring) for columns named like "date", "start of day", and
     * "end of day" — matching this format's own header labels. Every
     * later row contributes an entry if it has a valid date and at
     * least one of start/end; rows with no date (including the
     * formula-only template rows past the real data, which this format
     * leaves in the sheet) are skipped entirely.
     */
    fun parseSheet(sheetXml: String, sharedStrings: List<String>): List<LogEntry> {
        val rows = ROW_REGEX.findAll(sheetXml).map { it.groupValues[1] }.iterator()
        if (!rows.hasNext()) return emptyList()

        val headerCells = parseCells(rows.next())
        var dateCol: String? = null
        var startCol: String? = null
        var endCol: String? = null
        for (cell in headerCells) {
            val text = cellText(cell, sharedStrings)?.trim()?.lowercase() ?: continue
            if (dateCol == null && "date" in text) dateCol = cell.col
            if (startCol == null && "start" in text) startCol = cell.col
            if (endCol == null && "end" in text) endCol = cell.col
        }
        val dCol = dateCol ?: return emptyList()

        val entries = mutableListOf<LogEntry>()
        while (rows.hasNext()) {
            val cellsByCol = parseCells(rows.next()).associateBy { it.col }
            val date = cellsByCol[dCol]?.let { cellNumeric(it) }?.let { excelSerialToIsoDate(it) } ?: continue
            val start = startCol?.let { cellsByCol[it] }?.let { cellNumeric(it) }?.let { excelFractionToTime(it) }
            val end = endCol?.let { cellsByCol[it] }?.let { cellNumeric(it) }?.let { excelFractionToTime(it) }
            if (start == null && end == null) continue
            entries.add(LogEntry(date = date, startTime = start, endTime = end))
        }
        return entries
    }
}
