package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
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
    ) = AccountWithBalance(
        account = AccountEntity(
            id = 1L,
            name = name,
            type = type,
            personId = null,
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
