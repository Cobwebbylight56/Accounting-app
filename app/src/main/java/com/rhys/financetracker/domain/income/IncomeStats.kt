package com.rhys.financetracker.domain.income

/**
 * What a person's yearly pay says about their month.
 *
 * Built from the two figures a person gives for themselves — pay before tax
 * and take-home after it — because a statement only ever shows the second,
 * and only month by month. Together they answer the questions a budget is
 * built on: how much actually arrives each month, how much of the headline
 * figure goes before it does, and what share of what arrives is being spent
 * or put aside.
 */
data class IncomeStats(
    /** Yearly pay before tax, in minor units. */
    val grossYearlyMinor: Long?,
    /** Yearly take-home after tax, in minor units. */
    val netYearlyMinor: Long?,
) {
    val hasAny: Boolean get() = grossYearlyMinor != null || netYearlyMinor != null

    /** Take-home in an average month. */
    val netMonthlyMinor: Long? get() = netYearlyMinor?.let { it / MONTHS }

    val grossMonthlyMinor: Long? get() = grossYearlyMinor?.let { it / MONTHS }

    /** Tax, National Insurance, pension and anything else taken before it arrives. */
    val deductionsYearlyMinor: Long?
        get() = if (grossYearlyMinor != null && netYearlyMinor != null) {
            grossYearlyMinor - netYearlyMinor
        } else {
            null
        }

    /** The share of gross pay taken before it arrives, as a whole percentage. */
    val deductionPercent: Int?
        get() {
            val gross = grossYearlyMinor ?: return null
            val deductions = deductionsYearlyMinor ?: return null
            if (gross <= 0L) return null
            return percent(deductions, gross)
        }

    /** What share of a month's take-home [amountMinor] is; null without take-home. */
    fun shareOfMonthlyTakeHome(amountMinor: Long): Int? {
        val monthly = netMonthlyMinor ?: return null
        if (monthly <= 0L) return null
        return percent(amountMinor, monthly)
    }

    operator fun plus(other: IncomeStats): IncomeStats = IncomeStats(
        grossYearlyMinor = sumOrNull(grossYearlyMinor, other.grossYearlyMinor),
        netYearlyMinor = sumOrNull(netYearlyMinor, other.netYearlyMinor),
    )

    companion object {
        const val MONTHS = 12L
        val NONE = IncomeStats(null, null)

        private fun percent(part: Long, whole: Long): Int =
            Math.round(part.toDouble() * 100.0 / whole.toDouble()).toInt()

        private fun sumOrNull(a: Long?, b: Long?): Long? =
            if (a == null && b == null) null else (a ?: 0L) + (b ?: 0L)
    }
}
