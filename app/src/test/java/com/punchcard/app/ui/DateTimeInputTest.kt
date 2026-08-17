package com.punchcard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for normalizeTime/normalizeDate (declared in ManageScreen.kt) —
 * the manual entry parsing that has to work both with punctuation
 * (":", "/", "-", ".") and as plain digits, since Android's Number-type
 * keyboard has no punctuation keys at all (see CHANGELOG v7). Plain-JVM
 * tests: these functions touch only java.time and Regex, no Compose/
 * Android runtime, so no emulator is needed.
 */
class DateTimeInputTest {

    // ---- normalizeTime ----

    @Test
    fun `accepts punctuated HH colon mm`() {
        assertEquals("09:00", normalizeTime("09:00"))
        assertEquals("23:59", normalizeTime("23:59"))
        assertEquals("00:00", normalizeTime("00:00"))
    }

    @Test
    fun `accepts four digit time with no separator`() {
        assertEquals("09:00", normalizeTime("0900"))
        assertEquals("17:30", normalizeTime("1730"))
    }

    @Test
    fun `accepts three digit time, left padded`() {
        assertEquals("09:00", normalizeTime("900"))
        assertEquals("09:05", normalizeTime("905"))
    }

    @Test
    fun `rejects an invalid hour or minute in either form`() {
        assertNull(normalizeTime("25:00"))
        assertNull(normalizeTime("09:60"))
        assertNull(normalizeTime("2500"))
        assertNull(normalizeTime("0960"))
    }

    @Test
    fun `rejects garbage or out-of-range-length input`() {
        assertNull(normalizeTime(""))
        assertNull(normalizeTime("abcd"))
        assertNull(normalizeTime("9"))
        assertNull(normalizeTime("99999"))
    }

    @Test
    fun `time field rejects input that a bluetooth keyboard or paste could produce past the Number keyboard`() {
        // The field is hinted as Number-type, but nothing stops a pasted
        // string, autofill suggestion, or physical keyboard from putting
        // arbitrary text in — these must degrade to "invalid", not crash.
        assertNull(normalizeTime("-900")) // looks numeric but isn't digits-only
        assertNull(normalizeTime("9.00"))
        assertNull(normalizeTime("09 : 00")) // spaces inside a punctuated time
        assertNull(normalizeTime("٠٩:٠٠")) // Arabic-Indic digits, not ASCII \d
        assertNull(normalizeTime("0".repeat(5000))) // pathologically long, still just rejected
        assertNull(normalizeTime("09:00:00")) // seconds component
    }

    // ---- normalizeDate ----

    @Test
    fun `accepts slash separated DD MM YYYY`() {
        assertEquals("2026-08-17", normalizeDate("17/08/2026"))
    }

    @Test
    fun `accepts dash separated DD MM YYYY`() {
        assertEquals("2026-08-17", normalizeDate("17-08-2026"))
    }

    @Test
    fun `accepts dot separated DD MM YYYY`() {
        assertEquals("2026-08-17", normalizeDate("17.08.2026"))
    }

    @Test
    fun `pads a single digit day or month`() {
        assertEquals("2026-08-07", normalizeDate("7/8/2026"))
    }

    @Test
    fun `accepts eight digit DDMMYYYY with no separator at all`() {
        assertEquals("2026-08-17", normalizeDate("17082026"))
    }

    @Test
    fun `rejects a calendar-invalid date`() {
        assertNull(normalizeDate("31/02/2026")) // February never has 31 days
        assertNull(normalizeDate("32/01/2026")) // no 32nd day
        assertNull(normalizeDate("17/13/2026")) // no month 13
        assertNull(normalizeDate("29/02/2026")) // 2026 is not a leap year
    }

    @Test
    fun `accepts Feb 29 on an actual leap year`() {
        assertEquals("2028-02-29", normalizeDate("29/02/2028"))
    }

    @Test
    fun `rejects malformed or incomplete input`() {
        assertNull(normalizeDate(""))
        assertNull(normalizeDate("17082026x"))
        assertNull(normalizeDate("1708")) // digits-only but not exactly 8
        assertNull(normalizeDate("17/08")) // missing year
        assertNull(normalizeDate("not a date"))
    }

    @Test
    fun `date field rejects input that a bluetooth keyboard or paste could produce past the Number keyboard`() {
        // Same concern as normalizeTime: the field only *hints* at a
        // numeric keyboard, so arbitrary pasted/typed text must degrade
        // to "invalid" rather than crash or silently misparse.
        assertNull(normalizeDate("   ")) // whitespace only
        assertNull(normalizeDate("-17/08/2026")) // leading minus breaks the day-length check
        assertNull(normalizeDate("17/08/2026/2027")) // too many separated parts
        assertNull(normalizeDate("17/0a/2026")) // letters mixed into a punctuated part
        assertNull(normalizeDate("١٧٠٨٢٠٢٦")) // Arabic-Indic digits, not ASCII
        assertNull(normalizeDate("1".repeat(5000))) // pathologically long, still just rejected
        assertNull(normalizeDate("17 08 2026")) // space-separated, no recognized separator
    }
}
