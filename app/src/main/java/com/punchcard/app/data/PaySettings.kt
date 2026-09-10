package com.punchcard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pay/tax settings — a single global row, always applied to every entry
 * regardless of date. Saving new settings overwrites this row outright;
 * there is no history, so a rate change also reshapes past months'
 * numbers, not just future ones.
 */
@Entity(tableName = "pay_settings")
data class PaySettings(
    @PrimaryKey val id: Int = 0,             // fixed singleton row
    val hourlyRate: Double,                  // gross NIS per hour
    val creditPoints: Double,                // Israeli tax credit points (e.g. 2.25)
    val pensionPct: Double,                  // pension deduction, e.g. 6.0 for 6%
    val overtimeEnabled: Boolean = true,     // 125%/150% pay after 8h/day (see PayCalculator)
    val savingsPct: Double = 0.0,            // set-aside-from-net-income target, e.g. 10.0 for 10%
    val transportationCosts: Double = 0.0,   // per-day transportation reimbursement, added to gross pay for every day worked
    val dailySpending: Double = 0.0,         // per-day spending constant, subtracted from gross pay for every day worked
)
