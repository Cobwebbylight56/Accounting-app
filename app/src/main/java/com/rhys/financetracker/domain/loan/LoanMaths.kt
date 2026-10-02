package com.rhys.financetracker.domain.loan

import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The sums behind a loan or mortgage, interest included.
 *
 * Interest is charged monthly at a twelfth of the yearly rate on what is
 * still owed, which is how UK loans and repayment mortgages are worked out
 * near enough to plan by. Money is in pence throughout.
 */
object LoanMaths {

    /**
     * The monthly payment that clears [owedMinor] in [months] at [yearlyRatePercent].
     * The standard repayment formula; with no interest, an even split.
     */
    fun monthlyPayment(owedMinor: Long, yearlyRatePercent: Double, months: Int): Long {
        require(months > 0) { "A loan needs at least one month" }
        val r = monthlyRate(yearlyRatePercent)
        if (r == 0.0) return ceil(owedMinor.toDouble() / months).toLong()
        return ceil(owedMinor * r / (1 - (1 + r).pow(-months))).toLong()
    }

    /**
     * How many months [monthlyMinor] takes to clear [owedMinor], or null when
     * it never does — the payment is no more than the interest each month.
     */
    fun monthsToClear(owedMinor: Long, yearlyRatePercent: Double, monthlyMinor: Long): Int? {
        if (owedMinor <= 0L) return 0
        if (monthlyMinor <= 0L) return null
        val r = monthlyRate(yearlyRatePercent)
        if (r == 0.0) return ceil(owedMinor.toDouble() / monthlyMinor).toInt()
        val interest = owedMinor * r
        if (monthlyMinor <= interest) return null
        return ceil(-ln(1 - interest / monthlyMinor) / ln(1 + r)).toInt()
    }

    /** One month of the plan: what is owed after that month's payment, and the interest in it. */
    data class Month(val index: Int, val owedAfterMinor: Long, val interestMinor: Long)

    /**
     * The month-by-month plan from now until it is clear, at most [limit]
     * months. Empty when it would never clear.
     */
    fun schedule(owedMinor: Long, yearlyRatePercent: Double, monthlyMinor: Long, limit: Int = 600): List<Month> {
        if (monthsToClear(owedMinor, yearlyRatePercent, monthlyMinor) == null) return emptyList()
        val r = monthlyRate(yearlyRatePercent)
        val months = mutableListOf<Month>()
        var owed = owedMinor.toDouble()
        var index = 0
        while (owed > 0.5 && index < limit) {
            val interest = owed * r
            owed = (owed + interest - monthlyMinor).coerceAtLeast(0.0)
            index++
            months += Month(index, owed.roundToLong(), interest.roundToLong())
        }
        return months
    }

    /** All the interest still to pay, at [monthlyMinor] a month; null when it never clears. */
    fun interestToPay(owedMinor: Long, yearlyRatePercent: Double, monthlyMinor: Long): Long? {
        if (monthsToClear(owedMinor, yearlyRatePercent, monthlyMinor) == null) return null
        return schedule(owedMinor, yearlyRatePercent, monthlyMinor).sumOf { it.interestMinor }
    }

    /** Interest in the coming month on [owedMinor] — how much of the next payment goes on it. */
    fun interestThisMonth(owedMinor: Long, yearlyRatePercent: Double): Long =
        (owedMinor * monthlyRate(yearlyRatePercent)).roundToLong()

    /** What paying [extraMinor] more a month does: months sooner, and interest saved. */
    data class Overpayment(val monthsSooner: Int, val interestSavedMinor: Long)

    fun overpay(owedMinor: Long, yearlyRatePercent: Double, monthlyMinor: Long, extraMinor: Long): Overpayment? {
        val before = monthsToClear(owedMinor, yearlyRatePercent, monthlyMinor) ?: return null
        val after = monthsToClear(owedMinor, yearlyRatePercent, monthlyMinor + extraMinor) ?: return null
        val saved = (interestToPay(owedMinor, yearlyRatePercent, monthlyMinor) ?: 0L) -
            (interestToPay(owedMinor, yearlyRatePercent, monthlyMinor + extraMinor) ?: 0L)
        return Overpayment(before - after, saved.coerceAtLeast(0L))
    }

    private fun monthlyRate(yearlyRatePercent: Double): Double = (yearlyRatePercent.coerceAtLeast(0.0) / 100.0) / 12.0
}
