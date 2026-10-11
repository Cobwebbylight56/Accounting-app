package com.rhys.financetracker.domain.report

import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.TransactionType
import java.time.YearMonth

/**
 * A month's money out, split three ways for the Spending graphs: what
 * subscriptions came to, what else went on credit cards, and the rest —
 * spending straight from the bank.
 *
 * Subscriptions are taken out first, whichever account paid them, so the
 * three never overlap and always add up to the month's money out.
 */
object SpendingSplit {

    data class Part(val cardMinor: Long = 0L, val subscriptionsMinor: Long = 0L)

    /**
     * @param subscriptionIds the entries that are subscription payments; see
     *   [com.rhys.financetracker.data.importer.SubscriptionTracker.Subscription.paymentIds].
     */
    fun byMonth(rows: List<TransactionWithDetails>, subscriptionIds: Set<Long>): Map<YearMonth, Part> =
        rows.asSequence()
            .filter { it.transaction.type == TransactionType.EXPENSE && !it.transaction.isArchived }
            // Left out of money out altogether, as the month's totals leave them out.
            .filter { it.categoryKind != CategoryKind.SAVING && it.categoryKind != CategoryKind.TRANSFER }
            .groupBy { YearMonth.from(it.transaction.date) }
            .mapValues { (_, inMonth) ->
                val (subscriptions, rest) = inMonth.partition { it.transaction.id in subscriptionIds }
                Part(
                    cardMinor = rest.filter { it.isOnCredit }.sumOf { it.transaction.amountMinor },
                    subscriptionsMinor = subscriptions.sumOf { it.transaction.amountMinor },
                )
            }

    /** [points] with each month's parts filled in. */
    fun applied(points: List<MonthPoint>, parts: Map<YearMonth, Part>): List<MonthPoint> =
        points.map { point ->
            val part = parts[point.yearMonth] ?: return@map point
            // Never more than the month's money out, which splits payments
            // across categories the same way and can leave some out.
            val subscriptions = part.subscriptionsMinor.coerceAtMost(point.expenseMinor)
            val card = part.cardMinor.coerceAtMost(point.expenseMinor - subscriptions)
            point.copy(cardMinor = card, subscriptionsMinor = subscriptions)
        }
}
