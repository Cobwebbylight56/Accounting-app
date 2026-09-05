package com.rhys.financetracker.core

import com.rhys.financetracker.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money is stored in pence and parsed from whatever the user types, so these
 * tests cover the awkward input a real person produces — currency symbols,
 * thousands separators, European decimal commas and accountancy brackets.
 */
class MoneyTest {

    @Test
    fun `parses a plain decimal`() {
        assertEquals(2499L, Money.parseOrNull("24.99"))
    }

    @Test
    fun `parses a whole number as pounds`() {
        assertEquals(2500L, Money.parseOrNull("25"))
    }

    @Test
    fun `parses a currency symbol and thousands separator`() {
        assertEquals(123456L, Money.parseOrNull("£1,234.56"))
    }

    @Test
    fun `parses a European style decimal comma`() {
        assertEquals(123456L, Money.parseOrNull("1.234,56"))
    }

    @Test
    fun `parses accountancy brackets as negative`() {
        assertEquals(-1230L, Money.parseOrNull("(12.30)"))
    }

    @Test
    fun `parses an explicit minus sign`() {
        assertEquals(-7940L, Money.parseOrNull("-79.40"))
    }

    @Test
    fun `rounds a third decimal place half up`() {
        assertEquals(1235L, Money.parseOrNull("12.345"))
    }

    @Test
    fun `rejects text that is not an amount`() {
        assertNull(Money.parseOrNull("not money"))
        assertNull(Money.parseOrNull(""))
        assertNull(Money.parseOrNull(null))
    }

    @Test
    fun `formats plain output without a currency symbol`() {
        assertEquals("1234.56", Money.formatPlain(123456L))
        assertEquals("-79.40", Money.formatPlain(-7940L))
        assertEquals("0.00", Money.formatPlain(0L))
    }

    @Test
    fun `converts major units without floating point drift`() {
        // 1862.23 is not exactly representable as a double; the conversion must
        // still land on 186223 pence.
        assertEquals(186223L, Money.fromMajor(1862.23))
        assertEquals(155814L, Money.fromMajor(1558.14))
    }

    @Test
    fun `splits an amount without losing a penny`() {
        val parts = Money.split(1000L, 3)
        assertEquals(3, parts.size)
        assertEquals(1000L, parts.sum())
        assertEquals(listOf(334L, 333L, 333L), parts)
    }

    @Test
    fun `splits a negative amount without losing a penny`() {
        val parts = Money.split(-1000L, 3)
        assertEquals(-1000L, parts.sum())
    }

    @Test
    fun `calculates a percentage`() {
        assertEquals(500L, Money.percentOf(10_000L, 5.0))
    }

    @Test
    fun `formats compactly for charts`() {
        assertEquals("£1.2k", Money.formatCompact(123_456L))
        assertEquals("£45", Money.formatCompact(4_500L))
    }
}
