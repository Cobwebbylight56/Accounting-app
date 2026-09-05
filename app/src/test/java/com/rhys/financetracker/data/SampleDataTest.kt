package com.rhys.financetracker.data

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.seed.SampleData
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sample data is a faithful copy of the original "Book r and h"
 * spreadsheet.  These tests are the guard rail: if someone edits a figure, the
 * totals stop matching the sheet and the build fails, which is exactly the
 * safety the spreadsheet itself never had.
 */
class SampleDataTest {

    private fun expensesFor(person: String): Long =
        SampleData.expenseRules
            .filter { it.personName == person }
            .sumOf { Money.fromMajor(it.amountMajor) }

    private fun savingsFor(person: String): Long =
        SampleData.savingsTransferRules
            .filter { it.personName == person }
            .sumOf { Money.fromMajor(it.amountMajor) }

    private fun incomeFor(person: String): Long =
        SampleData.incomeRules
            .filter { it.personName == person }
            .sumOf { Money.fromMajor(it.amountMajor) }

    @Test
    fun `Rhys outgoings match the spreadsheet total of 1941 pounds 63`() {
        // The sheet's "spent" row for Rhys: £1,941.63, including the £504 put away.
        assertEquals(
            194_163L,
            expensesFor(SampleData.PERSON_RHYS) + savingsFor(SampleData.PERSON_RHYS),
        )
    }

    @Test
    fun `Hannah outgoings match the spreadsheet total of 1558 pounds 14`() {
        assertEquals(
            155_814L,
            expensesFor(SampleData.PERSON_HANNAH) + savingsFor(SampleData.PERSON_HANNAH),
        )
    }

    @Test
    fun `Rhys leftover matches the spreadsheet figure of minus 79 pounds 40`() {
        val leftOver = incomeFor(SampleData.PERSON_RHYS) -
            expensesFor(SampleData.PERSON_RHYS) - savingsFor(SampleData.PERSON_RHYS)
        assertEquals(-7_940L, leftOver)
    }

    @Test
    fun `Hannah leftover matches the spreadsheet figure of minus 111 pounds 14`() {
        val leftOver = incomeFor(SampleData.PERSON_HANNAH) -
            expensesFor(SampleData.PERSON_HANNAH) - savingsFor(SampleData.PERSON_HANNAH)
        assertEquals(-11_114L, leftOver)
    }

    @Test
    fun `combined income matches the spreadsheet figure of 3309 pounds 23`() {
        assertEquals(
            330_923L,
            SampleData.incomeRules.sumOf { Money.fromMajor(it.amountMajor) },
        )
    }

    @Test
    fun `opening balances total the ALL SAVINGS figure of 11418 pounds 37`() {
        assertEquals(
            1_141_837L,
            SampleData.accounts.sumOf { Money.fromMajor(it.openingBalanceMajor) },
        )
    }

    @Test
    fun `the put away rows total 1204 pounds a month`() {
        assertEquals(
            120_400L,
            SampleData.savingsTransferRules.sumOf { Money.fromMajor(it.amountMajor) },
        )
    }

    @Test
    fun `every rule names an account that exists`() {
        val accountNames = SampleData.accounts.map { it.name }.toSet()
        SampleData.allRules.forEach { rule ->
            assertTrue(
                "\"${rule.name}\" refers to unknown account \"${rule.accountName}\"",
                rule.accountName in accountNames,
            )
        }
    }

    @Test
    fun `the savings destination account exists`() {
        assertTrue(
            SampleData.SAVINGS_DESTINATION_ACCOUNT in SampleData.accounts.map { it.name },
        )
    }

    @Test
    fun `every due day is a valid day of the month`() {
        SampleData.allRules.forEach { rule ->
            assertTrue(
                "\"${rule.name}\" has an impossible due day",
                rule.dayOfMonth in 1..28,
            )
        }
    }

    @Test
    fun `the savings transfers are transfers, not expenses`() {
        // This is the correction the app makes to the spreadsheet: money put by
        // is kept, not spent.
        assertTrue(
            SampleData.savingsTransferRules.all { it.type == TransactionType.TRANSFER },
        )
    }
}
