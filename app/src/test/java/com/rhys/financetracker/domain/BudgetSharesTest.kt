package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.budget.BudgetShares
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each person's column of the budget. */
class BudgetSharesTest {

    private val sam = 1L
    private val alex = 2L
    private val people = listOf(sam, alex)

    @Test
    fun `shares are each person's, a line with none is split evenly, and a different month scales them`() {
        assertEquals(mapOf(sam to 12_000L, alex to 8_000L), BudgetShares.forMonth(mapOf(sam to 12_000L, alex to 8_000L), 20_000, 20_000, people))
        assertEquals(mapOf(sam to 10_001L, alex to 10_000L), BudgetShares.forMonth(emptyMap(), 20_001, 20_001, people))
        // Changed to £300 for the month: £180 and £120.
        assertEquals(mapOf(sam to 18_000L, alex to 12_000L), BudgetShares.forMonth(mapOf(sam to 12_000L, alex to 8_000L), 20_000, 30_000, people))
        // Someone not on the sheet has no share.
        assertEquals(mapOf(sam to 12_000L, alex to 0L), BudgetShares.forMonth(mapOf(sam to 12_000L), 20_000, 20_000, people))
    }

    @Test
    fun `the joint part is what no one person has`() {
        assertEquals(5_000L, BudgetShares.unassigned(mapOf(sam to 10_000L, alex to 5_000L), 20_000, people))
        assertEquals(0L, BudgetShares.unassigned(emptyMap(), 20_000, people))
    }

    @Test
    fun `changing one person's share keeps the others and the joint part`() {
        // Not split yet: £100 each, then Sam's is £120.
        assertEquals(mapOf(sam to 12_000L, alex to 10_000L) to 22_000L, BudgetShares.withPerson(emptyMap(), 20_000, people, sam, 12_000))
        // Split with £50 joint: the joint part stays.
        assertEquals(
            mapOf(sam to 10_000L, alex to 2_000L) to 17_000L,
            BudgetShares.withPerson(mapOf(sam to 10_000L, alex to 5_000L), 20_000, people, alex, 2_000),
        )
        assertEquals(mapOf(sam to 15_000L, alex to 7_500L), BudgetShares.scaled(mapOf(sam to 10_000L, alex to 5_000L), 20_000, 30_000))
    }
}
