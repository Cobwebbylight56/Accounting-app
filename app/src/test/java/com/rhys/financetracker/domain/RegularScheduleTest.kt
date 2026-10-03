package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RegularSchedule
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RegularScheduleTest {

    private fun rule(frequency: Frequency = Frequency.MONTHLY) = RecurringRuleEntity(
        name = "car loan",
        amountMinor = 22_201,
        type = TransactionType.TRANSFER,
        frequency = frequency,
        startDate = LocalDate.of(2026, 10, 1),
        nextDueDate = LocalDate.of(2026, 10, 1),
        accountId = 1,
        transferAccountId = 2,
    )

    @Test
    fun `paid on its day - the next one is next month, this one is not added again`() {
        val planned = RegularSchedule.plan(rule(), paid = LocalDate.of(2026, 10, 1), day = 1, today = LocalDate.of(2026, 10, 3))
        assertEquals(LocalDate.of(2026, 10, 1), planned.startDate)
        assertEquals(LocalDate.of(2026, 11, 1), planned.nextDueDate)
    }

    @Test
    fun `paid on another day - the chosen day starts next month`() {
        val planned = RegularSchedule.plan(rule(), paid = LocalDate.of(2026, 10, 2), day = 28, today = LocalDate.of(2026, 10, 3))
        assertEquals(LocalDate.of(2026, 11, 28), planned.startDate)
        assertEquals(LocalDate.of(2026, 11, 28), planned.nextDueDate)
    }

    @Test
    fun `an old payment does not fill in months the statements already hold`() {
        val planned = RegularSchedule.plan(rule(), paid = LocalDate.of(2026, 3, 1), day = 1, today = LocalDate.of(2026, 10, 3))
        // Only the last month or so: March to August are left alone. 1 Sep
        // is filled in, or tied to its statement line if that is already in.
        assertEquals(LocalDate.of(2026, 9, 1), planned.nextDueDate)
    }

    @Test
    fun `a missed one in the last month is filled in`() {
        val planned = RegularSchedule.plan(rule(), paid = LocalDate.of(2026, 9, 1), day = 1, today = LocalDate.of(2026, 10, 3))
        assertEquals(LocalDate.of(2026, 10, 1), planned.nextDueDate)
    }

    @Test
    fun `changing a schedule never goes back over what it added`() {
        val planned = RegularSchedule.plan(
            rule(),
            paid = LocalDate.of(2026, 9, 1),
            day = 1,
            today = LocalDate.of(2026, 10, 3),
            doneUpTo = LocalDate.of(2026, 10, 1),
        )
        assertEquals(LocalDate.of(2026, 11, 1), planned.nextDueDate)
    }

    @Test
    fun `weekly keeps the weekday`() {
        val planned = RegularSchedule.plan(rule(Frequency.WEEKLY), paid = LocalDate.of(2026, 10, 2), day = 15, today = LocalDate.of(2026, 10, 3))
        assertEquals(LocalDate.of(2026, 10, 9), planned.nextDueDate)
    }

    @Test
    fun `the 31st falls on the last day of a short month`() {
        val planned = RegularSchedule.plan(rule(), paid = LocalDate.of(2026, 10, 31), day = 31, today = LocalDate.of(2026, 10, 31))
        assertEquals(LocalDate.of(2026, 11, 30), planned.nextDueDate)
    }

    @Test
    fun `guesses the kind from what it knows`() {
        assertEquals(PaymentKind.TO_SAVINGS, RegularSchedule.guessKind(TransactionType.TRANSFER, "Transfer Start Save", Holding.SET_ASIDE, false))
        assertEquals(PaymentKind.DIRECT_DEBIT, RegularSchedule.guessKind(TransactionType.TRANSFER, "car loan", Holding.OWED, false))
        assertEquals(PaymentKind.STANDING_ORDER, RegularSchedule.guessKind(TransactionType.EXPENSE, "H EVANS SO", null, false))
        assertEquals(PaymentKind.DIRECT_DEBIT, RegularSchedule.guessKind(TransactionType.EXPENSE, "SKY DIGITAL DD", null, false))
        assertEquals(PaymentKind.CARD, RegularSchedule.guessKind(TransactionType.EXPENSE, "Audible Adbl Co", null, true))
        assertEquals(PaymentKind.MONEY_IN, RegularSchedule.guessKind(TransactionType.INCOME, "Wage", null, false))
    }

    @Test
    fun `describes it plainly`() {
        assertEquals("on the 1st of each month", RegularSchedule.describe(Frequency.MONTHLY, LocalDate.of(2026, 10, 1)))
        assertEquals("on the 22nd of each month", RegularSchedule.describe(Frequency.MONTHLY, LocalDate.of(2026, 10, 22)))
        assertEquals("on the 13th of each month", RegularSchedule.describe(Frequency.MONTHLY, LocalDate.of(2026, 10, 13)))
        assertEquals("every Friday", RegularSchedule.describe(Frequency.WEEKLY, LocalDate.of(2026, 10, 2)))
    }
}
