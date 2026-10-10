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
