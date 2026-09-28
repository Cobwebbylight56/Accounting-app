package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.PotFlow
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.report.FinancialSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Which money counts as Available, Saved and Owed.
 *
 * One stored answer per account — its [Holding] — and nothing else. The type
 * only suggests it when the account is made.
 */
class SavingsClassificationTest {

    private fun account(
        name: String,
        type: AccountType,
        holding: Holding = type.defaultHolding,
        balanceMinor: Long = 100_000L,
        personId: Long? = null,
    ) = AccountWithBalance(
        account = AccountEntity(
            id = 1L,
            name = name,
            type = type,
            holding = holding,
            personId = personId,
            openingBalanceMinor = 0L,
            openingBalanceDate = LocalDate.of(2026, 1, 1),
            colorHex = "#455A64",
        ),
        balanceMinor = balanceMinor,
        personName = null,
    )

    @Test
    fun `the type suggests where an account counts`() {
        assertTrue(account("Saver", AccountType.SAVINGS).isSavings)
        assertTrue(account("Pension", AccountType.PENSION).isSavings)
        assertTrue(!account("Main account", AccountType.CURRENT).isSavings)
        assertTrue(account("Barclaycard", AccountType.CREDIT_CARD).isLiability)
    }

    @Test
    fun `the holding is the answer, whatever the type`() {
        // The case this was built for: a saver typed as a current account,
        // which sat in Available while Saved said there was nothing saved.
        val saver = account("saver", AccountType.CURRENT, holding = Holding.SET_ASIDE)
        assertTrue(saver.isSavings)
        assertTrue(!saver.isLiability)
        // A current account kept untouched in case the main one is lost.
        assertTrue(account("Spare", AccountType.CURRENT, holding = Holding.SET_ASIDE).isSavings)
        // And the other way, for a savings account being spent down.
        assertTrue(!account("Saver", AccountType.SAVINGS, holding = Holding.SPEND).isSavings)
    }

    @Test
    fun `cash is not an account type`() {
        // Notes in the house are the cash pot, which belongs to nobody's
        // account; there is nothing to pick when making an account.
        assertTrue(AccountType.entries.none { it.name == "CASH" })
    }

    @Test
    fun `an account nobody owns belongs to no person's view`() {
        val owned = account("Main account", AccountType.CURRENT, personId = 1L)
        val nobodys = account("Main account", AccountType.CURRENT, personId = null)
        val all = listOf(owned, nobodys)

        assertEquals(listOf(owned), all.filter { it.account.personId == 1L })
        assertTrue(all.none { it.account.personId == 2L })
        assertEquals(1, all.count { it.account.personId == null })
    }

    @Test
    fun `a month is judged on both directions, not only what went in`() {
        val month = PotFlow(intoPotMinor = 20_000L, outOfPotMinor = 50_000L)
        assertEquals(-30_000L, month.netMinor)
        assertEquals(25_000L, PotFlow(intoPotMinor = 25_000L, outOfPotMinor = 0L).netMinor)
        assertEquals(0L, PotFlow.EMPTY.netMinor)
    }

    @Test
    fun `money put aside is not spending, but it is not left to spend either`() {
        // Income and spending totals leave savings out entirely, so the
        // month's figures are what was earned and spent. What was put aside
        // is taken off what is left to spend instead.
        val summary = FinancialSummary.EMPTY.copy(
            monthIncomeMinor = 200_000L,
            monthExpenseMinor = 120_000L,
            committedRecurringMinor = 30_000L,
            savingsInMinor = 25_000L,
            savingsOutMinor = 5_000L,
        )
        assertEquals(80_000L, summary.monthNetMinor)
        assertEquals(20_000L, summary.savingsNetMinor)
        assertEquals(30_000L, summary.disposableMinor)
    }

    @Test
    fun `cash alone is not savings activity`() {
        val cashOnly = FinancialSummary.EMPTY.copy(cashOutMinor = 4_000L)
        assertTrue(!cashOnly.hasSavingsActivity)
        assertTrue(cashOnly.hasPotActivity)
        assertTrue(!FinancialSummary.EMPTY.hasPotActivity)
    }

    @Test
    fun `the cash pot total is what went in less what came out`() {
        val entries = listOf(
            CashPotEntryEntity(date = LocalDate.of(2026, 9, 1), amountMinor = 151_000L, isIn = true),
            CashPotEntryEntity(date = LocalDate.of(2026, 9, 3), amountMinor = 2_500L, isIn = false),
            CashPotEntryEntity(date = LocalDate.of(2026, 9, 9), amountMinor = 4_000L, isIn = true),
        )
        assertEquals(152_500L, entries.sumOf { it.signedMinor })
    }

    @Test
    fun `the three totals split the money without dropping or double counting it`() {
        val accounts = listOf(
            account("Main account", AccountType.CURRENT, balanceMinor = -9_382L),
            account("bank", AccountType.CURRENT, holding = Holding.SET_ASIDE, balanceMinor = 300_000L),
            account("saver mum", AccountType.SAVINGS, balanceMinor = 190_000L),
            account("Barclaycard", AccountType.CREDIT_CARD, balanceMinor = -45_000L),
        )
        val saved = accounts.filter { it.isSavings }.sumOf { it.balanceMinor }
        val owed = accounts.filter { it.isLiability }.sumOf { it.balanceMinor }
        val available = accounts.filterNot { it.isSavings || it.isLiability }
            .sumOf { it.balanceMinor }

        assertEquals(490_000L, saved)
        assertEquals(-45_000L, owed)
        assertEquals(-9_382L, available)
        assertEquals(accounts.sumOf { it.balanceMinor }, saved + owed + available)
    }
}
