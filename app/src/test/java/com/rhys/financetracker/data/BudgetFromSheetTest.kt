package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.BudgetFromSheet
import com.rhys.financetracker.data.importer.HouseholdLayoutDetector
import com.rhys.financetracker.data.importer.SheetData
import com.rhys.financetracker.domain.budget.LineGoal
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The household sheet the app began with, read as a budget. */
class BudgetFromSheetTest {

    private fun row(vararg cells: String) = cells.toList()

    private val sheet = SheetData(
        "Budget",
        listOf(
            row("", "", "income", "befor tax", "27,455.76", "", "16,692.48"),
            row("", "", "", "", "Sam", "", "Alex", "", "both"),
            row("", "", "income", "", "1862.23", "", "1447", "", "3309.23"),
            row(),
            row("", "", "OUTGOINGS"),
            row("", "", "car", "", "60.75", "", "80.59", "", "141.34"),
            row("", "", "road tax", "", "37.62", "", "24.06", "", "61.68"),
            row("", "", "fuel", "", "120", "", "80", "", "200"),
            row("", "", "food shop", "", "150", "", "150", "", "300"),
            row("", "", "phone", "", "20", "", "", "", "20"),
            row("", "", "spent", "", "388.37", "", "334.65", "", "723.02"),
            row(),
            row("", "", "savings &"),
            row("", "", "lloyds saver", "", "500", "", "200", "", "700"),
        ),
    )

    @Test
    fun `each outgoing is everyone's figures added up, never the both column or the total row`() {
        val layout = HouseholdLayoutDetector.detect(sheet)!!
        val rows = BudgetFromSheet.rows(sheet, layout)
        assertEquals(listOf("Car", "Road tax", "Fuel", "Food shop", "Phone"), rows.map { it.name })
        assertEquals(14_134L, rows[0].amountMinor)
        assertEquals(mapOf("Sam" to 6_075L, "Alex" to 8_059L), rows[0].byPerson)
        assertEquals(2_000L, rows[4].amountMinor)
    }

    private val categories = listOf(
        BudgetFromSheet.Category(1, "Fuel", CategoryKind.EXPENSE),
        BudgetFromSheet.Category(2, "Groceries", CategoryKind.EXPENSE),
        BudgetFromSheet.Category(3, "Road tax", CategoryKind.EXPENSE),
        BudgetFromSheet.Category(4, "Mobile", CategoryKind.EXPENSE),
        BudgetFromSheet.Category(5, "Savings", CategoryKind.SAVING),
        BudgetFromSheet.Category(6, "Home insurance", CategoryKind.EXPENSE),
        BudgetFromSheet.Category(7, "Car finance", CategoryKind.EXPENSE),
    )
    private val accounts = listOf(
        BudgetFromSheet.Account(10, "Lloyds Current Account", Holding.SPEND),
        BudgetFromSheet.Account(11, "Car loan", Holding.OWED),
        BudgetFromSheet.Account(12, "Moneybox LISA", Holding.SET_ASIDE),
    )

    @Test
    fun `each line finds where its money goes, and fuel and food are aims`() {
        fun m(label: String) = BudgetFromSheet.match(label, categories, accounts)
        assertEquals(BudgetFromSheet.Match(categoryId = 1, aim = true), m("fuel"))
        assertEquals(BudgetFromSheet.Match(categoryId = 1, aim = true), m("Petrol"))
        assertEquals(BudgetFromSheet.Match(categoryId = 2, aim = true), m("food shop"))
        assertEquals(BudgetFromSheet.Match(categoryId = 3), m("road tax"))
        assertEquals(BudgetFromSheet.Match(categoryId = 4), m("phone"))
        assertEquals(BudgetFromSheet.Match(categoryId = 6), m("house insurance"))
        // Paid into a loan, or a saver: an amount to reach.
        assertEquals(BudgetFromSheet.Match(accountId = 11, goal = LineGoal.PUT_ASIDE_AT_LEAST), m("car"))
        assertEquals(BudgetFromSheet.Match(accountId = 12, goal = LineGoal.PUT_ASIDE_AT_LEAST), m("LISA"))
        assertEquals(BudgetFromSheet.Match(categoryId = 5, goal = LineGoal.PUT_ASIDE_AT_LEAST), m("savings"))
        // "Car fuel" is fuel, not the car loan.
        assertEquals(1L, m("car fuel").categoryId)
        // Nothing fits: left to choose.
        val unknown = m("bazaar")
        assertNull(unknown.categoryId)
        assertNull(unknown.accountId)
        assertFalse(unknown.aim)
        assertTrue(BudgetFromSheet.isAim("pocket money", null))
    }
}
