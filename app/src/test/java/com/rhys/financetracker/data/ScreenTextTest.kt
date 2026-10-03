package com.rhys.financetracker.data

import com.rhys.financetracker.data.receipts.ScreenText
import com.rhys.financetracker.data.receipts.ScreenText.Line
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {

    private val today = LocalDate.of(2026, 10, 3)

    /** A line of text at [left], [top], about as tall as screen text is. */
    private fun at(text: String, left: Int, top: Int, height: Int = 50) =
        Line(text, left, top, left + text.length * 22, top + height)

    /**
     * Google Wallet's list of payments on a card, as text recognition hands
     * it back: names and dates in one column, amounts in another, each on
     * its own line, in no reliable order.
     */
    private val wallet = listOf(
        at("3:58", 30, 20),
        at("TRANSPT WALES", 260, 315), at("£11.00", 705, 315),
        at("RAIL", 260, 375),
        at("2 Oct", 260, 430),
        at("ASDA PETROL 4275", 260, 585), at("£30.00", 690, 585),
        at("30 Sept", 260, 640),
        at("TESCO STORES 6231", 260, 803), at("£1.25", 727, 803),
        at("28 Sept", 260, 858),
        at("TESCO STORES 6231", 260, 1020), at("£1.25", 727, 1020),
        at("26 Sept", 260, 1075),
        at("MCDONALDS", 260, 1237), at("£19.07", 706, 1237),
        at("15 Sept", 260, 1292),
        at("Burger King", 260, 1450), at("£23.29", 700, 1450),
        at("Newport 2", 260, 1508),
        at("11 Sept", 260, 1565),
        at("TESCO STORES 6231", 260, 1720), at("£4.00", 712, 1720),
        at("9 Sept", 260, 1775),
        // Cut off at the bottom: no date showing.
        at("TESCO PFS 4198", 260, 1935), at("£47.92", 706, 1935),
    ).shuffled(java.util.Random(7))

    @Test
    fun `a Google Wallet list reads as one payment per row`() {
        val listed = ScreenText.listedPayments(wallet, today)
        assertEquals(7, listed.size)
        assertEquals(ScreenText.ListedPayment("TRANSPT WALES RAIL", 1_100, LocalDate.of(2026, 10, 2), false), listed[0])
        assertEquals(ScreenText.ListedPayment("ASDA PETROL 4275", 3_000, LocalDate.of(2026, 9, 30), false), listed[1])
        assertEquals(LocalDate.of(2026, 9, 28), listed[2].date)
        assertEquals(125L, listed[3].amountMinor)
        assertEquals("Burger King Newport 2", listed[5].payee)
        assertEquals(LocalDate.of(2026, 9, 9), listed[6].date)
    }

    @Test
    fun `rows put amounts back beside their names`() {
        val text = ScreenText.rowText(
            listOf(at("£7.30", 600, 402), at("TOTAL", 20, 400), at("TESCO", 20, 100), at("VISA", 20, 460), at("£7.30", 600, 462)),
        )
        assertEquals("TESCO\nTOTAL  £7.30\nVISA  £7.30", text)
    }

    @Test
    fun `a receipt is not a list`() {
        val receipt = listOf(
            at("TESCO", 20, 100),
            at("MILK", 20, 200), at("£1.65", 600, 200),
            at("BREAD", 20, 260), at("£1.40", 600, 260),
            at("TOTAL", 20, 320), at("£3.05", 600, 320),
        )
        assertTrue(ScreenText.listedPayments(receipt, today).isEmpty())
    }

    @Test
    fun `dates without a year are the latest one not ahead`() {
        assertEquals(LocalDate.of(2026, 10, 2), ScreenText.shortDate("2 Oct", today))
        assertEquals(LocalDate.of(2025, 12, 15), ScreenText.shortDate("15 Dec", today))
        assertEquals(LocalDate.of(2026, 9, 30), ScreenText.shortDate("Wed 30 Sept", today))
        assertEquals(LocalDate.of(2026, 10, 2), ScreenText.shortDate("Yesterday", today))
        assertEquals(LocalDate.of(2024, 3, 1), ScreenText.shortDate("1 March 2024", today))
    }
}
