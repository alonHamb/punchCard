package com.punchcard.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for the CSV building/parsing/merging logic behind
 * the backup files — no SAF/ContentResolver/device needed, run instantly
 * via `./gradlew test`.
 */
class CsvFormatTest {

    // ---- escapeCsv ----

    @Test
    fun `plain value is not quoted`() {
        assertEquals("09:00", CsvFormat.escapeCsv("09:00"))
    }

    @Test
    fun `value with a comma is quoted`() {
        assertEquals("\"a,b\"", CsvFormat.escapeCsv("a,b"))
    }

    @Test
    fun `embedded quotes are doubled and the whole value wrapped`() {
        assertEquals("\"say \"\"hi\"\"\"", CsvFormat.escapeCsv("say \"hi\""))
    }

    @Test
    fun `value with an embedded newline is quoted`() {
        assertEquals("\"line1\nline2\"", CsvFormat.escapeCsv("line1\nline2"))
    }

    // ---- parseCsvLine ----

    @Test
    fun `parses a simple comma separated line`() {
        assertEquals(
            listOf("2026-08-17", "09:00", "17:00", "8.0", "480.0"),
            CsvFormat.parseCsvLine("2026-08-17,09:00,17:00,8.0,480.0"),
        )
    }

    @Test
    fun `parses a quoted field containing a comma`() {
        assertEquals(
            listOf("2026-08", "note, with comma", "5"),
            CsvFormat.parseCsvLine("2026-08,\"note, with comma\",5"),
        )
    }

    @Test
    fun `parses doubled quotes inside a quoted field`() {
        assertEquals(listOf("say \"hi\"", "ok"), CsvFormat.parseCsvLine("\"say \"\"hi\"\"\",ok"))
    }

    @Test
    fun `escape then parse round trips any value`() {
        val original = listOf("2026-08-17", "a,b", "say \"hi\"", "plain", "")
        val line = original.joinToString(",") { CsvFormat.escapeCsv(it) }
        assertEquals(original, CsvFormat.parseCsvLine(line))
    }

    // ---- parseDataLine ----

    @Test
    fun `parseDataLine skips blank lines and the header row`() {
        assertNull(CsvFormat.parseDataLine("", "Date"))
        assertNull(CsvFormat.parseDataLine("   ", "Date"))
        assertNull(CsvFormat.parseDataLine("Date,Start Time,End Time,Hours,Money", "Date"))
    }

    @Test
    fun `parseDataLine still recognizes an older-schema header with fewer columns`() {
        // Regression test for a real bug: a summary file written before
        // "Overtime Hours" was added to the header must still have its
        // header row skipped, not misread as a bogus data row keyed
        // "Month" (see CHANGELOG v6).
        assertNull(CsvFormat.parseDataLine("Month,Total Hours,Hourly Rate,Gross", "Month"))
    }

    @Test
    fun `parseDataLine returns columns for an actual data row`() {
        assertEquals(
            listOf("2026-08-17", "09:00", "17:00", "8.0", "480.0"),
            CsvFormat.parseDataLine("2026-08-17,09:00,17:00,8.0,480.0", "Date"),
        )
    }

    // ---- mergeRows ----

    @Test
    fun `mergeRows keeps existing rows and overwrites by key rather than duplicating`() {
        val header = "Date,Start Time,End Time,Hours,Money"
        val existing = listOf(
            header,
            "2026-08-01,09:00,17:00,8.0,480.0",
            "2026-08-02,09:00,17:00,8.0,480.0",
        )
        val updated = mapOf("2026-08-02" to listOf("2026-08-02", "09:00", "18:00", "9.0", "540.0"))
        val merged = CsvFormat.mergeRows(header, existing, updated)

        assertEquals(2, merged.size) // not 3 — the 2nd is overwritten, not appended
        assertEquals(listOf("2026-08-01", "09:00", "17:00", "8.0", "480.0"), merged[0])
        assertEquals(listOf("2026-08-02", "09:00", "18:00", "9.0", "540.0"), merged[1])
    }

    @Test
    fun `mergeRows sorts the result by key regardless of insertion order`() {
        val header = "Date,Start Time,End Time,Hours,Money"
        val existing = listOf(header, "2026-08-03,09:00,17:00,8.0,480.0")
        val added = mapOf("2026-08-01" to listOf("2026-08-01", "09:00", "17:00", "8.0", "480.0"))
        val merged = CsvFormat.mergeRows(header, existing, added)
        assertEquals(listOf("2026-08-01", "2026-08-03"), merged.map { it[0] })
    }

    @Test
    fun `mergeRows tolerates an older-schema header when merging`() {
        // Existing file was written with the old (pre-overtime) summary
        // header; the new header has an extra "Overtime Hours" column.
        // Its old header row must still be recognized by matching only
        // the first cell, so the old data row underneath it survives
        // instead of the header being misread as a bogus data row.
        val oldHeader = "Month,Total Hours,Hourly Rate,Gross"
        val newHeader = "Month,Total Hours,Overtime Hours,Hourly Rate,Gross"
        val existing = listOf(oldHeader, "2026-07,160,50,8000")
        val added = mapOf("2026-08" to listOf("2026-08", "170", "5", "50", "8750"))
        val merged = CsvFormat.mergeRows(newHeader, existing, added)

        assertEquals(2, merged.size)
        assertEquals("2026-07", merged[0][0])
        assertEquals("2026-08", merged[1][0])
    }

    @Test
    fun `mergeRows with no existing lines just returns the new rows sorted`() {
        val header = "Date,Hours"
        val added = mapOf(
            "2026-08-02" to listOf("2026-08-02", "8.0"),
            "2026-08-01" to listOf("2026-08-01", "7.0"),
        )
        val merged = CsvFormat.mergeRows(header, emptyList(), added)
        assertEquals(listOf("2026-08-01", "2026-08-02"), merged.map { it[0] })
    }

    // ---- buildCsvContent ----

    @Test
    fun `buildCsvContent renders header then rows joined by newline`() {
        val header = "Date,Hours"
        val rows = listOf(listOf("2026-08-01", "8.0"), listOf("2026-08-02", "7.5"))
        assertEquals("Date,Hours\n2026-08-01,8.0\n2026-08-02,7.5\n", CsvFormat.buildCsvContent(header, rows))
    }

    @Test
    fun `buildCsvContent escapes values that need it`() {
        val header = "Date,Note"
        val rows = listOf(listOf("2026-08-01", "has, a comma"))
        assertEquals("Date,Note\n2026-08-01,\"has, a comma\"\n", CsvFormat.buildCsvContent(header, rows))
    }

    @Test
    fun `full upsert cycle is idempotent`() {
        // Simulates running the same backup write twice in a row —
        // merging the identical rows again must not change the output.
        val header = "Date,Start Time,End Time,Hours,Money"
        val rows = mapOf("2026-08-17" to listOf("2026-08-17", "09:00", "17:00", "7.0", "420.0"))
        val firstPass = CsvFormat.buildCsvContent(header, CsvFormat.mergeRows(header, emptyList(), rows))
        val existingLines = firstPass.lines().filter { it.isNotBlank() }
        val secondPass = CsvFormat.buildCsvContent(header, CsvFormat.mergeRows(header, existingLines, rows))
        assertEquals(firstPass, secondPass)
    }
}
