package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.loan.LoanMaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanMathsTest {

    @Test
    fun `the monthly payment matches a lender's figure`() {
        // £10,000 over 5 years at 6.9% APR is about £197.54 a month.
        val monthly = LoanMaths.monthlyPayment(1_000_000, 6.9, 60)
        assertTrue("was $monthly", monthly in 19_750L..19_760L)
        // A £200,000 mortgage over 25 years at 4.5%: about £1,111.67.
        val mortgage = LoanMaths.monthlyPayment(20_000_000, 4.5, 300)
        assertTrue("was $mortgage", mortgage in 111_160L..111_170L)
    }

    @Test
    fun `that payment clears it in the term it came from`() {
        val monthly = LoanMaths.monthlyPayment(1_000_000, 6.9, 60)
        assertEquals(60, LoanMaths.monthsToClear(1_000_000, 6.9, monthly))
        assertEquals(0L, LoanMaths.schedule(1_000_000, 6.9, monthly).last().owedAfterMinor)
    }

    @Test
    fun `no interest is an even split`() {
        assertEquals(10_000L, LoanMaths.monthlyPayment(120_000, 0.0, 12))
        assertEquals(12, LoanMaths.monthsToClear(120_000, 0.0, 10_000))
        assertEquals(0L, LoanMaths.interestToPay(120_000, 0.0, 10_000))
    }

    @Test
    fun `a payment no bigger than the interest never clears it`() {
        // £10,000 at 12% is £100 a month in interest.
        assertNull(LoanMaths.monthsToClear(1_000_000, 12.0, 10_000))
        assertTrue(LoanMaths.schedule(1_000_000, 12.0, 10_000).isEmpty())
    }

    @Test
    fun `paying more clears it sooner and saves interest`() {
        val monthly = LoanMaths.monthlyPayment(1_000_000, 6.9, 60)
        val plan = LoanMaths.overpay(1_000_000, 6.9, monthly, 5_000)!!
        assertTrue(plan.monthsSooner in 10..16)
        assertTrue(plan.interestSavedMinor > 30_000)
    }
}
