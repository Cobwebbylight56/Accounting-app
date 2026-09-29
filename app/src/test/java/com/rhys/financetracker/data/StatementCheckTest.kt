package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.StatementCheck
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proving the whole statement was read, from its own running balance. */
class StatementCheckTest {

    private var row = 0

    private fun line(day: Int, amount: Long, isIn: Boolean, balance: Long?) = ImportCandidate(
        sourceRow = row++,
        name = "Row $row",
        amountMinor = amount,
        target = ImportTarget.TRANSACTION,
        personName = null,
        accountName = null,
        categoryName = null,
        notes = null,
        dayOfMonth = null,
        dateIso = LocalDate.of(2026, 9, day).toString(),
        frequencyName = "MONTHLY",
        transactionType = if (isIn) TransactionType.INCOME else TransactionType.EXPENSE,
        balanceMinor = balance,
    )

    @Test
    fun `every row accounting for the balance proves nothing is missing`() {
        val check = StatementCheck.of(
            listOf(
                line(1, 20_000L, isIn = false, balance = 80_000L),
                line(2, 4_500L, isIn = false, balance = null),
                line(2, 1_500L, isIn = false, balance = 74_000L),
                line(28, 186_223L, isIn = true, balance = 260_223L),
            ),
        )
        assertTrue(check.isProvenComplete)
        assertEquals(100_000L, check.startBalanceMinor)
        assertEquals(260_223L, check.endBalanceMinor)
        assertEquals(4, check.rows)
        assertEquals(186_223L, check.moneyInMinor)
        assertEquals(26_000L, check.moneyOutMinor)
    }

    @Test
    fun `a missing row shows where and how much`() {
        val check = StatementCheck.of(
            listOf(
                line(1, 20_000L, isIn = false, balance = 80_000L),
                // A £45 payment on the 3rd was never read.
                line(5, 1_500L, isIn = false, balance = 74_000L),
            ),
        )
        assertTrue(!check.isProvenComplete)
        assertEquals(1, check.gaps.size)
        assertEquals(-4_500L, check.gaps.single().unaccountedMinor)
        assertEquals(LocalDate.of(2026, 9, 1), check.gaps.single().after)
    }

    @Test
    fun `a newest-first file is read forwards`() {
        val check = StatementCheck.of(
            listOf(
                line(5, 1_500L, isIn = false, balance = 78_500L),
                line(1, 20_000L, isIn = false, balance = 80_000L),
            ),
        )
        assertTrue(check.isProvenComplete)
        assertEquals(78_500L, check.endBalanceMinor)
    }

    @Test
    fun `no balances means it cannot be checked, not that it failed`() {
        val check = StatementCheck.of(listOf(line(1, 500L, isIn = false, balance = null)))
        assertTrue(!check.canBeChecked)
        assertTrue(!check.isProvenComplete)
        assertEquals(null, check.endBalanceMinor)
    }
}
