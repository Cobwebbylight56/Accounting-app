package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.FlexibleStatementReader
import com.rhys.financetracker.data.importer.PdfStatementParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two credit card statements, as their PDFs read out. */
class CardStatementTest {

    private fun lines(name: String): List<String> =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("statements/$name")) { "missing $name" }
            .bufferedReader().readLines()

    @Test
    fun `Capital One - the year only in two digits, and the rows read`() {
        val text = lines("capital-one.txt")
        // "Statement date 26 February 26", "as at 26/02/26": nowhere in full.
        assertEquals(2026, PdfStatementParser.inferYear(text, thisYear = 2026))
        val rows = PdfStatementParser.parse(text)
        val ebay = rows.single()
        assertEquals(LocalDate.of(2026, 1, 27), ebay.date)
        // The day it was bought, " on 26 Jan", is not part of the payee.
        assertEquals("Ebay O*02-14164-34703 London GBR", ebay.description)
        assertEquals(13_089L, ebay.moneyOutMinor)
        assertNull(ebay.moneyInMinor)
        // What's owed after it — not the "Previous Balance 0.00" printed under it.
        assertEquals(13_089L, ebay.balanceMinor)
        assertNull(ebay.problem)
        assertNotNull(PdfStatementParser.toSheet(text, "Capital One"))
    }

    @Test
    fun `Zopa - spending out, payments in, interest and fees kept`() {
        val rows = PdfStatementParser.parse(lines("zopa.txt"))
        assertEquals(7, rows.size)
        assertEquals(listOf(4_999L, 2_706L, 899L, 1_200L, 162L), rows.mapNotNull { it.moneyOutMinor })
        assertEquals(listOf(999L, 1_200L), rows.mapNotNull { it.moneyInMinor })
        assertEquals("Total purchase interest", rows.last().description)
        assertEquals(8_766L, rows.last().balanceMinor)
        assertTrue(rows.all { it.problem == null })
    }

    @Test
    fun `a column's totals are not a payment`() {
        val text = lines("capital-one.txt")
        assertTrue(PdfStatementParser.parse(text).none { "TOTALS" in it.description })
    }

    @Test
    fun `Capital One - paying it off by Direct Debit is money in, and the summary is not a row`() {
        val text = lines("capital-one-repaid.txt")
        val rows = PdfStatementParser.parse(text)
        assertEquals(
            listOf("Tesco Stores 3294 Newport GBR", "Direct Debit Payment - Thank You", "Amazon.co.uk*AB12C London GBR"),
            rows.map { it.description },
        )
        assertEquals(listOf(4_560L, null, 4_350L), rows.map { it.moneyOutMinor })
        assertEquals(listOf(null, 3_999L, null), rows.map { it.moneyInMinor })
        val sheet = requireNotNull(PdfStatementParser.toSheet(text, "Capital One"))
        assertEquals(3, sheet.rows.size - 1) // under the headings
    }

    @Test
    fun `Capital One - the any-layout reader finds only the payments too`() {
        val rows = FlexibleStatementReader.parse(lines("capital-one-repaid.txt"), LocalDate.of(2026, 4, 1))
        // Not "Protect Your Identity", "is", "New transactions", "Payments
        // received", "Mr A Customer at" or "Dear Mr A Customer, Your Direct Debit".
        assertEquals(
            listOf("Tesco Stores 3294 Newport GBR", "Direct Debit Payment - Thank You", "Amazon.co.uk*AB12C London GBR"),
            rows.map { it.description },
        )
        assertEquals(listOf(4_560L, null, 4_350L), rows.map { it.moneyOutMinor })
        assertEquals(listOf(null, 3_999L, null), rows.map { it.moneyInMinor })
    }

    @Test
    fun `a card's payment wording`() {
        assertEquals(false, PdfStatementParser.cardDirectionFromWording("Direct Debit Payment - Thank You"))
        assertEquals(false, PdfStatementParser.cardDirectionFromWording("PAYMENT RECEIVED - THANK YOU"))
        assertNull(PdfStatementParser.cardDirectionFromWording("Tesco Stores"))
    }

    @Test
    fun `a two-digit year is only read from a whole date`() {
        // "26 Jan 130.89" is a date and an amount, with no year in it.
        assertNull(PdfStatementParser.inferYear(listOf("27 Jan EBAY on 26 Jan 130.89"), thisYear = 2026))
        assertEquals(2025, PdfStatementParser.inferYear(listOf("Statement date 3 March 25"), thisYear = 2026))
    }
}
