package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.PotFlow
import com.rhys.financetracker.domain.report.FinancialSummary
import com.rhys.financetracker.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Which accounts count as "Saved" rather than "Available".
 *
 * The type alone cannot decide it. A current account nobody touches is savings
 * to the person who owns it, and an account called "cash" can be the money set
 * aside rather than the money to hand — so the owner can say, and the type is
 * only the default.
 */
class SavingsClassificationTest {

    private fun account(
        name: String,
        type: AccountType,
        countsAsSavings: Boolean? = null,
        balanceMinor: Long = 100_000L,
        personId: Long? = null,
    ) = AccountWithBalance(
        account = AccountEntity(
            id = 1L,
            name = name,
            type = type,
            personId = personId,
            openingBalanceMinor = 0L,
            openingBalanceDate = LocalDate.of(2026, 1, 1),
            colorHex = "#455A64",
            countsAsSavings = countsAsSavings,
        ),
        balanceMinor = balanceMinor,
        personName = null,
    )

    @Test
    fun `the type decides when nobody has said otherwise`() {
        assertTrue(account("Saver", AccountType.SAVINGS).isSavings)
        assertTrue(account("Pension", AccountType.PENSION).isSavings)
        assertTrue(!account("Main account", AccountType.CURRENT).isSavings)
        assertTrue(!account("cash", AccountType.CASH).isSavings)
    }

    @Test
    fun `saying so overrides the type in both directions`() {
        // The case this was built for: money kept in an ordinary account or in
        // cash, which the app was counting as spendable.
        assertTrue(account("bank", AccountType.CURRENT, countsAsSavings = true).isSavings)
        assertTrue(account("cash", AccountType.CASH, countsAsSavings = true).isSavings)
        // And the other way, for a savings account being spent down.
        assertTrue(!account("Saver", AccountType.SAVINGS, countsAsSavings = false).isSavings)
    }

    @Test
    fun `an account nobody owns belongs to no person's view`() {
        // The fault behind "when I select Rhys it just wants me to add an
        // account": an imported account with no owner is filtered out of every
        // person's slice of the app while still holding all of the money, and
        // nothing on either screen said the two were connected.
        val owned = account("Main account", AccountType.CURRENT, personId = 1L)
        val nobodys = account("Rhys Evans", AccountType.CURRENT, personId = null)
        val all = listOf(owned, nobodys)

        assertEquals(listOf(owned), all.filter { it.account.personId == 1L })
        assertTrue(all.none { it.account.personId == 2L })
        assertEquals(1, all.count { it.account.personId == null })
    }

    @Test
    fun `a month is judged on both directions, not only what went in`() {
        // The fault: only payments into a saver were counted. A month that put
        // £200 in and took £500 back out was reported as having saved £200,
        // when it had drawn its savings down by £300.
        val month = PotFlow(intoPotMinor = 20_000L, outOfPotMinor = 50_000L)
        assertEquals(-30_000L, month.netMinor)

        val saving = PotFlow(intoPotMinor = 25_000L, outOfPotMinor = 0L)
        assertEquals(25_000L, saving.netMinor)
        assertEquals(0L, PotFlow.EMPTY.netMinor)
    }

    @Test
    fun `what the app has watched move stands in for a saver it cannot see`() {
        // With no savings account in the app there is no balance to show, and
        // £0.00 was a lie about a household saving every month. The movements
        // it has seen are the honest answer.
        val summary = FinancialSummary(
            totalBalanceMinor = 553_424L,
            totalSavingsMinor = 0L,
            totalLiabilitiesMinor = 0L,
            netWorthMinor = 553_424L,
            monthIncomeMinor = 0L,
            monthExpenseMinor = 0L,
            committedRecurringMinor = 0L,
            savingsInMinor = 5_000L,
            savingsOutMinor = 0L,
            savingsEverMovedMinor = 15_000L,
            cashOutMinor = 4_000L,
            cashInMinor = 1_000L,
        )
        assertEquals(5_000L, summary.savingsNetMinor)
        assertEquals(3_000L, summary.cashNetMinor)
        assertTrue(summary.hasPotActivity)
        assertTrue(!FinancialSummary.EMPTY.hasPotActivity)
    }

    @Test
    fun `the two totals split the money without dropping or double counting it`() {
        val accounts = listOf(
            account("Main account", AccountType.CURRENT, balanceMinor = -9_382L),
            account("bank", AccountType.CURRENT, countsAsSavings = true, balanceMinor = 300_000L),
            account("cash", AccountType.CASH, countsAsSavings = true, balanceMinor = 151_000L),
            account("saver mum", AccountType.SAVINGS, balanceMinor = 190_000L),
        )
        val saved = accounts.filter { it.isSavings }.sumOf { it.balanceMinor }
        val available = accounts.filterNot { it.isSavings || it.isLiability }
            .sumOf { it.balanceMinor }

        assertEquals(641_000L, saved)
        assertEquals(-9_382L, available)
        // Every pound is in exactly one of the two.
        assertEquals(accounts.sumOf { it.balanceMinor }, saved + available)
    }
}
