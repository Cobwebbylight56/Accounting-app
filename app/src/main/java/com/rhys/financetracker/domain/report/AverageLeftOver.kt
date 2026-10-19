package com.rhys.financetracker.domain.report

/**
 * What is left over in a month, on average: what came in, less what went
 * out, was put aside and paid off loans — the month's "Left over" — taken
 * across the last few full months that had pay in them.
 */
data class AverageLeftOver(
    val perMonthMinor: Long,
    /** How many months it is the average of. */
    val months: Int,
) {
    companion object {
        /** The average over [months], from those months' totals; null with no months to go on. */
        fun of(incomeMinor: Long, expenseMinor: Long, savedMinor: Long, loanPaymentsMinor: Long, months: Int): AverageLeftOver? {
            if (months <= 0) return null
            return AverageLeftOver((incomeMinor - expenseMinor - savedMinor - loanPaymentsMinor) / months, months)
        }

        /** How many full months, at most, it is taken over. */
        const val MONTHS = 3L
    }
}

/** One month of the average: what came in, went out, was put aside and paid off loans. */
data class MonthLeftOver(
    val month: java.time.YearMonth,
    val inMinor: Long,
    val outMinor: Long,
    val savedMinor: Long,
    val loansMinor: Long,
) {
    val leftMinor: Long get() = inMinor - outMinor - savedMinor - loansMinor
}

/**
 * How "Left over a month, on average" was worked out: each month, and the
 * biggest payments counted as spending — where a payment that isn't really
 * spending (a move between your own accounts, a card paid off) shows up.
 */
data class LeftOverExplained(
    val months: List<MonthLeftOver>,
    val biggest: List<com.rhys.financetracker.data.local.projection.TransactionWithDetails>,
) {
    val averageMinor: Long get() = if (months.isEmpty()) 0L else months.sumOf { it.leftMinor } / months.size
}
