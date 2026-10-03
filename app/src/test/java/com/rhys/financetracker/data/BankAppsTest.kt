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

class BankAppsBorrowingTest {

    private val mortgage = BankApps.BorrowingOption(10, "Nationwide Mortgage", "mortgage")
    private val carLoan = BankApps.BorrowingOption(11, "Car finance", "loan")
    private val klarna = BankApps.BorrowingOption(12, "Klarna", "pay later")
    private val tescoCard = BankApps.BorrowingOption(13, "Tesco Credit Card", "credit card")
    private val all = listOf(mortgage, carLoan, klarna, tescoCard)

    @Test
    fun `an overpayment goes to the kind it names`() {
        assertEquals(10L, BankApps.pickBorrowing("mortgage", "Overpayment to mortgage", all, fromAccountId = 1))
        assertEquals(11L, BankApps.pickBorrowing("loan", "Overpayment to loan", all, fromAccountId = 1))
        assertEquals(13L, BankApps.pickBorrowing("credit card", "Payment to credit card", all, fromAccountId = 1))
    }

    @Test
    fun `a lender named as the payee is paying it off`() {
        assertEquals(12L, BankApps.pickBorrowing(null, "KLARNA*ASOS", all, fromAccountId = 1))
    }

    @Test
    fun `shopping is not paying off a shop's card`() {
        assertNull(BankApps.pickBorrowing(null, "TESCO STORES 3012", all, fromAccountId = 1))
        // Klarna's own alert, already in the Klarna account, is a purchase.
        assertNull(BankApps.pickBorrowing(null, "Klarna", listOf(klarna), fromAccountId = 12))
    }
}
