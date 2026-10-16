package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.PdfStatementParser
import com.rhys.financetracker.data.importer.PdfStatementReader
import com.rhys.financetracker.data.importer.RepeatedHeadingsReader
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lloyds' statements downloaded from the app: the headings printed again
 * over every payment, with hidden screen-reader words jumbled into them, and
 * a statement for a stretch with no payments at all.
 */
class LloydsStatementTest {

    private fun lines(name: String): List<String> =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("statements/$name")) { "missing $name" }
            .bufferedReader().readLines()

    @Test
    fun `every payment is read, which way it went from its own heading`() {
        val rows = RepeatedHeadingsReader.parse(lines("lloyds-row-headings.txt"))!!
        assertEquals(3, rows.size)

        val wage = rows[0]
        assertEquals(LocalDate.of(2026, 2, 5), wage.date)
        assertEquals(186_223L, wage.moneyInMinor)
        assertNull(wage.moneyOutMinor)
        assertEquals(197_223L, wage.balanceMinor)
        // The jumbled heading scraps are left out of the payee.
        assertEquals("ACME INDUSTRIAL BANK CREDIT", wage.description)

        val penny = rows[1]
        assertEquals(LocalDate.of(2026, 2, 6), penny.date)
        assertEquals(1L, penny.moneyOutMinor)
        assertEquals(197_222L, penny.balanceMinor)
        assertEquals("A CUSTOMER FASTER PAYMENT", penny.description)

        val rest = rows[2]
        assertEquals(186_222L, rest.moneyOutMinor)
        assertEquals(11_000L, rest.balanceMinor)
        assertEquals("A CUSTOMER FASTER PAYMENT", rest.description)

        // Every one adds up against the balance.
        assertTrue(rows.all { it.problem == null })
    }

    @Test
    fun `the summary at the top is not taken for payments`() {
        val sheet = PdfStatementParser.toSheet(lines("lloyds-row-headings.txt"), "Lloyds")!!
        val body = sheet.rows.drop(1)
        assertEquals(3, body.size)
        assertTrue(body.none { row -> row.any { "Balance on" in it || "Money In" in it } })
        assertEquals(listOf("2026-02-05", "2026-02-06", "2026-02-06"), body.map { it[0] })
    }

    @Test
    fun `a statement laid out the usual way is left to the usual reader`() {
        assertNull(
            RepeatedHeadingsReader.parse(
                listOf(
                    "Date Description Type In (£) Out (£) Balance (£)",
                    "01 Jun 26 SAVER FPI 250.00 1500.00",
                ),
            ),
        )
    }

    @Test
    fun `what an older version read wrongly is recognised to put right`() {
        // The summary at the top, taken for two payments.
        assertTrue(RepeatedHeadingsReader.isSummary("Money In. . Balance on . ."))
        assertTrue(RepeatedHeadingsReader.isSummary("Money Out. . Balance on . ."))
        assertTrue(!RepeatedHeadingsReader.isSummary("MONEYBOX SAVINGS"))
        // A real payment with heading scraps in it: tidied to what reading it again gives.
        assertEquals("A CUSTOMER FASTER PAYMENT", RepeatedHeadingsReader.tidy(". A CUSTOMER. FPO Moneyb Ilna n(k£.)"))
        // Anything else is left alone.
        assertNull(RepeatedHeadingsReader.tidy("AMAZON (uk) MARKETPLACE"))
        assertNull(RepeatedHeadingsReader.tidy("TESCO STORES 3294"))
    }

    @Test
    fun `a statement with no payments says so, not that it can't be read`() {
        val text = lines("lloyds-no-transactions.txt")
        assertNull(PdfStatementParser.toSheet(text, "Lloyds"))
        val message = PdfStatementReader.unreadable(text.joinToString("\n"))
        assertTrue(message, message.startsWith("That statement (01 October 2026 to 16 October 2026) has no payments in it"))
    }
}
