package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.PriceRise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Netflix at £10.99, then £12.99: a rise. A bill that moves about is not. */
class PriceRiseTest {

    @Test
    fun `a steady price then a higher one is a rise`() {
        assertEquals(10_99L, PriceRise.from(listOf(10_99, 10_99, 10_99, 12_99)))
        assertEquals(10_99L, PriceRise.from(listOf(10_99, 10_99, 12_99, 12_99, 12_99)))
    }

    @Test
    fun `old news, cheaper, or never steady is no rise`() {
        assertNull(PriceRise.from(listOf(10_99, 10_99, 12_99, 12_99, 12_99, 12_99)))
        assertNull(PriceRise.from(listOf(12_99, 12_99, 10_99)))
        assertNull(PriceRise.from(listOf(41_20, 38_75, 52_10)))
        assertNull(PriceRise.from(listOf(10_99, 10_99, 10_99)))
        assertNull(PriceRise.from(listOf(10_99, 12_99)))
    }
}
