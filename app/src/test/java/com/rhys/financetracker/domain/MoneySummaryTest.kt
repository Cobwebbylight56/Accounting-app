package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.report.MoneySummary
import com.rhys.financetracker.domain.report.ProgressFigures
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What's shared with Claude: the month in plain lines, and nothing that identifies an account. */
class MoneySummaryTest {

    private val october = ProgressFigures(
        moneyInMinor = 321_000,
        spentMinor = 298_050,
        savedMinor = 15_000,
        owedChangeMinor = -12_000,
        byCategory = mapOf("Groceries" to 51_220, "Fuel" to 18_000, "Eating out" to 9_500),
    )

    @Test
    fun `it asks the question, then gives the month's figures`() {
        val text = MoneySummary.text(
            MoneySummary.Input(
                periodName = "October 2026",
                figures = october,
                before = october.copy(spentMinor = 286_050),
                beforeName = "September 2026",
                biggest = listOf("Utility Warehouse" to 18_395),
                owedNowMinor = 894_095,
                savedNowMinor = 475_176,
                availableNowMinor = 306_438,
                people = 2,
            ),
        )
        assertTrue(text.startsWith("Here's a summary of our household's money for October 2026"))
        assertTrue(text.contains("- Money in: £3,210.00"))
        assertTrue(text.contains("- Left over: £229.50"))
        assertTrue(text.contains("- Owed on cards and loans: down £120.00"))
        assertTrue(text.contains("- Spent: £120.00 more"))
        assertTrue(text.contains("- Groceries: £512.20\n- Fuel: £180.00"))
        assertTrue(text.contains("- Utility Warehouse: £183.95"))
        assertTrue(text.contains("- Owed on cards and loans: £8,940.95"))
    }

    @Test
    fun `a month that went over says so, and a long list of categories is added up`() {
        val many = (1..15).associate { "Category $it" to it * 1_000L }
        val text = MoneySummary.text(
            MoneySummary.Input("November 2026 so far", october.copy(spentMinor = 330_000, byCategory = many)),
        )
        assertTrue(text.contains("- Left over: −£90.00"))
        assertTrue(text.contains("- Everything else (3 more): £60.00"))
        assertFalse(text.contains("Compared with"))
        assertFalse(text.contains("Where things stand now"))
    }
}
