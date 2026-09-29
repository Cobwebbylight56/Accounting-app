package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.RecurringDetector
import com.rhys.financetracker.data.importer.RecurringDetector.Payment
import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finding the bills in a statement. */
class RecurringDetectorTest {

    private fun pay(text: String, amount: Long, month: Int, day: Int, category: String? = null) =
        Payment(text, amount, LocalDate.of(2026, month, day), category, accountId = 1L)

    @Test
    fun `direct debits are bills from a single payment`() {
        val found = RecurringDetector.find(
            listOf(
                pay("UTILITY WAREHOUSE DD 123456", 8_512L, 9, 3),
                pay("SAMSUNG FINANCE 88321 DD", 4_200L, 9, 12),
                pay("DVLA-AB12CDE DD", 1_625L, 9, 1),
            ),
            known = emptyList(),
        )
        assertEquals(
            setOf("Utility Warehouse", "Samsung Finance", "Dvla"),
            found.map { it.name }.toSet(),
        )
        assertTrue(found.all { it.frequency == Frequency.MONTHLY })
    }

    @Test
    fun `a payee paid each month at a steady amount is a bill`() {
        val found = RecurringDetector.find(
            listOf(
                pay("NETFLIX.COM", 1_099L, 7, 14),
                pay("NETFLIX.COM", 1_099L, 8, 14),
                pay("NETFLIX.COM", 1_099L, 9, 14),
            ),
            known = emptyList(),
        ).single()
        assertEquals(Frequency.MONTHLY, found.frequency)
        assertEquals(3, found.occurrences)
        assertEquals(LocalDate.of(2026, 10, 14), found.nextDue(LocalDate.of(2026, 9, 29)))
    }

    @Test
    fun `weekly shopping is not a bill`() {
        val found = RecurringDetector.find(
            listOf(
                pay("TESCO STORES 2231", 4_512L, 9, 2, "Groceries"),
                pay("TESCO STORES 2231", 8_930L, 9, 9, "Groceries"),
                pay("TESCO STORES 2231", 2_150L, 9, 16, "Groceries"),
            ),
            known = emptyList(),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a bill already set up is not suggested again`() {
        val found = RecurringDetector.find(
            listOf(pay("UTILITY WAREHOUSE DD 123456", 8_512L, 9, 3)),
            known = listOf(RecurringDetector.KnownBill("Utility Warehouse", 8_000L, 1L)),
        )
        assertTrue(found.isEmpty())
    }
}
