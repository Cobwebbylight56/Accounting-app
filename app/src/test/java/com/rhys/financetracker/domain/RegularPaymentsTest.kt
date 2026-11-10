package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.RegularPayments
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/** "One-off" is only offered beside a payment that isn't a regular bill. */
class RegularPaymentsTest {

    private fun row(id: Long, description: String, date: String, rule: Long? = null) = TransactionWithDetails(
        transaction = TransactionEntity(
            id = id,
            accountId = 1,
            type = TransactionType.EXPENSE,
            amountMinor = 18_000,
            date = LocalDate.parse(date),
            description = description,
            recurringRuleId = rule,
        ),
        accountName = null,
        transferAccountName = null,
        categoryName = null,
        categoryColor = null,
        personName = null,
        personColor = null,
    )

    @Test
    fun `a bill paid in two months, by direct debit, or set up as regular is regular`() {
        val rows = listOf(
            row(1, "UTILITY WAREHOUSE", "2026-08-02"),
            row(2, "UTILITY WAREHOUSE", "2026-09-02"),
            row(3, "TV LICENCE MBP DD", "2026-09-10"),
            row(4, "Gym", "2026-09-12", rule = 7),
            row(5, "ARNOLD CLARK CARS", "2026-09-20"),
        )
        assertEquals(setOf(1L, 2L, 3L, 4L), RegularPayments.among(rows))
    }

    @Test
    fun `two buys at one shop in the same month are not a bill`() {
        val rows = listOf(row(1, "CURRYS", "2026-09-01"), row(2, "CURRYS", "2026-09-15"))
        assertEquals(emptySet<Long>(), RegularPayments.among(rows))
    }
}
