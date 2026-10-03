package com.rhys.financetracker.domain.recurrence

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate

/**
 * Turns one payment into a regular one: "this car loan goes out on the 1st
 * every month". The payment it was made from is that period's, so the
 * schedule carries on from the next one and never writes it in twice.
 */
object RegularSchedule {

    /** The frequencies offered when making a payment regular. */
    val CHOICES = listOf(Frequency.WEEKLY, Frequency.FOUR_WEEKLY, Frequency.MONTHLY, Frequency.YEARLY)

    /** True when the day of the month is chosen, rather than the weekday kept. */
    fun usesDayOfMonth(frequency: Frequency): Boolean = when (frequency) {
        Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.HALF_YEARLY, Frequency.YEARLY,
        Frequency.CUSTOM_MONTHS,
        -> true
        else -> false
    }

    /**
     * How far back a new schedule fills in. A month and a bit covers a
     * payment that fell due since the last statement; anything older is
     * already on a statement, or never will be.
     */
    const val BACKFILL_DAYS = 35L

    /**
     * Where the schedule starts, and the first date the app adds by itself.
     *
     * @param paid the date of the payment it is made from.
     * @param day the day of the month it goes out on; ignored for weekly ones.
     * @param doneUpTo the last date already covered, when changing a schedule
     *   that has added payments since.
     */
    fun plan(
        rule: RecurringRuleEntity,
        paid: LocalDate,
        day: Int,
        today: LocalDate,
        doneUpTo: LocalDate? = null,
    ): RecurringRuleEntity {
        val start = if (usesDayOfMonth(rule.frequency)) {
            val inMonth = paid.withDayOfMonth(day.coerceIn(1, paid.lengthOfMonth()))
            if (inMonth == paid) {
                paid
            } else {
                // Paid on another day this time: that was this period's, so
                // the chosen day starts with the next one.
                val months = monthsBetweenPayments(rule.frequency, rule.interval)
                val next = paid.withDayOfMonth(1).plusMonths(months)
                next.withDayOfMonth(day.coerceIn(1, next.lengthOfMonth()))
            }
        } else {
            paid
        }
        val withStart = rule.copy(startDate = start, nextDueDate = start)
        val after = listOfNotNull(paid, doneUpTo, today.minusDays(BACKFILL_DAYS)).max()
        val next = if (start.isAfter(after)) {
            start
        } else {
            RecurrenceCalculator.nextOccurrenceAfter(withStart, after) ?: start
        }
        return withStart.copy(nextDueDate = next)
    }

    private fun monthsBetweenPayments(frequency: Frequency, interval: Int): Long = when (frequency) {
        Frequency.QUARTERLY -> 3L
        Frequency.HALF_YEARLY -> 6L
        Frequency.YEARLY -> 12L
        Frequency.CUSTOM_MONTHS -> interval.coerceAtLeast(1).toLong()
        else -> 1L
    }

    /**
     * A first guess at what kind of payment this is, from what the app knows:
     * money into a saver is moving to savings, a payment to a loan or card
     * is usually a direct debit, and the bank's own "DD" or "SO" settles it.
     */
    fun guessKind(
        type: TransactionType,
        description: String,
        toHolding: Holding?,
        isSubscription: Boolean,
    ): PaymentKind {
        if (type == TransactionType.INCOME) return PaymentKind.MONEY_IN
        val words = description.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        val text = description.lowercase()
        if ("dd" in words || "ddr" in words || "direct debit" in text) return PaymentKind.DIRECT_DEBIT
        if ("so" in words || "sto" in words || "standing order" in text) return PaymentKind.STANDING_ORDER
        if (type == TransactionType.TRANSFER) {
            return when (toHolding) {
                Holding.SET_ASIDE -> PaymentKind.TO_SAVINGS
                Holding.OWED -> PaymentKind.DIRECT_DEBIT
                else -> PaymentKind.STANDING_ORDER
            }
        }
        return if (isSubscription) PaymentKind.CARD else PaymentKind.DIRECT_DEBIT
    }

    /** "1st", "2nd", "23rd". */
    fun ordinal(day: Int): String {
        val suffix = if (day in 11..13) {
            "th"
        } else {
            when (day % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$day$suffix"
    }

    private fun weekday(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)

    /** "on the 1st of each month", "every 4 weeks", "every year on 3 Mar". */
    fun describe(frequency: Frequency, start: LocalDate): String = when (frequency) {
        Frequency.WEEKLY -> "every ${weekday(start)}"
        Frequency.FORTNIGHTLY -> "every other ${weekday(start)}"
        Frequency.FOUR_WEEKLY -> "every 4 weeks, on a ${weekday(start)}"
        Frequency.MONTHLY -> "on the ${ordinal(start.dayOfMonth)} of each month"
        Frequency.QUARTERLY -> "every 3 months, on the ${ordinal(start.dayOfMonth)}"
        Frequency.HALF_YEARLY -> "every 6 months, on the ${ordinal(start.dayOfMonth)}"
        Frequency.YEARLY -> "every year on the ${ordinal(start.dayOfMonth)} of ${
            start.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
        }"
        else -> frequency.displayName.lowercase()
    }
}
