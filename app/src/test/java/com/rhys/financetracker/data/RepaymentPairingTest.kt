package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.RepaymentPairing
import com.rhys.financetracker.data.importer.RepaymentPairing.Entry
import com.rhys.financetracker.data.importer.RepaymentPairing.Step
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A card paid off from the bank, seen on both statements, counts once. */
class RepaymentPairingTest {

    private val nationwide = 1L
    private val capitalOne = 2L
    private val zopa = 3L
    private val card = RepaymentPairing.Owed(capitalOne, "Capital One", LocalDate.of(2026, 2, 26))
    private val zopaCard = RepaymentPairing.Owed(zopa, "Zopa", LocalDate.of(2026, 2, 1))

    private fun entry(
        id: Long,
        account: Long,
        type: TransactionType,
        amount: Long,
        day: Int,
        description: String,
        category: String? = null,
        byUser: Boolean = false,
        to: Long? = null,
    ) = Entry(
        id = id,
        accountId = account,
        type = type,
        transferAccountId = to,
        amountMinor = amount,
        date = LocalDate.of(2026, 3, day),
        description = description,
        source = RecordSource.STATEMENT,
        categoryName = category,
        categoryByUser = byUser,
    )

    private fun find(vararg entries: Entry, owed: List<RepaymentPairing.Owed> = listOf(card)) =
        RepaymentPairing.find(owed, entries.toList()) { _, _ -> true }

    @Test
    fun `the bank's payment and the card's thank-you are one move`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 10, "Direct Debit Payment - Thank You", "Transfers & payments"),
            entry(20, nationwide, TransactionType.EXPENSE, 3_999, 8, "CAPITAL ONE", "Credit & loans"),
            entry(21, nationwide, TransactionType.EXPENSE, 3_999, 9, "TESCO STORES"),
        )
        assertEquals(listOf(Step.Paired(incomeId = 10, expenseId = 20, owedId = capitalOne)), steps)
    }

    @Test
    fun `money in already held as a move into the card is put away`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 10, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.TRANSFER, 3_999, 9, "CAPITAL ONE", to = capitalOne),
        )
        assertEquals(listOf(Step.AlreadyMoved(incomeId = 10, transferId = 20)), steps)
    }

    @Test
    fun `a refund on the card is left alone`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 10, "REFUND AMAZON", "Refunds"),
            entry(20, nationwide, TransactionType.EXPENSE, 3_999, 8, "CAPITAL ONE", "Credit & loans"),
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `a payment a week away, or a different amount, is not the same one`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 20, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.EXPENSE, 3_999, 8, "CAPITAL ONE"),
            entry(21, nationwide, TransactionType.EXPENSE, 4_000, 20, "CAPITAL ONE"),
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `the payment naming the card wins over one only filed as credit`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 2_500, 10, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.EXPENSE, 2_500, 9, "ZOPA CARD", "Credit & loans"),
            entry(21, nationwide, TransactionType.EXPENSE, 2_500, 9, "CAPITAL ONE", "Credit & loans"),
            owed = listOf(card, zopaCard),
        )
        assertEquals(listOf(Step.Paired(incomeId = 10, expenseId = 21, owedId = capitalOne)), steps)
    }

    @Test
    fun `two payments that could each be it - neither is touched`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 2_500, 10, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.EXPENSE, 2_500, 9, "CAPITAL ONE"),
            entry(21, 4L, TransactionType.EXPENSE, 2_500, 9, "CAPITAL ONE"),
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `what the user filed as something else is theirs`() {
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 10, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.EXPENSE, 3_999, 8, "CAPITAL ONE", "Gifts", byUser = true),
        )
        assertTrue(steps.isEmpty())
    }

    @Test
    fun `a move from before the card's balance day is not paired with money in after it`() {
        // The card's balance was given on 9 March, so a move on the 8th is
        // inside that figure while money in on the 10th is not.
        val early = RepaymentPairing.Owed(capitalOne, "Capital One", LocalDate.of(2026, 3, 9))
        val steps = find(
            entry(10, capitalOne, TransactionType.INCOME, 3_999, 10, "Direct Debit Payment - Thank You"),
            entry(20, nationwide, TransactionType.EXPENSE, 3_999, 8, "CAPITAL ONE"),
            owed = listOf(early),
        )
        assertTrue(steps.isEmpty())
    }
}
