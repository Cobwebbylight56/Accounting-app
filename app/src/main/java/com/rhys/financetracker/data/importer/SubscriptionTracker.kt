package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * The subscriptions and other regular payments in the statements, and
 * whether each is still being paid.
 *
 * A payee seen at a steady rhythm — every month, week or year, at much the
 * same amount — is a subscription. One that turns up every month and then
 * misses one has probably been cancelled; missing two, it has stopped.
 *
 * "Missing" is judged against the latest statement read for that account,
 * not today's date: a card whose statement has not been imported yet would
 * otherwise show every subscription on it as stopped.
 */
object SubscriptionTracker {

    enum class Status(val label: String) {
        ACTIVE("Still being paid"),
        MISSED("Missed a payment — may have stopped"),
        STOPPED("Stopped"),
    }

    data class Subscription(
        val name: String,
        /** The latest amount paid. */
        val amountMinor: Long,
        val frequency: Frequency,
        val lastDate: LocalDate,
        /** When the next one would be, going by the rhythm so far. */
        val nextDue: LocalDate,
        val status: Status,
        /** Whether it was paid in each of the last [MONTHS_SHOWN] months, oldest first. */
        val monthsPaid: List<Boolean>,
        val months: List<YearMonth>,
        val accountId: Long,
        val categoryName: String?,
        val timesPaid: Int,
    ) {
        /** What it costs over a year at the latest amount. */
        val yearlyMinor: Long get() = amountMinor * paymentsPerYear(frequency)

        /** And per month, for adding up. */
        val monthlyMinor: Long get() = yearlyMinor / 12
    }

    /**
     * The regular payments among [payments], most expensive first within
     * each status — still being paid, then missed, then stopped.
     */
    fun track(payments: List<RecurringDetector.Payment>): List<Subscription> {
        // The latest day each account's statements reach.
        val coveredTo = payments.groupBy { it.accountId }.mapValues { (_, rows) -> rows.maxOf { it.date } }
        return payments
            .filterNot { it.categoryName in NOT_SUBSCRIPTIONS }
            .filterNot { payment ->
                TransactionFingerprint.normaliseDescription(payment.description).split(' ').any { it in UNDONE }
            }
            .groupBy { RecurringDetector.keyOf(it.description) }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (key, group) -> judge(key, group, coveredTo) }
            .sortedWith(compareBy<Subscription> { it.status.ordinal }.thenByDescending { it.monthlyMinor })
    }

    private fun judge(key: String, group: List<RecurringDetector.Payment>, coveredTo: Map<Long, LocalDate>): Subscription? {
        val sorted = group.sortedBy { it.date }
        if (sorted.map { it.date }.distinct().size < 2) return null
        val frequency = RecurringDetector.intervalOf(sorted.map { it.date }) ?: return null

        // A steady amount, give or take a price rise. Direct Debits are let
        // vary more: an energy bill moves with the weather.
        val amounts = sorted.map { it.amountMinor }.sorted()
        val median = amounts[amounts.size / 2].coerceAtLeast(1L)
        val spread = (amounts.last() - amounts.first()).toDouble() / median
        val marked = sorted.any { RecurringDetector.isMarkedRegular(it.description) }
        if (spread > (if (marked) MARKED_SPREAD else STEADY_SPREAD)) return null

        // About one payment per period, not a shop visited several times in it.
        val every = daysOf(frequency)
        val span = ChronoUnit.DAYS.between(sorted.first().date, sorted.last().date)
        val expected = span / every + 1
        if (sorted.size > expected * 2) return null

        val last = sorted.last()
        val reference = coveredTo[last.accountId] ?: last.date
        val overdue = ChronoUnit.DAYS.between(last.date, reference)
        val grace = maxOf(5L, every / 4)
        val status = when {
            overdue <= every + grace -> Status.ACTIVE
            overdue <= every * 2 + grace -> Status.MISSED
            else -> Status.STOPPED
        }

        val endMonth = YearMonth.from(reference)
        val months = (MONTHS_SHOWN - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }
        val paidIn = sorted.map { YearMonth.from(it.date) }.toSet()

        return Subscription(
            name = key.split(' ').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } },
            amountMinor = last.amountMinor,
            frequency = frequency,
            lastDate = last.date,
            nextDue = last.date.plusDays(every),
            status = status,
            monthsPaid = months.map { it in paidIn },
            months = months,
            accountId = last.accountId,
            categoryName = last.categoryName,
            timesPaid = sorted.size,
        )
    }

    private fun daysOf(frequency: Frequency): Long = when (frequency) {
        Frequency.WEEKLY -> 7
        Frequency.FORTNIGHTLY -> 14
        Frequency.FOUR_WEEKLY -> 28
        Frequency.QUARTERLY -> 91
        Frequency.YEARLY -> 365
        else -> 31
    }

    private fun paymentsPerYear(frequency: Frequency): Long = when (frequency) {
        Frequency.WEEKLY -> 52
        Frequency.FORTNIGHTLY -> 26
        Frequency.FOUR_WEEKLY -> 13
        Frequency.QUARTERLY -> 4
        Frequency.YEARLY -> 1
        else -> 12
    }

    /** Money put away or moved between people is regular, but not a subscription. */
    private val NOT_SUBSCRIPTIONS = setOf("Savings", "Cash", "Transfers & payments", "People & services")

    /** A payment that came back or was undone is not a payment for anything. */
    private val UNDONE = setOf("returned", "refund", "refunded", "reversal", "reversed", "unpaid", "recalled")

    private const val STEADY_SPREAD = 0.35
    private const val MARKED_SPREAD = 1.0

    /** How many months of dots each row shows. */
    const val MONTHS_SHOWN = 12
}
