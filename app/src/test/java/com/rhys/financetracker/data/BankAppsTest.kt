package com.rhys.financetracker.data

import com.rhys.financetracker.data.live.BankApps
import com.rhys.financetracker.data.live.BankApps.AccountOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankAppsTest {

    private val current = AccountOption(1, "Rhys Current", null, isSpending = true, isCard = false)
    private val joint = AccountOption(2, "Joint Nationwide", null, isSpending = true, isCard = false)
    private val saver = AccountOption(3, "Lloyds Monthly Saver", null, isSpending = false, isCard = false)
    private val lloydsCurrent = AccountOption(4, "Lloyds Classic 5678", null, isSpending = true, isCard = false)
    private val amex = AccountOption(5, "American Express", null, isSpending = false, isCard = true)
    private val all = listOf(current, joint, saver, lloydsCurrent, amex)

    @Test
    fun `the card's last digits win`() {
        assertEquals(4L, BankApps.pickAccount("Nationwide", "5678", all, defaultAccountId = 1))
    }

    @Test
    fun `then an account named after the bank, spending first`() {
        assertEquals(2L, BankApps.pickAccount("Nationwide", null, all, defaultAccountId = 1))
        assertEquals(4L, BankApps.pickAccount("Lloyds", null, all, defaultAccountId = 1))
        assertEquals(5L, BankApps.pickAccount("American Express", null, all, defaultAccountId = 1))
    }

    @Test
    fun `otherwise the main account, then the first day-to-day one`() {
        assertEquals(3L, BankApps.pickAccount("Monzo", null, all, defaultAccountId = 3))
        assertEquals(1L, BankApps.pickAccount("Google Wallet", null, all, defaultAccountId = null))
        assertNull(BankApps.pickAccount("Monzo", null, emptyList(), defaultAccountId = null))
    }
}
