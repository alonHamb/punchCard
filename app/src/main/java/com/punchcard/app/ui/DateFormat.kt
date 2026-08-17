package com.punchcard.app.ui

/**
 * The app displays dates as DD/MM/YYYY everywhere in the UI. Storage
 * stays ISO "YYYY-MM-DD" everywhere else — Room primary keys
 * (LogEntry.date, PaySettings.effectiveDate), the CSV backup files, and
 * every date-range/"effective as of" comparison in PayCalculator and
 * the DAOs all rely on that format sorting and comparing correctly as
 * plain strings. Never change the storage format — this function only
 * converts it for display.
 */
private val ISO_DATE_REGEX = Regex("""\d{4}-\d{2}-\d{2}""")

fun formatDateDisplay(isoDate: String): String {
    if (!ISO_DATE_REGEX.matches(isoDate)) return isoDate // defensive: show the raw value rather than crash on odd input
    val (y, m, d) = isoDate.split("-")
    return "$d/$m/$y"
}
