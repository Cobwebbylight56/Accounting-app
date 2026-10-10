package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.WageFinder
import com.rhys.financetracker.data.importer.WageFinder.PaidIn
import com.rhys.financetracker.data.live.BankAlertParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WageFinderTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun paid(description: String, pence: Long, date: LocalDate, category: String? = null, account: Long = 1L, person: Long? = 7L) =
        PaidIn(description, pence, date, account, category, person)

    /** Paid on the 28th, or the Friday before when it falls at a weekend. */
    private val wages = listOf(
        paid("ACME LOGISTICS LTD BGC", 186_216, LocalDate.of(2026, 5, 28)),
        paid("ACME LOGISTICS LTD BGC", 191_040, LocalDate.of(2026, 6, 26)),
        paid("ACME LOGISTICS LTD BGC", 186_216, LocalDate.of(2026, 7, 28)),
        paid("ACME LOGISTICS LTD BGC", 203_500, LocalDate.of(2026, 8, 28)),
        paid("ACME LOGISTICS LTD BGC", 186_216, LocalDate.of(2026, 9, 28)),
    )

    @Test
    fun `the same employer paying in every month is the wage`() {
        val found = WageFinder.find(wages, today).single()
        assertEquals(7L, found.personId)
        assertEquals(1L, found.accountId)
        assertEquals(186_216L, found.amountMinor)
        assertEquals(28, found.day)
        assertEquals("Acme Logistics", found.payer)
    }

    @Test
    fun `an employer whose name reads like a person's is still the wage`() {
        val found = WageFinder.find(wages.map { it.copy(description = "ACME LOGISTICS") }, today).single()
        assertEquals(186_216L, found.amountMinor)
    }

    @Test
    fun `money from a person, refunds and small sums are not a wage`() {
        val others = (5..9).flatMap { month ->
            listOf(
                paid("H EVANS", 40_000, LocalDate.of(2026, month, 2), category = "Transfers & payments"),
                paid("AMAZON REFUND", 45_000, LocalDate.of(2026, month, 12)),
                paid("NATIONWIDE INTEREST", 120, LocalDate.of(2026, month, 1)),
            )
        }
        assertTrue(WageFinder.find(others, today).isEmpty())
    }

    @Test
    fun `a job that stopped paying is not the wage`() {
        val old = wages.map { it.copy(date = it.date.minusMonths(4)) }
        assertTrue(WageFinder.find(old, today).isEmpty())
    }

    @Test
    fun `nobody's account, nobody's wage`() {
        assertTrue(WageFinder.find(wages.map { it.copy(personId = null) }, today).isEmpty())
    }

    @Test
    fun `the balance a bank's alert gives`() {
        assertEquals(84_010L, BankAlertParser.balanceIn("Nationwide", "You spent £12.50 at TESCO. Your balance is £840.10"))
        assertEquals(120_455L, BankAlertParser.balanceIn("Daily balance", "Available balance: £1,204.55"))
        assertEquals(-1_230L, BankAlertParser.balanceIn("Lloyds", "Balance -£12.30"))
        assertEquals(-1_230L, BankAlertParser.balanceIn("Lloyds", "Your balance is £12.30 overdrawn"))
        assertNull(BankAlertParser.balanceIn("Balance update", "You spent £12.50 at TESCO"))
        assertNull(BankAlertParser.balanceIn("Nationwide", "You spent £12.50 at TESCO"))
    }
}
