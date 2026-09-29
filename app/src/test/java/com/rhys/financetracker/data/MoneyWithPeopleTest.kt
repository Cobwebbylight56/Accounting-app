package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.PayeeEntry
import com.rhys.financetracker.data.repository.PayeeRepository
import com.rhys.financetracker.data.repository.TidyUpRepository
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyWithPeopleTest {

    private val day = LocalDate.of(2026, 9, 10)

    private fun entry(id: Long, description: String, pence: Long, category: String? = null) =
        PayeeEntry(id = id, description = description, amountMinor = pence, date = day, categoryName = category)

    @Test
    fun `money sent and money back from the same person share one row`() {
        val ledgers = PayeeRepository.ledgers(
            sent = listOf(
                entry(1, "FASTER PAYMENT TO J SMITH REF 1", 5_000),
                entry(2, "Payment to JOHN SMITH", 2_000),
            ),
            received = listOf(entry(3, "BANK CREDIT SMITH J", 3_000)),
        )
        assertEquals(1, ledgers.size)
        val smith = ledgers.single()
        assertEquals(7_000L, smith.sentMinor)
        assertEquals(3_000L, smith.receivedMinor)
        assertEquals(-4_000L, smith.netMinor)
        assertEquals("John Smith", smith.name)
    }

    @Test
    fun `money in that is not from a person is left out`() {
        val ledgers = PayeeRepository.ledgers(
            sent = emptyList(),
            received = listOf(
                entry(1, "ACME LTD SALARY", 200_000),
                entry(2, "FASTER PAYMENT FROM HANNAH EVANS", 1_500),
            ),
        )
        assertEquals(listOf("Hannah Evans"), ledgers.map { it.name })
        assertEquals(0L, ledgers.single().sentMinor)
    }

    @Test
    fun `money out becomes a move to the other account`() {
        val row = TransactionEntity(
            id = 9, amountMinor = 10_000, type = TransactionType.EXPENSE, date = day,
            description = "TRANSFER TO START TO SAVE", accountId = 1, categoryId = 4,
        )
        val moved = TidyUpRepository.asTransfer(row, otherId = 2, now = 5)
        assertEquals(TransactionType.TRANSFER, moved.type)
        assertEquals(1L, moved.accountId)
        assertEquals(2L, moved.transferAccountId)
        assertNull(moved.categoryId)
        assertEquals(10_000L, moved.amountMinor)
    }

    @Test
    fun `money in is turned round to come from the other account`() {
        val row = TransactionEntity(
            id = 9, amountMinor = 10_000, type = TransactionType.INCOME, date = day,
            description = "FROM RHYS CURRENT", accountId = 2,
        )
        val moved = TidyUpRepository.asTransfer(row, otherId = 1, now = 5)
        assertEquals(TransactionType.TRANSFER, moved.type)
        assertEquals(1L, moved.accountId)
        assertEquals(2L, moved.transferAccountId)
        assertTrue(moved.id == row.id)
    }
}
