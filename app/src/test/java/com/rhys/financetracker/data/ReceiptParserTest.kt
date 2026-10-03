package com.rhys.financetracker.data

import com.rhys.financetracker.data.receipts.ReceiptParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Text as it comes back from reading a photo of a receipt or a screenshot. */
class ReceiptParserTest {

    private val today = LocalDate.of(2026, 10, 3)

    @Test
    fun `a supermarket receipt`() {
        val text = """
            TESCO
            Wakefield Extra
            Tel 0345 677 9001
            VAT No GB 220 4302 31
            MILK 4 PINTS           1.65
            BREAD WHOLEMEAL        1.40
            CHICKEN BREASTS        4.75
            Clubcard savings      -0.50
            SUBTOTAL               7.80
            TOTAL                  7.30
            VISA                   7.30
            CARD NUMBER ************4321
            03/10/26 14:22  ST:2311 TILL 4
            Thank you for shopping at Tesco
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Tesco", reading.shop)
        assertEquals(730L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 3), reading.date)
        assertEquals("4321", reading.cardEnding)
    }

    @Test
    fun `total on the line under it`() {
        val text = """
            COSTA COFFEE
            12 Westgate, Wakefield
            Flat White Regular  3.10
            Croissant           2.25
            Total
            £5.35
            Contactless
            2 Oct 2026
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Costa Coffee", reading.shop)
        assertEquals(535L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 2), reading.date)
    }

    @Test
    fun `an online order screenshot`() {
        val text = """
            10:23
            Order details
            Amazon.co.uk
            Ordered on 1 October 2026
            Phone case                £9.99
            Charging cable            £14.00
            Postage                    £0.00
            Order total: £23.99
        """.trimIndent()
        val reading = ReceiptParser.parse(text, today)
        assertEquals("Amazon", reading.shop)
        assertEquals(2_399L, reading.totalMinor)
        assertEquals(LocalDate.of(2026, 10, 1), reading.date)
    }

    @Test
    fun `savings and VAT are not the total`() {
        val text = """
            B&Q
            TOTAL SAVINGS  12.00
            VAT 20%         8.00
            AMOUNT DUE     48.00
        """.trimIndent()
        assertEquals(4_800L, ReceiptParser.parse(text, today).totalMinor)
    }

    @Test
    fun `thousands`() {
        assertEquals(124_999L, ReceiptParser.parse("Currys\nTOTAL £1,249.99", today).totalMinor)
    }

    @Test
    fun `nothing readable gives nothing`() {
        val reading = ReceiptParser.parse("", today)
        assertNull(reading.shop)
        assertNull(reading.totalMinor)
        assertNull(reading.date)
    }

    @Test
    fun `a date in the future is a misreading`() {
        assertNull(ReceiptParser.parse("Shop\nTOTAL 5.00\n03/10/29", today).date)
    }
}
