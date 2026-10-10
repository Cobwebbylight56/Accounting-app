package com.rhys.financetracker.domain

import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.UntilPayday
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UntilPaydayTest {

    private val today = LocalDate.of(2026, 10, 10)
    private val current = 1L
    private val saver = 2L
    private val carLoan = 3L

    private fun rule(name: String, pence: Long, day: Int, type: TransactionType, to: Long? = null, notes: String? = null) =
        RecurringRuleEntity(
            name = name,
            amountMinor = pence,
            type = type,
            frequency = Frequency.MONTHLY,
            startDate = LocalDate.of(2026, 9, day),
            nextDueDate = LocalDate.of(2026, 10, day).let { if (it.isBefore(today)) it.plusMonths(1) else it },
            accountId = current,
            transferAccountId = to,
            notes = notes,
        )

    private val rules = listOf(
        rule("Rhys's wage", 186_216, 28, TransactionType.INCOME, notes = SpreadsheetImporter.WAGE_MARKER),
        rule("Sky", 3_500, 12, TransactionType.EXPENSE),
        rule("Start Save", 5_000, 15, TransactionType.TRANSFER, to = saver),
        // After pay day: not this pay's to cover.
        rule("Council tax", 15_000, 30, TransactionType.EXPENSE),
        // Already gone this month.
        rule("Car loan", 22_201, 1, TransactionType.TRANSFER, to = carLoan),
    )

    @Test
    fun `what's in the account, less what goes out before payday`() {
        val left = UntilPayday.of(rules, setOf(current), availableMinor = 64_000, today = today)
        assertEquals(LocalDate.of(2026, 10, 28), left.payday)
        assertEquals(LocalDate.of(2026, 10, 27), left.until)
        // Sky and the savings standing order.
        assertEquals(8_500L, left.billsMinor)
        assertEquals(55_500L, left.leftMinor)
        assertEquals(186_216L, left.wageDueMinor)
        assertEquals(LocalDate.of(2026, 10, 28), left.wageDueDate)
    }

    @Test
    fun `without a wage it runs to the end of the month`() {
        val left = UntilPayday.of(rules.drop(1), setOf(current), availableMinor = 64_000, today = today)
        assertNull(left.payday)
        assertEquals(LocalDate.of(2026, 10, 31), left.until)
        assertEquals(23_500L, left.billsMinor)
        assertNull(left.wageDueDate)
    }

    @Test
    fun `moving money between two spending accounts goes nowhere`() {
        val between = listOf(rule("To joint", 30_000, 20, TransactionType.TRANSFER, to = 9L))
        assertEquals(0L, UntilPayday.of(between, setOf(current, 9L), 64_000, today).billsMinor)
    }

    @Test
    fun `left over a month, on average`() {
        // Three months: £5,586.48 in, £3,000 out, £1,212 put aside, £666.03 to the car loan.
        val average = com.rhys.financetracker.domain.report.AverageLeftOver.of(558_648, 300_000, 121_200, 66_603, months = 3)!!
        assertEquals(23_615L, average.perMonthMinor)
        assertEquals(3, average.months)
        assertNull(com.rhys.financetracker.domain.report.AverageLeftOver.of(0, 0, 0, 0, months = 0))
    }
}
