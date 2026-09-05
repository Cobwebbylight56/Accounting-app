package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recurrence engine is what makes the app self-maintaining, so its edge
 * cases are tested closely: short months, leap years, catching up after a long
 * gap, and rules that have finished.
 */
class RecurrenceCalculatorTest {

    private fun rule(
        frequency: Frequency = Frequency.MONTHLY,
        start: LocalDate = LocalDate.of(2026, 1, 15),
        next: LocalDate = start,
        interval: Int = 1,
        end: LocalDate? = null,
        maxOccurrences: Int? = null,
        generated: Int = 0,
    ) = RecurringRuleEntity(
        id = 1L,
        name = "Test",
        amountMinor = 1000L,
        type = TransactionType.EXPENSE,
        frequency = frequency,
        interval = interval,
        startDate = start,
        endDate = end,
        maxOccurrences = maxOccurrences,
        occurrencesGenerated = generated,
        nextDueDate = next,
        accountId = 1L,
    )

    // ------------------------------------------------------ occurrence dates

    @Test
    fun `monthly keeps the day of the month`() {
        val start = LocalDate.of(2026, 1, 15)
        assertEquals(
            LocalDate.of(2026, 4, 15),
            RecurrenceCalculator.occurrenceDate(start, Frequency.MONTHLY, 1, 3),
        )
    }

    @Test
    fun `a bill due on the 31st clamps to the end of a short month`() {
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(
            LocalDate.of(2026, 2, 28),
            RecurrenceCalculator.occurrenceDate(start, Frequency.MONTHLY, 1, 1),
        )
    }

    @Test
    fun `a bill due on the 31st returns to the 31st the following month`() {
        // This is the behaviour naive plusMonths chaining gets wrong: after
        // clamping to 28 February it would stay on the 28th forever.
        val start = LocalDate.of(2026, 1, 31)
        assertEquals(
            LocalDate.of(2026, 3, 31),
            RecurrenceCalculator.occurrenceDate(start, Frequency.MONTHLY, 1, 2),
        )
    }

    @Test
    fun `the 29th of February is handled in a leap year`() {
        val start = LocalDate.of(2024, 1, 29)
        assertEquals(
            LocalDate.of(2024, 2, 29),
            RecurrenceCalculator.occurrenceDate(start, Frequency.MONTHLY, 1, 1),
        )
    }

    @Test
    fun `fortnightly stays on the same weekday`() {
        val start = LocalDate.of(2026, 3, 2)
        val fourth = RecurrenceCalculator.occurrenceDate(start, Frequency.FORTNIGHTLY, 1, 4)
        assertEquals(LocalDate.of(2026, 4, 27), fourth)
        assertEquals(start.dayOfWeek, fourth.dayOfWeek)
    }

    @Test
    fun `every four weeks is 28 days apart, not a month`() {
        val start = LocalDate.of(2026, 1, 1)
        assertEquals(
            LocalDate.of(2026, 1, 29),
            RecurrenceCalculator.occurrenceDate(start, Frequency.FOUR_WEEKLY, 1, 1),
        )
    }

    @Test
    fun `quarterly moves three months at a time`() {
        val start = LocalDate.of(2026, 1, 10)
        assertEquals(
            LocalDate.of(2026, 7, 10),
            RecurrenceCalculator.occurrenceDate(start, Frequency.QUARTERLY, 1, 2),
        )
    }

    @Test
    fun `a custom interval in days is respected`() {
        val start = LocalDate.of(2026, 1, 1)
        assertEquals(
            LocalDate.of(2026, 1, 31),
            RecurrenceCalculator.occurrenceDate(start, Frequency.CUSTOM_DAYS, 10, 3),
        )
    }

    @Test
    fun `a custom interval in months is respected`() {
        val start = LocalDate.of(2026, 1, 20)
        assertEquals(
            LocalDate.of(2026, 11, 20),
            RecurrenceCalculator.occurrenceDate(start, Frequency.CUSTOM_MONTHS, 5, 2),
        )
    }

    // ------------------------------------------------------- catching up

    @Test
    fun `catching up after three missed months produces three occurrences`() {
        val rule = rule(start = LocalDate.of(2026, 1, 15), next = LocalDate.of(2026, 1, 15))
        val due = RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 3, 20))
        assertEquals(
            listOf(
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15),
            ),
            due,
        )
    }

    @Test
    fun `nothing is due before the start date`() {
        val rule = rule(start = LocalDate.of(2026, 6, 1), next = LocalDate.of(2026, 6, 1))
        assertTrue(RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 3, 1)).isEmpty())
    }

    @Test
    fun `generation stops at the end date`() {
        val rule = rule(
            start = LocalDate.of(2026, 1, 15),
            next = LocalDate.of(2026, 1, 15),
            end = LocalDate.of(2026, 2, 20),
        )
        val due = RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 6, 1))
        assertEquals(2, due.size)
    }

    @Test
    fun `generation stops at the occurrence limit`() {
        val rule = rule(
            start = LocalDate.of(2026, 1, 15),
            next = LocalDate.of(2026, 1, 15),
            maxOccurrences = 2,
        )
        assertEquals(2, RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 12, 1)).size)
    }

    @Test
    fun `a paused rule generates nothing`() {
        val rule = rule(next = LocalDate.of(2026, 1, 15)).copy(isPaused = true)
        assertTrue(RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 6, 1)).isEmpty())
    }

    @Test
    fun `a one-off generates exactly once`() {
        val rule = rule(frequency = Frequency.ONE_OFF, start = LocalDate.of(2026, 2, 3))
        val due = RecurrenceCalculator.occurrencesDue(rule, LocalDate.of(2026, 12, 1))
        assertEquals(listOf(LocalDate.of(2026, 2, 3)), due)
    }

    // ---------------------------------------------------- next occurrence

    @Test
    fun `the next occurrence is strictly after the given date`() {
        val rule = rule(start = LocalDate.of(2026, 1, 15))
        assertEquals(
            LocalDate.of(2026, 2, 15),
            RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 1, 15)),
        )
    }

    @Test
    fun `a finished one-off has no next occurrence`() {
        val rule = rule(frequency = Frequency.ONE_OFF, start = LocalDate.of(2026, 1, 15))
        assertNull(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 1, 15)))
    }

    @Test
    fun `a rule past its end date has no next occurrence`() {
        val rule = rule(
            start = LocalDate.of(2026, 1, 15),
            end = LocalDate.of(2026, 3, 1),
        )
        assertNull(RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 3, 1)))
    }

    @Test
    fun `catching up from years ago does not take forever`() {
        // The index is estimated arithmetically rather than by stepping, so a
        // rule that started in 2000 resolves immediately.
        val rule = rule(start = LocalDate.of(2000, 1, 15))
        val next = RecurrenceCalculator.nextOccurrenceAfter(rule, LocalDate.of(2026, 3, 20))
        assertEquals(LocalDate.of(2026, 4, 15), next)
    }

    // -------------------------------------------------- monthly equivalent

    @Test
    fun `weekly amounts normalise to a monthly figure`() {
        // £20 a week is 52 payments a year, so £86.67 a month.
        assertEquals(8667L, RecurrenceCalculator.monthlyEquivalentMinor(2000L, Frequency.WEEKLY, 1))
    }

    @Test
    fun `yearly amounts normalise to a monthly figure`() {
        assertEquals(5000L, RecurrenceCalculator.monthlyEquivalentMinor(60_000L, Frequency.YEARLY, 1))
    }

    @Test
    fun `monthly amounts are unchanged`() {
        assertEquals(
            16_200L,
            RecurrenceCalculator.monthlyEquivalentMinor(16_200L, Frequency.MONTHLY, 1),
        )
    }

    @Test
    fun `a one-off contributes nothing to the monthly figure`() {
        assertEquals(0L, RecurrenceCalculator.monthlyEquivalentMinor(50_000L, Frequency.ONE_OFF, 1))
    }

    @Test
    fun `upcoming occurrences stay inside the window`() {
        val rule = rule(start = LocalDate.of(2026, 1, 15), next = LocalDate.of(2026, 1, 15))
        val upcoming = RecurrenceCalculator.upcomingOccurrences(
            rule = rule,
            from = LocalDate.of(2026, 2, 1),
            to = LocalDate.of(2026, 4, 30),
        )
        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 4, 15),
            ),
            upcoming,
        )
    }
}
