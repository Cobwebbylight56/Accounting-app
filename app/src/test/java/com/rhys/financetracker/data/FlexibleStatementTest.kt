package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.FlexibleStatementReader
import com.rhys.financetracker.data.importer.PdfStatementParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Statements laid out every way but the usual one. */
class FlexibleStatementTest {

    private val today = LocalDate.of(2026, 10, 10)

    private fun read(text: String) = FlexibleStatementReader.parse(text.trimIndent().lines(), today)

    private fun PdfStatementParser.Row.signed(): Long = (moneyInMinor ?: 0L) - (moneyOutMinor ?: 0L)

    @Test
    fun `month first with pound signs and a balance - Revolut`() {
        val rows = read(
            """
            Account statement
            Date Description Money out Money in Balance
            Opening balance £852.60
            Sep 27, 2026 Tesco Stores £12.50 £840.10
            Sep 28, 2026 Payment from H Evans £50.00 £890.10
            Sep 29, 2026 Spotify £11.99 £878.11
            """,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29)), rows.map { it.date })
        assertEquals(listOf(-1_250L, 5_000L, -1_199L), rows.map { it.signed() })
        assertEquals("Tesco Stores", rows.first().description)
        assertTrue(rows.all { it.problem == null })
    }

    @Test
    fun `two dates and a minus for a payment - an Amex card`() {
        val rows = read(
            """
            Credit limit £3,000.00  Minimum payment £25.00
            Sep 27 Sep 26 EBAY O*02-14164 LONDON 130.89
            Oct 02 Oct 02 PAYMENT RECEIVED - THANK YOU -50.00
            Oct 03 Oct 01 AUDIBLE UK 8.99
            """,
        )
        assertEquals(3, rows.size)
        assertEquals(LocalDate.of(2026, 9, 27), rows[0].date)
        assertEquals(listOf(-13_089L, 5_000L, -899L), rows.map { it.signed() })
        assertEquals("EBAY O*02-14164 LONDON", rows[0].description)
    }

    @Test
    fun `the date at the end of the line`() {
        val rows = read(
            """
            TESCO STORES 3294 12.50 27/09/2026
            SALARY ACME LTD 1,862.23 CR 28/09/2026
            SHELL FILLING STN 61.40 29/09/2026
            """,
        )
        assertEquals(listOf(-1_250L, 186_223L, -6_140L), rows.map { it.signed() })
        assertEquals("SALARY ACME LTD", rows[1].description)
    }

    @Test
    fun `a date heading the payments under it`() {
        val rows = read(
            """
            Monday 27 September 2026
            TESCO STORES 12.50
            COSTA COFFEE 3.10
            Tuesday 28 September 2026
            ACME LTD SALARY 1,862.23 CR
            """,
        )
        assertEquals(listOf(LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 27), LocalDate.of(2026, 9, 28)), rows.map { it.date })
        assertEquals(listOf("TESCO STORES", "COSTA COFFEE", "ACME LTD SALARY"), rows.map { it.description })
        assertEquals(186_223L, rows.last().moneyInMinor)
    }

    @Test
    fun `the payee and the amount on the lines under the date`() {
        val rows = read(
            """
            27/09/2026 Card payment
            TESCO STORES 3294
            £12.50
            28/09/2026 Direct debit
            SKY DIGITAL
            £35.00
            """,
        )
        assertEquals(listOf("Card payment TESCO STORES 3294", "Direct debit SKY DIGITAL"), rows.map { it.description })
        assertEquals(listOf(-1_250L, -3_500L), rows.map { it.signed() })
    }

    @Test
    fun `ISO dates and a trailing minus`() {
        val rows = read(
            """
            2026-09-27 Spotify 11.99-
            2026-09-28 Refund Amazon 20.00
            """,
        )
        assertEquals(listOf(-1_199L, 2_000L), rows.map { it.signed() })
    }

    @Test
    fun `what a statement says about itself is left out`() {
        val rows = read(
            """
            Statement period 01 Sep 2026 to 30 Sep 2026
            Payment due date 25 Oct 2026 Minimum payment £15.49
            Credit limit £800.00
            Purchase rate 25.69%
            27 Sep 2026 Tesco 12.50
            28 Sep 2026 Costa 3.10
            Your new balance £15.60
            Page 1 of 2
            """,
        )
        assertEquals(listOf("Tesco", "Costa"), rows.map { it.description })
    }

    @Test
    fun `an unusual layout is read when the usual reader finds nothing`() {
        val lines = """
            Your statement
            Sep 27, 2026 Tesco Stores £12.50 £840.10
            Sep 28, 2026 Payment from H Evans £50.00 £890.10
            Sep 29, 2026 Spotify £11.99 £878.11
        """.trimIndent().lines()
        assertNotNull(PdfStatementParser.toSheet(lines, "Revolut"))
    }

    @Test
    fun `a letter with a date and a figure in it is still not a statement`() {
        val lines = """
            Dear Mr Evans
            Your new card will arrive by 12 October 2026.
            Thank you for being with us since 2019.
        """.trimIndent().lines()
        assertNull(PdfStatementParser.toSheet(lines, "Letter"))
    }

    @Test
    fun `dates in every common shape`() {
        fun date(text: String) = FlexibleStatementReader.dateIn(text, 2026, today)?.first
        assertEquals(LocalDate.of(2026, 1, 27), date("27/01/2026 x"))
        assertEquals(LocalDate.of(2026, 1, 27), date("27-Jan-26 x"))
        assertEquals(LocalDate.of(2026, 1, 27), date("27th January 2026 x"))
        assertEquals(LocalDate.of(2026, 1, 27), date("Jan 27, 2026 x"))
        assertEquals(LocalDate.of(2026, 1, 27), date("2026-01-27 x"))
        assertEquals(LocalDate.of(2026, 1, 27), date("27 Jan x"))
        // "27 Jan 26 Jan": the second is a date, not the year.
        assertEquals(LocalDate.of(2026, 1, 27), date("27 Jan 26 Jan EBAY"))
        assertNull(date("TESCO 12.50"))
    }
}
