package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.MonthPoint
import com.rhys.financetracker.domain.report.SpendingSplit
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

/** A month's money out, split into the bank, credit cards and subscriptions. */
class SpendingSplitTest {

    private val september = YearMonth.of(2026, 9)

    private fun row(
        id: Long,
        amount: Long,
        holding: Holding,
        type: TransactionType = TransactionType.EXPENSE,
        kind: CategoryKind? = CategoryKind.EXPENSE,
    ) = TransactionWithDetails(
        transaction = TransactionEntity(
            id = id,
            amountMinor = amount,
            type = type,
            date = LocalDate.of(2026, 9, 10),
            description = "Payment $id",
            accountId = if (holding == Holding.OWED) 2L else 1L,
        ),
        accountName = null,
        transferAccountName = null,
        categoryName = null,
        categoryColor = null,
        personName = null,
        personColor = null,
        categoryKind = kind,
        accountHolding = holding,
    )

    private val rows = listOf(
        row(1, 5_000, Holding.SPEND),
        // Audible on the card: a subscription, not counted again as card spending.
        row(2, 899, Holding.OWED),
        row(3, 2_400, Holding.OWED),
        // Spotify from the bank.
        row(4, 1_199, Holding.SPEND),
        // Moved to savings and money to a person: not money out at all.
        row(5, 10_000, Holding.SPEND, kind = CategoryKind.SAVING),
        row(6, 2_000, Holding.SPEND, kind = CategoryKind.TRANSFER),
        // Paying the card off is not spending.
        row(7, 3_999, Holding.OWED, type = TransactionType.INCOME),
    )

    @Test
    fun `subscriptions first, then the cards, the rest from the bank`() {
        val part = SpendingSplit.byMonth(rows, subscriptionIds = setOf(2L, 4L)).getValue(september)
        assertEquals(2_400L, part.cardMinor)
        assertEquals(899L + 1_199L, part.subscriptionsMinor)
        val point = SpendingSplit.applied(listOf(MonthPoint(september, 0L, 9_498L)), mapOf(september to part)).single()
        assertEquals(5_000L, point.bankMinor)
        assertEquals(point.expenseMinor, point.bankMinor + point.cardMinor + point.subscriptionsMinor)
    }

    @Test
    fun `never more than the month's money out`() {
        val part = SpendingSplit.Part(cardMinor = 9_000L, subscriptionsMinor = 2_000L)
        val point = SpendingSplit.applied(listOf(MonthPoint(september, 0L, 5_000L)), mapOf(september to part)).single()
        assertEquals(2_000L, point.subscriptionsMinor)
        assertEquals(3_000L, point.cardMinor)
        assertEquals(0L, point.bankMinor)
    }
}
