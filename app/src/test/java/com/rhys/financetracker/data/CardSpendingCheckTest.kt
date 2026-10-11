package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.CardSpendingCheck
import com.rhys.financetracker.data.importer.RepaymentPairing.Entry
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.model.TransactionType.EXPENSE
import com.rhys.financetracker.domain.model.TransactionType.INCOME
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spending on a card that an older version read as money in. */
class CardSpendingCheckTest {

    private val zopa = 2L
    private val nationwide = 1L

    private fun entry(id: Long, type: TransactionType, month: Int, day: Int, description: String, amount: Long = 899, account: Long = zopa, category: String? = null) =
        Entry(
            id = id,
            accountId = account,
            type = type,
            transferAccountId = null,
            amountMinor = amount,
            date = LocalDate.of(2026, month, day),
            description = description,
            source = RecordSource.STATEMENT,
            categoryName = category,
            categoryByUser = false,
        )

    private val audible = (1..9).map { month ->
        // May and June were read the wrong way round.
        entry(month.toLong(), if (month in 5..6) INCOME else EXPENSE, month, 2, "Audible UK", category = if (month in 5..6) null else "Subscriptions")
    }

    @Test
    fun `the months read as money in are spending again, filed like the rest`() {
        val found = CardSpendingCheck.find(audible, setOf(zopa))
        assertEquals(listOf(5L, 6L), found.map { it.id })
        assertEquals(9L, found.first().likeId)
    }

    @Test
    fun `a refund beside the payment it refunds is left alone`() {
        val rows = audible.filter { it.type == EXPENSE } + entry(50, INCOME, 3, 6, "Audible UK")
        assertTrue(CardSpendingCheck.find(rows, setOf(zopa)).isEmpty())
    }

    @Test
    fun `money in on a current account is not a card's`() {
        val rows = audible.map { it.copy(accountId = nationwide) }
        assertTrue(CardSpendingCheck.find(rows, setOf(zopa)).isEmpty())
    }

    @Test
    fun `paying the card, refunds and money on another day are left alone`() {
        val rows = audible.filter { it.type == EXPENSE } +
            entry(60, INCOME, 5, 2, "Direct Debit Payment - Thank You") +
            entry(61, INCOME, 6, 2, "Audible UK", category = "Refunds") +
            entry(62, INCOME, 6, 18, "Audible UK")
        assertTrue(CardSpendingCheck.find(rows, setOf(zopa)).isEmpty())
    }
}
