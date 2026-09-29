package com.rhys.financetracker.domain.income

import kotlin.math.roundToLong

/**
 * Working out new pay from how a rise was given.
 *
 * Rises arrive in three shapes — "3% from April", "an extra £1,500 a year",
 * "your new salary is £29,000" — and asking for all three as a new salary
 * means doing the sum by hand. Each is taken as it was given.
 */
object PayRise {

    enum class Given(val displayName: String) {
        PERCENT("A percentage"),
        AMOUNT("An amount a year"),
        NEW_PAY("My new yearly pay"),
    }

    /** The new yearly pay before tax, in minor units, or null when it cannot be worked out. */
    fun newGross(currentGrossMinor: Long?, given: Given, value: Double): Long? = when (given) {
        Given.PERCENT -> currentGrossMinor?.let { (it * (1.0 + value / 100.0)).roundToLong() }
        Given.AMOUNT -> currentGrossMinor?.let { it + (value * 100.0).roundToLong() }
        Given.NEW_PAY -> (value * 100.0).roundToLong()
    }?.takeIf { it > 0L }

    /**
     * Take-home after a rise, when it has not been given: the same share of
     * gross as before.
     *
     * Only an estimate — tax bands mean a rise is taxed a little harder than
     * the pay beneath it — and it is always labelled as one. It is there so
     * the figures move with the rise rather than standing still until the
     * first new payslip.
     */
    fun estimateNet(previousGrossMinor: Long?, previousNetMinor: Long?, newGrossMinor: Long): Long? {
        val gross = previousGrossMinor ?: return null
        val net = previousNetMinor ?: return null
        if (gross <= 0L) return null
        return (newGrossMinor.toDouble() * net / gross).roundToLong()
    }
}
