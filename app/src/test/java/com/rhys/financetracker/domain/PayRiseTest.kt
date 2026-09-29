package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.income.PayRise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayRiseTest {

    @Test
    fun `a rise is taken in whatever shape it was given`() {
        val now = 2_745_576L // £27,455.76
        assertEquals(2_827_943L, PayRise.newGross(now, PayRise.Given.PERCENT, 3.0))
        assertEquals(2_895_576L, PayRise.newGross(now, PayRise.Given.AMOUNT, 1_500.0))
        assertEquals(2_900_000L, PayRise.newGross(now, PayRise.Given.NEW_PAY, 29_000.0))
        // A percentage or an amount needs something to add it to.
        assertNull(PayRise.newGross(null, PayRise.Given.PERCENT, 3.0))
        assertEquals(2_900_000L, PayRise.newGross(null, PayRise.Given.NEW_PAY, 29_000.0))
    }

    @Test
    fun `take-home after a rise keeps the same share of pay when not given`() {
        assertEquals(2_360_000L, PayRise.estimateNet(3_000_000L, 2_400_000L, 2_950_000L))
        assertNull(PayRise.estimateNet(null, 2_400_000L, 2_950_000L))
    }
}
