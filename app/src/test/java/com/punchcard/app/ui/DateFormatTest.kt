package com.punchcard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain-JVM tests for the ISO -> DD/MM/YYYY display conversion (DateFormat.kt). */
class DateFormatTest {

    @Test
    fun `converts ISO date to DD slash MM slash YYYY`() {
        assertEquals("17/08/2026", formatDateDisplay("2026-08-17"))
    }

    @Test
    fun `keeps leading zeros on single digit day and month`() {
        assertEquals("05/01/2026", formatDateDisplay("2026-01-05"))
    }

    @Test
    fun `falls back to the raw string for malformed input rather than crashing`() {
        assertEquals("not-a-date", formatDateDisplay("not-a-date"))
        assertEquals("2026-08", formatDateDisplay("2026-08")) // missing day
    }

    @Test
    fun `round trips through normalizeDate back to the same ISO string`() {
        // DateFormat.formatDateDisplay and ManageScreen's normalizeDate
        // are inverses of each other — a value shown on screen and typed
        // back in must resolve to the exact same stored date.
        val iso = "2026-08-17"
        assertEquals(iso, normalizeDate(formatDateDisplay(iso)))
    }
}
