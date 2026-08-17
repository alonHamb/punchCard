package com.punchcard.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain-JVM tests for XlsxFormat — the pure .xlsx-parsing logic behind
 * Settings' "Import from spreadsheet" feature. No Android/zip-stream
 * dependency, so these run instantly via `./gradlew test`.
 */
class XlsxFormatTest {

    // ---- excelSerialToIsoDate ----

    @Test
    fun `converts a known excel serial to the matching ISO date`() {
        // 2026-08-04, taken from a real exported sheet.
        assertEquals("2026-08-04", XlsxFormat.excelSerialToIsoDate(46238.0))
    }

    @Test
    fun `rejects non-finite or out-of-range serials`() {
        assertNull(XlsxFormat.excelSerialToIsoDate(Double.NaN))
        assertNull(XlsxFormat.excelSerialToIsoDate(Double.POSITIVE_INFINITY))
        assertNull(XlsxFormat.excelSerialToIsoDate(0.0))
        assertNull(XlsxFormat.excelSerialToIsoDate(-5.0))
        assertNull(XlsxFormat.excelSerialToIsoDate(99_999_999.0))
    }

    // ---- excelFractionToTime ----

    @Test
    fun `converts known day fractions to HH mm`() {
        assertEquals("11:30", XlsxFormat.excelFractionToTime(0.4791666666666667))
        assertEquals("16:55", XlsxFormat.excelFractionToTime(0.7048611111111112))
        assertEquals("00:00", XlsxFormat.excelFractionToTime(0.0))
    }

    @Test
    fun `rejects fractions outside 0 to 1 or non-finite`() {
        assertNull(XlsxFormat.excelFractionToTime(-0.1))
        assertNull(XlsxFormat.excelFractionToTime(1.0))
        assertNull(XlsxFormat.excelFractionToTime(1.5))
        assertNull(XlsxFormat.excelFractionToTime(Double.NaN))
        assertNull(XlsxFormat.excelFractionToTime(Double.POSITIVE_INFINITY))
    }

    // ---- parseSharedStrings ----

    @Test
    fun `extracts plain text entries in index order`() {
        val xml = "<sst><si><t>date</t></si><si><t>start of day</t></si></sst>"
        assertEquals(listOf("date", "start of day"), XlsxFormat.parseSharedStrings(xml))
    }

    @Test
    fun `concatenates split rich-text runs within one entry`() {
        val xml = "<sst><si><r><t>hello </t></r><r><t>world</t></r></si></sst>"
        assertEquals(listOf("hello world"), XlsxFormat.parseSharedStrings(xml))
    }

    @Test
    fun `unescapes XML entities including numeric references`() {
        val xml = "<sst><si><t>Q&amp;A &lt;test&gt; &#39;quoted&#39;</t></si></sst>"
        assertEquals(listOf("Q&A <test> 'quoted'"), XlsxFormat.parseSharedStrings(xml))
    }

    @Test
    fun `returns empty list for null or blank input`() {
        assertTrue(XlsxFormat.parseSharedStrings(null).isEmpty())
        assertTrue(XlsxFormat.parseSharedStrings("").isEmpty())
        assertTrue(XlsxFormat.parseSharedStrings("   ").isEmpty())
    }

    // ---- pickSheetEntryName ----

    @Test
    fun `prefers sheet1 xml when present`() {
        val names = listOf("xl/worksheets/sheet2.xml", "xl/worksheets/sheet1.xml")
        assertEquals("xl/worksheets/sheet1.xml", XlsxFormat.pickSheetEntryName(names))
    }

    @Test
    fun `falls back to the alphabetically first worksheet when sheet1 is absent`() {
        val names = listOf("xl/worksheets/sheet3.xml", "xl/worksheets/sheet2.xml")
        assertEquals("xl/worksheets/sheet2.xml", XlsxFormat.pickSheetEntryName(names))
    }

    @Test
    fun `returns null when there are no worksheet entries`() {
        assertNull(XlsxFormat.pickSheetEntryName(listOf("xl/sharedStrings.xml", "xl/styles.xml")))
    }

    // ---- parseSheet ----

    private val sharedStrings = listOf("date", "start of day", "end of day", "work hours")

    private fun headerRow() =
        """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>"""

    @Test
    fun `parses a realistic sheet with a header row and blank template rows`() {
        // Mirrors the real exported format: row 2 has real data (with a
        // formula cell in D that must be ignored), row 3 is a blank
        // template row with self-closing cells and no real date.
        val xml = headerRow() +
            """<row r="2"><c r="A2"><v>46238.0</v></c><c r="B2"><v>0.4791666666666667</v></c><c r="C2"><v>0.7048611111111112</v></c>""" +
            """<c r="D2"><f t="shared" ref="D2:D23" si="1">if((C2-B2)&gt;4/24,(C2-B2)-(1/24),(C2-B2))</f><v>0.1840277778</v></c></row>""" +
            """<row r="3"><c r="A3"/><c r="B3"/><c r="C3"/><c r="D3"><f t="shared" si="1"/><v>0</v></c></row>"""

        val entries = XlsxFormat.parseSheet(xml, sharedStrings)

        assertEquals(1, entries.size)
        assertEquals("2026-08-04", entries[0].date)
        assertEquals("11:30", entries[0].startTime)
        assertEquals("16:55", entries[0].endTime)
    }

    @Test
    fun `matches header columns by substring regardless of order or extra columns`() {
        // Columns reordered (end before start) plus an extra unrelated
        // column — header matching must still find the right ones.
        val strings = listOf("notes", "end of day", "date", "start of day")
        val xml = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c></row>""" +
            """<row r="2"><c r="A2"><v>text-not-used</v></c><c r="B2"><v>0.7048611111111112</v></c><c r="C2"><v>46238.0</v></c><c r="D2"><v>0.4791666666666667</v></c></row>"""

        val entries = XlsxFormat.parseSheet(xml, strings)

        assertEquals(1, entries.size)
        assertEquals("2026-08-04", entries[0].date)
        assertEquals("11:30", entries[0].startTime)
        assertEquals("16:55", entries[0].endTime)
    }

    @Test
    fun `keeps a row with only a start or only an end time`() {
        val xml = headerRow() +
            """<row r="2"><c r="A2"><v>46238.0</v></c><c r="B2"><v>0.5</v></c></row>""" +
            """<row r="3"><c r="A3"><v>46239.0</v></c><c r="C3"><v>0.75</v></c></row>"""

        val entries = XlsxFormat.parseSheet(xml, sharedStrings)

        assertEquals(2, entries.size)
        assertEquals("12:00", entries[0].startTime)
        assertNull(entries[0].endTime)
        assertNull(entries[1].startTime)
        assertEquals("18:00", entries[1].endTime)
    }

    @Test
    fun `skips rows with no date and rows with neither start nor end`() {
        val xml = headerRow() +
            """<row r="2"><c r="B2"><v>0.5</v></c><c r="C2"><v>0.75</v></c></row>""" + // no date at all
            """<row r="3"><c r="A3"><v>46238.0</v></c></row>""" // date but no times

        assertTrue(XlsxFormat.parseSheet(xml, sharedStrings).isEmpty())
    }

    @Test
    fun `returns empty when the header has no recognizable date column`() {
        val xml = """<row r="1"><c r="A1" t="s"><v>3</v></c></row>""" + // "work hours" only
            """<row r="2"><c r="A2"><v>46238.0</v></c></row>"""
        assertTrue(XlsxFormat.parseSheet(xml, sharedStrings).isEmpty())
    }

    @Test
    fun `ignores a date cell that is a shared-string reference instead of a real number`() {
        // A date typed/formatted as text rather than a real Excel date
        // serial must not be misread as a serial number via its string index.
        val strings = listOf("date", "start of day", "end of day", "17/08/2026")
        val xml = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c></row>""" +
            """<row r="2"><c r="A2" t="s"><v>3</v></c><c r="B2"><v>0.5</v></c><c r="C2"><v>0.75</v></c></row>"""

        assertTrue(XlsxFormat.parseSheet(xml, strings).isEmpty())
    }

    @Test
    fun `empty sheet yields no entries`() {
        assertTrue(XlsxFormat.parseSheet("", sharedStrings).isEmpty())
    }
}
