package com.punchcard.app.logic

import com.punchcard.app.data.LogEntry
import com.punchcard.app.data.PaySettings
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Pure calculation logic — no Android/Room dependencies, so it's plain,
 * fast-testable Kotlin (see src/test/.../PayCalculatorTest.kt). Mirrors
 * the Google Apps Script version used by the earlier PWA build, so the
 * numbers match if you ever compare against old data.
 *
 * IMPORTANT — this is an ESTIMATE, not payroll or tax advice. It uses
 * simplified 2026 Israeli figures (see constants below) and does not
 * model every real-world nuance (e.g. the ~35% pension tax credit
 * mechanism, marginal relief, multiple employers, non-resident status,
 * etc). Verify against your payslip / a professional for anything that
 * matters financially.
 */
object PayCalculator {

    // Fixed daily breaks, subtracted from worked hours whenever a shift
    // overlaps them. Pairs are [startMinutesFromMidnight, endMinutesFromMidnight].
    val BREAK_WINDOWS: List<IntRange> = listOf(
        (10 * 60 + 0)..(10 * 60 + 10),   // 10:00–10:10
        (12 * 60 + 40)..(13 * 60 + 20),  // 12:40–13:20
        (15 * 60 + 0)..(15 * 60 + 10),   // 15:00–15:10
    )

    // ---------------------------------------------------------------
    // 2026 Israel constants (salaried employee / "sachir"). Update yearly.
    // ---------------------------------------------------------------
    data class TaxBracket(val upTo: Double, val rate: Double)

    val TAX_BRACKETS: List<TaxBracket> = listOf(
        TaxBracket(7010.0, 0.10),
        TaxBracket(10060.0, 0.14),
        TaxBracket(19000.0, 0.20),
        TaxBracket(25100.0, 0.31),
        TaxBracket(46690.0, 0.35),
        TaxBracket(60130.0, 0.47),
        TaxBracket(Double.POSITIVE_INFINITY, 0.50),
    )
    const val CREDIT_POINT_VALUE = 242.0 // NIS per month, per point (2026)
    const val NI_THRESHOLD = 7703.0      // NIS/month — reduced rate applies up to here
    const val NI_CEILING = 51910.0       // NIS/month — max insurable income
    const val NI_RATE_LOW = 0.0427       // combined National Insurance + health, up to threshold
    const val NI_RATE_HIGH = 0.1217      // combined, above threshold up to ceiling

    // ---------------------------------------------------------------
    // Overtime ("שעות נוספות") — Israel's Hours of Work and Rest Law.
    // Simplified PER-DAY model: the first 2 hours worked beyond a
    // standard 8h day are paid at 125%, anything past that at 150%.
    // This ignores weekly-hours aggregation and shortened Friday/
    // pre-holiday days — like the rest of this app's tax math, it's a
    // documented estimate, not a legal payroll calculation.
    // ---------------------------------------------------------------
    const val REGULAR_DAILY_HOURS = 8.6
    const val OVERTIME_TIER1_HOURS = 2.0  // the 9th and 10th hour of a day
    const val OVERTIME_RATE_TIER1 = 1.25
    const val OVERTIME_RATE_TIER2 = 1.50  // the 11th hour of a day onward


    private fun round2(n: Double): Double = round(n * 100.0) / 100.0

    private fun toMinutes(hhmm: String): Int {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    /** Worked hours = End − Start, minus any overlap with BREAK_WINDOWS. */
    fun computeHours(startTime: String, endTime: String): Double {
        val startMin = toMinutes(startTime)
        var endMin = toMinutes(endTime)
        if (endMin < startMin) endMin += 24 * 60 // safety net if a shift ever crosses midnight

        var diff = (endMin - startMin).toDouble()
        for (w in BREAK_WINDOWS) {
            val overlapStart = max(startMin, w.first)
            val overlapEnd = min(endMin, w.last)
            if (overlapEnd > overlapStart) diff -= (overlapEnd - overlapStart)
        }
        diff = max(0.0, diff)
        return round2(diff / 60.0)
    }

    data class DailyPay(
        val regularHours: Double,
        val overtimeHours: Double, // tier1 + tier2 combined
        val regularPay: Double,
        val overtimePay: Double,   // premium pay for the overtime hours (125%/150% rate)
        val pay: Double,           // regularPay + overtimePay
    )

    /**
     * Splits [hours] into regular vs. overtime (125%/150%) and returns
     * the resulting pay for one day at [hourlyRate], broken into the
     * regular-pay and overtime-pay components (so the UI can show them
     * as separate line items). When [overtimeEnabled] is false, or
     * [hours] doesn't exceed [REGULAR_DAILY_HOURS], overtimePay is 0.
     */
    fun computeDailyPay(hours: Double, hourlyRate: Double, overtimeEnabled: Boolean,): DailyPay {
        if (!overtimeEnabled || hours <= REGULAR_DAILY_HOURS) {
            val pay = round2(hours * hourlyRate)
            return DailyPay(regularHours = hours, overtimeHours = 0.0, regularPay = pay, overtimePay = 0.0, pay = pay)
        }
        val extra = hours - REGULAR_DAILY_HOURS
        val tier1 = min(extra, OVERTIME_TIER1_HOURS)
        val tier2 = max(0.0, extra - OVERTIME_TIER1_HOURS)
        val regularPay = REGULAR_DAILY_HOURS * hourlyRate
        val overtimePay = tier1 * hourlyRate * OVERTIME_RATE_TIER1 + tier2 * hourlyRate * OVERTIME_RATE_TIER2
        return DailyPay(
            regularHours = REGULAR_DAILY_HOURS,
            overtimeHours = tier1 + tier2,
            regularPay = round2(regularPay),
            overtimePay = round2(overtimePay),
            pay = round2(regularPay + overtimePay),
        )
    }

    fun computeMoney(hours: Double, hourlyRate: Double, overtimeEnabled: Boolean): Double =
        computeDailyPay(hours, hourlyRate, overtimeEnabled).pay

    // internal (not private) so PayCalculatorTest can exercise the tax
    // brackets and NI thresholds directly, in addition to indirectly via
    // computeMonthSummary.
    internal fun grossIncomeTax(monthlyIncome: Double): Double {
        var tax = 0.0
        var prev = 0.0
        for (b in TAX_BRACKETS) {
            if (monthlyIncome > prev) {
                val inBracket = min(monthlyIncome, b.upTo) - prev
                tax += inBracket * b.rate
                prev = b.upTo
            } else {
                break
            }
        }
        return tax
    }

    internal fun niHealthTax(monthlyIncome: Double): Double {
        val capped = min(monthlyIncome, NI_CEILING)
        return if (capped <= NI_THRESHOLD) capped * NI_RATE_LOW
        else NI_THRESHOLD * NI_RATE_LOW + (capped - NI_THRESHOLD) * NI_RATE_HIGH
    }

    data class MonthSummary(
        val month: String,
        val hasData: Boolean,
        val hasSettings: Boolean,
        val totalHours: Double = 0.0,
        val overtimeHours: Double = 0.0,
        val regularPay: Double = 0.0,
        val overtimePay: Double = 0.0,
        val transportationCosts: Double = 0.0, // per-day reimbursement, summed across days worked (already included in gross)
        val dailySpending: Double = 0.0, // per-day spending constant, summed across days worked (already deducted from gross)
        val gross: Double = 0.0,
        val incomeTax: Double = 0.0,
        val niHealth: Double = 0.0,
        val pension: Double = 0.0,
        val pensionPct: Double = 0.0,
        val net: Double = 0.0,
        val savings: Double = 0.0,
        val savingsPct: Double = 0.0,
        val leftToSpend: Double = 0.0,
        val creditPoints: Double = 0.0,
        val hourlyRate: Double = 0.0,
        val overtimeEnabled: Boolean = false,
    )

    /**
     * [entries] should be every complete (hours != null) LogEntry whose date
     * starts with [monthStr] ("YYYY-MM"). [settings] is the single global
     * pay/tax configuration, applied uniformly to every entry regardless of
     * its date — there's no per-date history, so changing settings reshapes
     * every month's numbers, past and future alike.
     */
    fun computeMonthSummary(
        monthStr: String,
        entries: List<LogEntry>,
        settings: PaySettings?,
    ): MonthSummary {
        if (entries.isEmpty()) {
            return MonthSummary(month = monthStr, hasData = false, hasSettings = settings != null)
        }
        if (settings == null) {
            val totalHours = entries.sumOf { it.hours ?: 0.0 }
            return MonthSummary(month = monthStr, hasData = true, hasSettings = false, totalHours = round2(totalHours))
        }

        var grossTotal = 0.0
        var totalHours = 0.0
        var overtimeHoursTotal = 0.0
        var regularPayTotal = 0.0
        var overtimePayTotal = 0.0
        var transportationTotal = 0.0
        var dailySpendingTotal = 0.0
        for (entry in entries) {
            val hours = entry.hours ?: continue
            val daily = computeDailyPay(hours, settings.hourlyRate, settings.overtimeEnabled)
            grossTotal += daily.pay + settings.transportationCosts - settings.dailySpending
            overtimeHoursTotal += daily.overtimeHours
            regularPayTotal += daily.regularPay
            overtimePayTotal += daily.overtimePay
            transportationTotal += settings.transportationCosts
            dailySpendingTotal += settings.dailySpending
            totalHours += hours
        }

        val incomeTax = max(0.0, grossIncomeTax(grossTotal) - settings.creditPoints * CREDIT_POINT_VALUE)
        val niHealth = niHealthTax(grossTotal)
        val pension = grossTotal * (settings.pensionPct / 100.0)
        val net = grossTotal - incomeTax - niHealth - pension
        // Savings is a set-aside-from-net *target*, not a payroll deduction —
        // it never changes what "net income" means anywhere else in the app
        // (Home screen, widget). "Left to spend" is the only new figure that
        // actually subtracts it.
        val savings = net * (settings.savingsPct / 100.0)
        val leftToSpend = net - savings

        return MonthSummary(
            month = monthStr,
            hasData = true,
            hasSettings = true,
            totalHours = round2(totalHours),
            overtimeHours = round2(overtimeHoursTotal),
            regularPay = round2(regularPayTotal),
            overtimePay = round2(overtimePayTotal),
            transportationCosts = round2(transportationTotal),
            dailySpending = round2(dailySpendingTotal),
            gross = round2(grossTotal),
            incomeTax = round2(incomeTax),
            niHealth = round2(niHealth),
            pension = round2(pension),
            pensionPct = settings.pensionPct,
            net = round2(net),
            savings = round2(savings),
            savingsPct = settings.savingsPct,
            leftToSpend = round2(leftToSpend),
            creditPoints = settings.creditPoints,
            hourlyRate = settings.hourlyRate,
            overtimeEnabled = settings.overtimeEnabled,
        )
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        else -> 30
    }

    fun isLastDayOfMonth(date: String): Boolean {
        val parts = date.split("-").map { it.toInt() }
        val y = parts[0]; val m = parts[1]; val d = parts[2]
        return d == daysInMonth(y, m)
    }

    // Default assumed hours for a synthetic (unlogged) workday, used only
    // when nothing's been logged yet this month to average from. Thursday
    // is a shorter day; every other working day (Sun-Wed) defaults to the
    // longer figure. Neither includes BREAK_WINDOWS — those are already
    // baked into computeHours for real, logged shifts.
    const val DEFAULT_THURSDAY_HOURS = 7.5
    const val DEFAULT_WORKDAY_HOURS = 8.5

    private fun dayOfWeek(date: String): DayOfWeek {
        val parts = date.split("-").map { it.toInt() }
        return LocalDate.of(parts[0], parts[1], parts[2]).dayOfWeek
    }

    /** True if [date] is a Friday or Saturday — Israel's weekend, and not a working day. */
    fun isWeekend(date: String): Boolean {
        val dow = dayOfWeek(date)
        return dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY
    }

    private fun defaultHoursFor(date: String): Double =
        if (dayOfWeek(date) == DayOfWeek.THURSDAY) DEFAULT_THURSDAY_HOURS else DEFAULT_WORKDAY_HOURS

    /**
     * Same as [computeMonthSummary], but for every day in [monthStr] that
     * hasn't been logged yet, isn't in the past (date >= [today]), isn't
     * a weekend ([isWeekend] — Friday/Saturday), and isn't an Israeli
     * statutory holiday ([IsraeliHolidays.isHoliday] — work holidays, not
     * school holidays, since those aren't days off work), assumes a
     * projected day of [entries]'s average logged hours-per-day (or, if
     * nothing's been logged yet this month, [DEFAULT_THURSDAY_HOURS] on
     * Thursdays and [DEFAULT_WORKDAY_HOURS] on other working days) and
     * folds those synthetic days in alongside the real ones. This is a
     * "if I keep up this pace, here's roughly what the month ends at"
     * projection, not a recorded fact — a month already fully in the past
     * (every date < [today]) or fully logged naturally has no synthetic
     * days added, so it comes back identical to [computeMonthSummary].
     */
    fun computeProjectedMonthSummary(
        monthStr: String,
        entries: List<LogEntry>,
        today: String,
        settings: PaySettings?,
    ): MonthSummary {
        val loggedHours = entries.mapNotNull { it.hours }
        val avgHours = if (loggedHours.isNotEmpty()) loggedHours.sum() / loggedHours.size else null

        val loggedDates = entries.map { it.date }.toSet()
        val (year, month) = monthStr.split("-").map { it.toInt() }
        val lastDay = daysInMonth(year, month)

        val syntheticEntries = (1..lastDay).mapNotNull { day ->
            val date = "%04d-%02d-%02d".format(year, month, day)
            if (date >= today && date !in loggedDates && !isWeekend(date) && !IsraeliHolidays.isHoliday(date)) {
                LogEntry(date = date, hours = avgHours ?: defaultHoursFor(date))
            } else null
        }

        return computeMonthSummary(monthStr, entries + syntheticEntries, settings)
    }
}
