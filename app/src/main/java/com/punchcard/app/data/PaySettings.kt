package com.punchcard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pay/tax settings, kept as an append-only, effective-dated history —
 * never overwritten in place. This is what makes "changing my rate today
 * doesn't rewrite last month's numbers" work: every calculation looks up
 * whichever row was in effect on the date being calculated, not just the
 * latest one.
 */
@Entity(tableName = "pay_settings")
data class PaySettings(
    @PrimaryKey val effectiveDate: String, // "YYYY-MM-DD" — applies from this date onward
    val hourlyRate: Double,                // gross NIS per hour
    val creditPoints: Double,              // Israeli tax credit points (e.g. 2.25)
    val pensionPct: Double,                // pension deduction, e.g. 6.0 for 6%
    val overtimeEnabled: Boolean = true,   // 125%/150% pay after 8h/day (see PayCalculator)
    val savingsPct: Double = 0.0,          // set-aside-from-net-income target, e.g. 10.0 for 10%
)
