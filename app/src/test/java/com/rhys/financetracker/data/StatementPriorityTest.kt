package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.StatementPriority
import com.rhys.financetracker.data.local.projection.ExistingEntry
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The same payment written down twice — once from memory, once by the bank —
 * has to end up as one entry, and the bank's version has to be the one that
 * survives. These cover both that working and, just as importantly, it
 * declining to act when the pairing is a guess.
 */
class StatementPriorityTest {

    private fun march(day: Int) = LocalDate.of(2026, 3, day)

    private fun statementRow(
        id: Int,
        payee: String,
        amountMinor: Long,
        day: Int,
        type: TransactionType = TransactionType.EXPENSE,
        month: Int = 3,
        alreadyPresent: Boolean = false,
    ) = ImportCandidate(
        sourceRow = id,
        sourceColumn = 0,
        name = payee,
        amountMinor = amountMinor,
        target = ImportTarget.TRANSACTION,
        personName = null,
        accountName = "Main account",
        categoryName = null,
        notes = null,
        dayOfMonth = null,
        dateIso = LocalDate.of(2026, month, day).toString(),
        frequencyName = "MONTHLY",
        transactionType = type,
        source = RecordSource.STATEMENT,
        isAlreadyPresent = alreadyPresent,
    )

    private fun recorded(
        id: Long,
        description: String,
        amountMinor: Long,
        day: Int,
        type: TransactionType = TransactionType.EXPENSE,
        month: Int = 3,
        source: RecordSource = RecordSource.SPREADSHEET,
    ) = ExistingEntry(
        id = id,
        date = LocalDate.of(2026, month, day),
        amountMinor = amountMinor,
        type = type,
        description = description,
        categoryId = null,
        notes = null,
        source = source,
    )

    @Test
    fun `the bank's version of a payment already noted down is matched to it`() {
        // The case this exists for: a spreadsheet says "Virgin media" on the
        // day it was written down, the statement says what the bank calls it
        // on the day the money actually left.
        val corrections = StatementPriority.corrections(
            candidates = listOf(statementRow(1, "VIRGIN MEDIA PAYMENTS 998812", 4650, 3)),
            existing = listOf(recorded(10, "Virgin media", 4650, 1)),
        )
        assertEquals(1, corrections.size)
        val correction = corrections.values.single()
        assertEquals(10L, correction.existing.id)
        assertTrue(correction.payeesAgreed)
    }

    @Test
    fun `agreeing payees allow a wider gap, but never a month`() {
        // A bill noted at the start of the month and taken over a week later
        // is one payment.
        assertEquals(
            1,
            StatementPriority.corrections(
                listOf(statementRow(1, "VIRGIN MEDIA PAYMENTS", 4650, 12)),
                listOf(recorded(10, "Virgin media", 4650, 3)),
            ).size,
        )
        // But this month's bill must never claim last month's entry, or every
        // month's import quietly rewrites the month before.
        assertTrue(
            StatementPriority.corrections(
                listOf(statementRow(1, "VIRGIN MEDIA PAYMENTS", 4650, 31)),
                listOf(recorded(10, "Virgin media", 4650, 1)),
            ).isEmpty(),
        )
    }

    @Test
    fun `an entry with nothing else it could be is matched on the amount alone`() {
        // "Petrol £20" and "SHELL FILLING STN £20" share no words at all, but
        // there is one of each and they are two days apart.
        val corrections = StatementPriority.corrections(
            listOf(statementRow(1, "SHELL FILLING STN", 2000, 3)),
            listOf(recorded(10, "Petrol", 2000, 1)),
        )
        assertEquals(1, corrections.size)
        assertTrue(!corrections.values.single().payeesAgreed)
    }

    @Test
    fun `two payments of the same amount are left alone rather than guessed at`() {
        // Which of these is the £20 that was written down as "Petrol"? Nothing
        // says, so nothing is merged. The worst case is a visible duplicate,
        // where guessing would attach the note to the wrong payment silently.
        val corrections = StatementPriority.corrections(
            listOf(
                statementRow(1, "SHELL FILLING STN", 2000, 1),
                statementRow(2, "TESCO STORES 3294", 2000, 2),
            ),
            listOf(recorded(10, "Petrol", 2000, 1)),
        )
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun `naming the payee settles which of two it was`() {
        val corrections = StatementPriority.corrections(
            listOf(
                statementRow(1, "SHELL FILLING STN", 2000, 1),
                statementRow(2, "TESCO STORES 3294", 2000, 2),
            ),
            listOf(recorded(10, "Shell petrol", 2000, 1)),
        )
        assertEquals(setOf("1:0"), corrections.keys)
        assertEquals(10L, corrections.values.single().existing.id)
    }

    @Test
    fun `what the bank already said is never overwritten`() {
        // Otherwise two statements covering the same week would take turns
        // rewriting each other's rows.
        assertTrue(
            StatementPriority.corrections(
                listOf(statementRow(1, "VIRGIN MEDIA", 4650, 3)),
                listOf(recorded(10, "Virgin media", 4650, 1, source = RecordSource.STATEMENT)),
            ).isEmpty(),
        )
    }

    @Test
    fun `money in is never matched to money out`() {
        // £46.50 paid and £46.50 received on the same day are opposites, not
        // two accounts of one event.
        assertTrue(
            StatementPriority.corrections(
                listOf(statementRow(1, "ACME LTD SALARY", 4650, 3, TransactionType.INCOME)),
                listOf(recorded(10, "Acme salary", 4650, 1)),
            ).isEmpty(),
        )
    }

    @Test
    fun `a row the fingerprint already recognised is left to it`() {
        // It is the same file imported again, not a second account of the same
        // payment, and it is already handled.
        assertTrue(
            StatementPriority.corrections(
                listOf(statementRow(1, "Virgin media", 4650, 1, alreadyPresent = true)),
                listOf(recorded(10, "Virgin media", 4650, 1)),
            ).isEmpty(),
        )
    }

    @Test
    fun `two months of one bill pair up in order rather than crosswise`() {
        val corrections = StatementPriority.corrections(
            listOf(
                statementRow(1, "VIRGIN MEDIA PAYMENTS", 4650, 3),
                statementRow(2, "VIRGIN MEDIA PAYMENTS", 4650, 3, month = 4),
            ),
            listOf(
                recorded(10, "Virgin media", 4650, 1),
                recorded(11, "Virgin media", 4650, 1, month = 4),
            ),
        )
        assertEquals(2, corrections.size)
        assertEquals(10L, corrections["1:0"]?.existing?.id)
        assertEquals(11L, corrections["2:0"]?.existing?.id)
    }

    @Test
    fun `words that appear on every statement do not count as agreement`() {
        // Half a statement says "payment" somewhere. Agreeing on that would
        // pair unrelated entries and then let them merge on a wide date gap.
        assertTrue(!StatementPriority.payeesAgree("DIRECT DEBIT PAYMENT", "MONTHLY PAYMENT CARD"))
        assertTrue(StatementPriority.payeesAgree("TESCO STORES 3294", "Tesco"))
        // A card-machine number is as distinctive as a name.
        assertTrue(StatementPriority.payeesAgree("SAINSBURYS 4471", "Shop 4471"))
    }

    @Test
    fun `nothing is matched when the ledger holds nothing to match`() {
        assertTrue(
            StatementPriority.corrections(
                listOf(statementRow(1, "TESCO STORES", 500, 3)),
                emptyList(),
            ).isEmpty(),
        )
        assertNull(StatementPriority.corrections(emptyList(), listOf(recorded(1, "x", 500, 3)))["1:0"])
    }
}
