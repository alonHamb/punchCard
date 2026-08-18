package com.punchcard.app.logic

import com.punchcard.app.data.LogEntry
import com.punchcard.app.data.PaySettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests — no Android device/emulator needed, run instantly
 * via `./gradlew test` (or the gutter "run" icon in Android Studio).
 */
class PayCalculatorTest {

    @Test
    fun `full day minus all three breaks`() {
        // 09:00-16:00 spans all three break windows: 10 + 40 + 10 = 60 min removed from 7h.
        assertEquals(6.0, PayCalculator.computeHours("09:00", "16:00"), 0.001)
    }

    @Test
    fun `short shift with no break overlap`() {
        assertEquals(0.5, PayCalculator.computeHours("09:00", "09:30"), 0.001)
    }

    @Test
    fun `shift fully inside a break is zero hours`() {
        assertEquals(0.0, PayCalculator.computeHours("10:05", "10:08"), 0.001)
    }

    @Test
    fun `full work day example`() {
        assertEquals(8.0, PayCalculator.computeHours("08:00", "17:00"), 0.001)
    }

    @Test
    fun `last day of month handles february and leap years`() {
        assertTrue(PayCalculator.isLastDayOfMonth("2026-08-31"))
        assertTrue(!PayCalculator.isLastDayOfMonth("2026-08-30"))
        assertTrue(PayCalculator.isLastDayOfMonth("2026-02-28")) // 2026 is not a leap year
        assertTrue(PayCalculator.isLastDayOfMonth("2028-02-29")) // 2028 is a leap year
    }

    @Test
    fun `month summary computes gross tax NI and net`() = runTest {
        val settings = PaySettings(effectiveDate = "2026-08-01", hourlyRate = 60.0, creditPoints = 2.25, pensionPct = 6.0)
        val entries = listOf(
            LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "17:00", hours = 8.0),
            LogEntry(date = "2026-08-04", startTime = "09:00", endTime = "17:00", hours = 8.0),
        )
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertTrue(summary.hasData)
        assertTrue(summary.hasSettings)
        assertEquals(16.0, summary.totalHours, 0.001)
        assertEquals(960.0, summary.gross, 0.001) // 16h * 60
        assertTrue(summary.net < summary.gross)
        assertTrue(summary.net > 0)
    }

    @Test
    fun `savings is a percentage of net income and never changes net`() = runTest {
        val settings = PaySettings(
            effectiveDate = "2026-08-01",
            hourlyRate = 60.0,
            creditPoints = 2.25,
            pensionPct = 6.0,
            savingsPct = 10.0,
        )
        val entries = listOf(LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "17:00", hours = 8.0))
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertEquals(10.0, summary.savingsPct, 0.001)
        assertEquals(summary.net * 0.10, summary.savings, 0.01)
        assertEquals(summary.net - summary.savings, summary.leftToSpend, 0.001)
        // Changing savingsPct must never change what net income itself means.
        val noSavingsSettings = settings.copy(savingsPct = 0.0)
        val summaryNoSavings = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { noSavingsSettings },
            getEarliest = { noSavingsSettings },
        )
        assertEquals(summaryNoSavings.net, summary.net, 0.001)
    }

    @Test
    fun `zero savings pct leaves leftToSpend equal to net`() = runTest {
        val settings = PaySettings(effectiveDate = "2026-08-01", hourlyRate = 60.0, creditPoints = 2.25, pensionPct = 6.0)
        val entries = listOf(LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "17:00", hours = 8.0))
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertEquals(0.0, summary.savings, 0.001)
        assertEquals(summary.net, summary.leftToSpend, 0.001)
    }

    @Test
    fun `no entries yields hasData false`() = runTest {
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-09",
            entries = emptyList(),
            getForDateOrBefore = { null },
            getEarliest = { null },
        )
        assertTrue(!summary.hasData)
    }

    @Test
    fun `no overtime within the regular 8h day`() {
        val daily = PayCalculator.computeDailyPay(hours = 8.0, hourlyRate = 60.0, overtimeEnabled = true)
        assertEquals(8.0, daily.regularHours, 0.001)
        assertEquals(0.0, daily.overtimeHours, 0.001)
        assertEquals(480.0, daily.pay, 0.001) // 8h * 60
    }

    @Test
    fun `first two overtime hours pay 125 percent`() {
        // 9 worked hours: 8 regular + 1 hour of tier-1 overtime.
        val daily = PayCalculator.computeDailyPay(hours = 9.0, hourlyRate = 60.0, overtimeEnabled = true)
        assertEquals(8.0, daily.regularHours, 0.001)
        assertEquals(1.0, daily.overtimeHours, 0.001)
        // 8*60 + 1*60*1.25 = 480 + 75 = 555
        assertEquals(555.0, daily.pay, 0.001)
    }

    @Test
    fun `hours past ten pay 150 percent`() {
        // 11 worked hours: 8 regular + 2 tier-1 (125%) + 1 tier-2 (150%).
        val daily = PayCalculator.computeDailyPay(hours = 11.0, hourlyRate = 60.0, overtimeEnabled = true)
        assertEquals(8.0, daily.regularHours, 0.001)
        assertEquals(3.0, daily.overtimeHours, 0.001)
        // 8*60 + 2*60*1.25 + 1*60*1.5 = 480 + 150 + 90 = 720
        assertEquals(720.0, daily.pay, 0.001)
    }

    @Test
    fun `overtime disabled pays flat rate regardless of hours`() {
        val daily = PayCalculator.computeDailyPay(hours = 11.0, hourlyRate = 60.0, overtimeEnabled = false)
        assertEquals(0.0, daily.overtimeHours, 0.001)
        assertEquals(660.0, daily.pay, 0.001) // 11h * 60, no premium
    }

    @Test
    fun `month summary includes overtime pay in gross`() = runTest {
        val settings = PaySettings(
            effectiveDate = "2026-08-01",
            hourlyRate = 60.0,
            creditPoints = 2.25,
            pensionPct = 6.0,
            overtimeEnabled = true,
        )
        // One regular 8h day, one 10h day (8 regular + 2 tier-1 overtime).
        val entries = listOf(
            LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "17:00", hours = 8.0),
            LogEntry(date = "2026-08-04", startTime = "09:00", endTime = "19:00", hours = 10.0),
        )
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertEquals(18.0, summary.totalHours, 0.001)
        assertEquals(2.0, summary.overtimeHours, 0.001)
        // Day 1: 480. Day 2: 8*60 + 2*60*1.25 = 480 + 150 = 630. Total 1110.
        assertEquals(1110.0, summary.gross, 0.001)
        // Regular pay: 8*60 (day 1) + 8*60 (day 2's regular portion) = 960.
        assertEquals(960.0, summary.regularPay, 0.001)
        // Overtime pay: day 2's 2 tier-1 hours at 125% = 2*60*1.25 = 150.
        assertEquals(150.0, summary.overtimePay, 0.001)
        assertEquals(summary.gross, summary.regularPay + summary.overtimePay, 0.001)
    }

    // ---- computeHours edge cases ----

    @Test
    fun `shift crossing midnight is measured forward`() {
        // 22:00 to 02:00 the next calendar day = 4 raw hours, no break overlap.
        assertEquals(4.0, PayCalculator.computeHours("22:00", "02:00"), 0.001)
    }

    @Test
    fun `shift starting mid-break only subtracts the overlapping portion`() {
        // 10:05-10:30 overlaps 5 of the 10:00-10:10 break's 10 minutes,
        // leaving 25 - 5 = 20 minutes = 0.33h once rounded to 2 decimals.
        assertEquals(0.33, PayCalculator.computeHours("10:05", "10:30"), 0.001)
    }

    @Test
    fun `identical start and end is zero hours, not negative`() {
        assertEquals(0.0, PayCalculator.computeHours("09:00", "09:00"), 0.001)
    }

    // ---- income tax brackets ----

    @Test
    fun `income tax is computed per progressive bracket`() {
        // Exactly the first bracket's ceiling: 7010 * 10% = 701.
        assertEquals(701.0, PayCalculator.grossIncomeTax(7010.0), 0.001)
        // One shekel into the second (14%) bracket: 701 + 1 * 0.14 = 701.14.
        assertEquals(701.14, PayCalculator.grossIncomeTax(7011.0), 0.001)
        // Zero income owes zero tax.
        assertEquals(0.0, PayCalculator.grossIncomeTax(0.0), 0.001)
    }

    @Test
    fun `income tax accumulates correctly across many brackets`() {
        // 25100 lands exactly on the 31% bracket's ceiling: sum of each
        // full bracket below it.
        val expected = 7010.0 * 0.10 + (10060.0 - 7010.0) * 0.14 + (19000.0 - 10060.0) * 0.20 + (25100.0 - 19000.0) * 0.31
        assertEquals(expected, PayCalculator.grossIncomeTax(25100.0), 0.001)
    }

    // ---- National Insurance + health tax ----

    @Test
    fun `NI and health tax applies the reduced rate up to the threshold`() {
        assertEquals(7703.0 * 0.0427, PayCalculator.niHealthTax(7703.0), 0.001)
    }

    @Test
    fun `NI and health tax applies the higher rate above the threshold`() {
        val income = 10000.0
        val expected = 7703.0 * 0.0427 + (10000.0 - 7703.0) * 0.1217
        assertEquals(expected, PayCalculator.niHealthTax(income), 0.001)
    }

    @Test
    fun `NI and health tax is capped at the insurable ceiling`() {
        val atCeiling = PayCalculator.niHealthTax(PayCalculator.NI_CEILING)
        val aboveCeiling = PayCalculator.niHealthTax(PayCalculator.NI_CEILING + 5000.0)
        assertEquals(atCeiling, aboveCeiling, 0.001) // income past the ceiling isn't taxed further
    }

    @Test
    fun `income tax continues accumulating through the top brackets`() {
        // 46690 lands exactly on the 35% bracket's ceiling.
        val expectedAt46690 = 7010.0 * 0.10 + (10060.0 - 7010.0) * 0.14 + (19000.0 - 10060.0) * 0.20 +
            (25100.0 - 19000.0) * 0.31 + (46690.0 - 25100.0) * 0.35
        assertEquals(expectedAt46690, PayCalculator.grossIncomeTax(46690.0), 0.001)

        // 60130 lands exactly on the 47% bracket's ceiling.
        val expectedAt60130 = expectedAt46690 + (60130.0 - 46690.0) * 0.47
        assertEquals(expectedAt60130, PayCalculator.grossIncomeTax(60130.0), 0.001)

        // Above 60130, the top 50% bracket applies to the remainder.
        val expectedAt70130 = expectedAt60130 + 10000.0 * 0.50
        assertEquals(expectedAt70130, PayCalculator.grossIncomeTax(70130.0), 0.001)
    }

    @Test
    fun `exactly ten hours is all tier-1 overtime with no tier-2`() {
        val daily = PayCalculator.computeDailyPay(hours = 10.0, hourlyRate = 60.0, overtimeEnabled = true)
        assertEquals(2.0, daily.overtimeHours, 0.001)
        // 8*60 + 2*60*1.25 = 480 + 150 = 630
        assertEquals(630.0, daily.pay, 0.001)
    }

    @Test
    fun `settingsForDate falls back to the earliest settings when the date predates all of them`() = runTest {
        val earliest = PaySettings(effectiveDate = "2026-06-01", hourlyRate = 55.0, creditPoints = 2.25, pensionPct = 6.0)
        val result = PayCalculator.settingsForDate(
            date = "2026-01-01", // predates the only known settings row
            getForDateOrBefore = { null },
            getEarliest = { earliest },
        )
        assertEquals(55.0, result?.hourlyRate ?: -1.0, 0.001)
    }

    @Test
    fun `credit points never push income tax below zero`() = runTest {
        // A tiny income with generous credit points would go negative
        // without the max(0.0, ...) floor in computeMonthSummary.
        val settings = PaySettings(effectiveDate = "2026-08-01", hourlyRate = 10.0, creditPoints = 10.0, pensionPct = 0.0)
        val entries = listOf(LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "10:00", hours = 1.0))
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertEquals(0.0, summary.incomeTax, 0.001)
        assertEquals(summary.gross - summary.niHealth - summary.pension, summary.net, 0.001)
    }

    @Test
    fun `entries with no hours logged are skipped without crashing`() = runTest {
        val settings = PaySettings(effectiveDate = "2026-08-01", hourlyRate = 60.0, creditPoints = 2.25, pensionPct = 6.0)
        // An in-progress day (Start logged, End not yet) has hours = null
        // and should be ignored by the month total, not thrown as an error.
        val entries = listOf(
            LogEntry(date = "2026-08-03", startTime = "09:00", endTime = "17:00", hours = 7.0),
            LogEntry(date = "2026-08-04", startTime = "09:00", endTime = null, hours = null),
        )
        val summary = PayCalculator.computeMonthSummary(
            monthStr = "2026-08",
            entries = entries,
            getForDateOrBefore = { settings },
            getEarliest = { settings },
        )
        assertEquals(7.0, summary.totalHours, 0.001)
    }
}
