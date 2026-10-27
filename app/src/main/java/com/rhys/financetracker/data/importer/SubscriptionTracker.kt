package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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
 * otherwise show every subscription on it as stopped. Never later than
 * today, though: a row dated in the future is a misread date, not a
 * statement from next year.
 *
 * What you told the app — when one started and how long it runs, or that
 * you cancelled it — decides when it ends; see [SubscriptionPlan].
 */
object SubscriptionTracker {

    enum class Status(val label: String) {
        ACTIVE("Still being paid"),
        MISSED("Missed a payment — may have stopped"),
        STOPPED("Stopped"),

        /** Cancelled, or its length has run out. */
        ENDED("Ended"),
        ;

        /** Still worth showing: being paid, or a payment missed that may mean it has stopped. */
        val isGoing: Boolean get() = this == ACTIVE || this == MISSED
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
        /** The first payment seen. */
        val firstDate: LocalDate = lastDate,
        /** What was paid each month, at the amounts really paid. */
        val paidByMonth: Map<YearMonth, Long> = emptyMap(),
        /** The entries that are its payments. */
        val paymentIds: Set<Long> = emptySet(),
        /** When it started, how long it runs or that it was cancelled, if you said. */
        val plan: SubscriptionPlan? = null,
        /** A direct debit or standing order, when the statement or the payee says so. */
        val paidBy: com.rhys.financetracker.domain.model.PaymentKind? = null,
        /** Each payment's amount, oldest first. */
        val amounts: List<Long> = emptyList(),
    ) {
        /** What it cost before a recent price rise, or null; see [PriceRise]. */
        val raisedFrom: Long? get() = PriceRise.from(amounts)

        /** How much more a year that rise costs, or null without one. */
        val riseYearlyMinor: Long? get() = raisedFrom?.let { (amountMinor - it) * paymentsPerYear(frequency) }

        val key: String get() = SubscriptionPlan.keyOf(name)

        /** The day it ends, if you said when. */
        val endsOn: LocalDate? get() = plan?.endsOn

        /** How many more payments before it ends; null when it runs until cancelled. */
        val paymentsLeft: Int?
            get() {
                val end = endsOn ?: return null
                var due = nextDue
                var count = 0
                while (due.isBefore(end) && count < MAX_COUNTED) {
                    count++
                    due = step(due, frequency)
                }
                return count
            }

        /** What it costs over a year at the latest amount. */
        val yearlyMinor: Long get() = amountMinor * paymentsPerYear(frequency)

        /** And per month, for adding up. */
        val monthlyMinor: Long get() = yearlyMinor / 12

        /** Netflix, Audible, Claude: a subscription, rather than a bill for a service like insurance. */
        val isSubscription: Boolean get() = SubscriptionKinds.isSubscription(name, categoryName)
    }

    /**
     * The regular payments among [payments], most expensive first within
     * each status — still being paid, then missed, then stopped.
     */
    fun track(
        payments: List<RecurringDetector.Payment>,
        plans: Map<String, SubscriptionPlan> = emptyMap(),
        today: LocalDate = LocalDate.now(),
    ): List<Subscription> {
        // The latest day each account's statements reach — never past today.
        val coveredTo = payments.groupBy { it.accountId }
            .mapValues { (_, rows) -> minOf(rows.maxOf { it.date }, today) }
        return payments
            .filterNot { it.categoryName in NOT_REGULAR }
            .filterNot { payment ->
                TransactionFingerprint.normaliseDescription(payment.description).split(' ').any { it in UNDONE }
            }
            .let(RecurringDetector::series)
            .let { mergeAsTold(it, plans) }
            .filterNot { plans[it.key]?.notMine == true }
            .mapNotNull { judge(it.key, it.payments, coveredTo) }
            .map { found ->
                val plan = plans[found.key] ?: return@map found
                found.copy(plan = plan, status = if (plan.hasEnded(today)) Status.ENDED else found.status)
            }
            .sortedWith(compareBy<Subscription> { it.status.ordinal }.thenByDescending { it.monthlyMinor })
    }

    /**
     * Ones you said are the same as another counted as that one — "Amzn
     * Prime" as "Amazon Prime" — with a payment held under both names (an
     * alert's and the bank's line for it) counted once.
     */
    internal fun mergeAsTold(
        series: List<RecurringDetector.Series>,
        plans: Map<String, SubscriptionPlan>,
    ): List<RecurringDetector.Series> {
        fun target(key: String): String {
            var current = key
            // Followed a few steps at most, so a loop of "same as" can't hang.
            repeat(MAX_SAME_AS_STEPS) { current = plans[current]?.sameAs ?: return current }
            return current
        }
        // Only what you said is the same as another moves; two policies with
        // one insurer, kept apart on purpose, stay two.
        val (told, untold) = series.partition { plans[it.key]?.sameAs != null }
        if (told.isEmpty()) return series
        val result = untold.toMutableList()
        for (part in told) {
            val key = target(part.key)
            val median = part.payments.map { it.amountMinor }.sorted().let { it[it.size / 2] }
            val into = result.indices.filter { result[it].key == key }
                .minByOrNull { index -> abs(result[index].payments.map { it.amountMinor }.sorted().let { it[it.size / 2] } - median) }
            if (into == null) {
                result += RecurringDetector.Series(key, part.payments)
            } else {
                val kept = result[into].payments.toMutableList()
                // A payment held under both names (an alert's, and the bank's line) counts once.
                part.payments.forEach { payment ->
                    val twice = kept.any { other ->
                        other.amountMinor == payment.amountMinor &&
                            abs(ChronoUnit.DAYS.between(other.date, payment.date)) <= SAME_PAYMENT_DAYS
                    }
                    if (!twice) kept += payment
                }
                result[into] = RecurringDetector.Series(key, kept.sortedBy { it.date })
            }
        }
        return result
    }

    private fun judge(key: String, group: List<RecurringDetector.Payment>, coveredTo: Map<Long, LocalDate>): Subscription? {
        val sorted = group.sortedBy { it.date }.filterNot { it.date.isAfter(coveredTo[it.accountId] ?: it.date) }
        if (sorted.map { it.date }.distinct().size < 2) return null
        val frequency = RecurringDetector.intervalOf(sorted.map { it.date }) ?: return null

        // A steady amount, give or take a price rise. Direct Debits are let
        // vary more: an energy bill moves with the weather.
        val amounts = sorted.map { it.amountMinor }.sorted()
        val median = amounts[amounts.size / 2].coerceAtLeast(1L)
        val spread = (amounts.last() - amounts.first()).toDouble() / median
        val marked = sorted.any { RecurringDetector.isMarkedRegular(it.description) }
        if (spread > (if (marked) MARKED_SPREAD else STEADY_SPREAD)) return null
        // Money to a person is only a bill when it's a standing order: the
        // bank says so, or it's the very same sum every time — rent to a
        // landlord, the childminder. Sending money now and then is not.
        if (sorted.all { it.categoryName in TO_PEOPLE } && !marked &&
            (amounts.first() != amounts.last() || sorted.size < MIN_STANDING_ORDERS)
        ) {
            return null
        }

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
            firstDate = sorted.first().date,
            paidBy = paidBy(sorted),
            paidByMonth = sorted.groupBy { YearMonth.from(it.date) }.mapValues { (_, rows) -> rows.sumOf { it.amountMinor } },
            paymentIds = sorted.map { it.id }.filter { it != 0L }.toSet(),
            amounts = sorted.map { it.amountMinor },
        )
    }

    /** "DD" or "SO" on the statement says how; a fixed sum to a person is a standing order. */
    private fun paidBy(payments: List<RecurringDetector.Payment>): com.rhys.financetracker.domain.model.PaymentKind? {
        val texts = payments.map { " " + TransactionFingerprint.normaliseDescription(it.description) + " " }
        return when {
            texts.any { text -> STANDING_ORDER_WORDS.any { it in text } } -> com.rhys.financetracker.domain.model.PaymentKind.STANDING_ORDER
            texts.any { text -> DIRECT_DEBIT_WORDS.any { it in text } } -> com.rhys.financetracker.domain.model.PaymentKind.DIRECT_DEBIT
            payments.all { it.categoryName in TO_PEOPLE } -> com.rhys.financetracker.domain.model.PaymentKind.STANDING_ORDER
            else -> null
        }
    }

    private val STANDING_ORDER_WORDS = listOf(" so ", " s o ", " sto ", " standing order ")
    private val DIRECT_DEBIT_WORDS = listOf(" dd ", " d d ", " ddr ", " direct debit ")

    /** The next payment after one on [date]. */
    private fun step(date: LocalDate, frequency: Frequency): LocalDate = when (frequency) {
        Frequency.WEEKLY -> date.plusWeeks(1)
        Frequency.FORTNIGHTLY -> date.plusWeeks(2)
        Frequency.FOUR_WEEKLY -> date.plusWeeks(4)
        Frequency.QUARTERLY -> date.plusMonths(3)
        Frequency.HALF_YEARLY -> date.plusMonths(6)
        Frequency.YEARLY -> date.plusYears(1)
        else -> date.plusMonths(1)
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

    /** Money put away or taken out as cash is regular, but not a bill or a subscription. */
    private val NOT_REGULAR = setOf("Savings", "Cash")

    /** Money to people: a bill only when it's a standing order. */
    private val TO_PEOPLE = setOf("Transfers & payments", "People & services")

    /** A fixed sum to a person this many times is a standing order. */
    private const val MIN_STANDING_ORDERS = 3

    /** How far apart one payment told twice can be dated: an alert's day and the bank's. */
    private const val SAME_PAYMENT_DAYS = 3L
    private const val MAX_SAME_AS_STEPS = 5

    /** A payment that came back or was undone is not a payment for anything. */
    private val UNDONE = setOf("returned", "refund", "refunded", "reversal", "reversed", "unpaid", "recalled")

    private const val STEADY_SPREAD = 0.35
    private const val MARKED_SPREAD = 1.0

    /** How many months of dots each row shows. */
    const val MONTHS_SHOWN = 12

    /** Enough to count a two-year contract's weekly payments. */
    private const val MAX_COUNTED = 120
}
