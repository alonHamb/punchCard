package com.punchcard.app.logic

/**
 * The 9 statutory paid holidays under Israel's Hours of Work and Rest
 * Law — the days most Israeli workplaces are closed. This is
 * deliberately *not* the same list as school holidays (no Chol HaMoed,
 * no summer break) or every religious/minor observance (no Purim, no
 * Tu BiShvat, no Tisha B'Av) — just the ones that are actual non-working
 * days for most employees: Rosh Hashana (2 days), Yom Kippur, Sukkot I,
 * Shmini Atzeret, Pesach I, Pesach VII, Yom Ha'atzmaut, and Shavuot.
 *
 * These follow the Hebrew calendar, so their Gregorian dates shift every
 * year and can't be derived with simple arithmetic — this table is
 * sourced from hebcal.com and, like PayCalculator's tax-bracket
 * constants, is a **hardcoded estimate that needs a yearly top-up**:
 * dates are only listed here through [DATES]'s last covered year: 2028.
 *
 * The 2026 dates are additionally cross-checked against the Israeli
 * Civil Service Commission's (נציבות שירות המדינה) official yearly
 * circular ("ימי מועד, ימי בחירה וימי עבודה מקוצרים לשנת 2026",
 * 11.11.2025, https://www.gov.il/BlobFolder/policy/calendar_2026/he/calendar_2026.pdf),
 * which lists exactly these same 9 dates as שעות עבודה = "אין עובדים"
 * (no work) — erev-holiday and Chol HaMoed days are explicitly shortened
 * workdays there, not full holidays, matching the exclusion here.
 */
object IsraeliHolidays {
    private val DATES: Set<String> = setOf(
        // 2025 (5785/5786)
        "2025-04-13", // Pesach I
        "2025-04-19", // Pesach VII
        "2025-05-01", // Yom Ha'atzmaut
        "2025-06-02", // Shavuot
        "2025-09-23", // Rosh Hashana I
        "2025-09-24", // Rosh Hashana II
        "2025-10-02", // Yom Kippur
        "2025-10-07", // Sukkot I
        "2025-10-14", // Shmini Atzeret

        // 2026 (5786/5787)
        "2026-04-02", // Pesach I
        "2026-04-08", // Pesach VII
        "2026-04-22", // Yom Ha'atzmaut
        "2026-05-22", // Shavuot
        "2026-09-12", // Rosh Hashana I
        "2026-09-13", // Rosh Hashana II
        "2026-09-21", // Yom Kippur
        "2026-09-26", // Sukkot I
        "2026-10-03", // Shmini Atzeret

        // 2027 (5787/5788)
        "2027-04-22", // Pesach I
        "2027-04-28", // Pesach VII
        "2027-05-12", // Yom Ha'atzmaut
        "2027-06-11", // Shavuot
        "2027-10-02", // Rosh Hashana I
        "2027-10-03", // Rosh Hashana II
        "2027-10-11", // Yom Kippur
        "2027-10-16", // Sukkot I
        "2027-10-23", // Shmini Atzeret

        // 2028 (5788/5789)
        "2028-04-11", // Pesach I
        "2028-04-17", // Pesach VII
        "2028-05-02", // Yom Ha'atzmaut
        "2028-05-31", // Shavuot
        "2028-09-21", // Rosh Hashana I
        "2028-09-22", // Rosh Hashana II
        "2028-09-30", // Yom Kippur
        "2028-10-05", // Sukkot I
        "2028-10-12", // Shmini Atzeret
    )

    /** True if [date] ("YYYY-MM-DD") is one of Israel's 9 statutory non-working holidays. */
    fun isHoliday(date: String): Boolean = date in DATES
}
