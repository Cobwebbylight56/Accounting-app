package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.PdfStatementParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A PDF gives lines of text with nothing marking which figure is the amount,
 * which is the balance, or which way the money went. The running balance is
 * what settles it, and these cover both that working and its failing safely.
 */
class PdfStatementParserTest {

    private val statement = listOf(
        "Your statement",
        "Account 12345678            Sort code 00-00-00",
        "Date        Description              Paid out   Paid in    Balance",
        "01 Mar 2026 Balance brought forward                        2,000.00",
        "01 Mar 2026 TESCO STORES 3294           42.15              1,957.85",
        "02 Mar 2026 SALARY ACME LTD                     1,862.23   3,820.08",
        "03 Mar 2026 SHELL FILLING STN           61.40              3,758.68",
    )

    @Test
    fun `reads the transaction lines and ignores the rest`() {
        val rows = PdfStatementParser.parse(statement)
        // Headings, the account line and the brought-forward line are not
        // transactions; the three payments are.
        assertEquals(3, rows.size)
        assertEquals(LocalDate.of(2026, 3, 1), rows[0].date)
        assertEquals("TESCO STORES 3294", rows[0].description)
    }

    @Test
    fun `the brought forward line sets the opening balance without becoming an entry`() {
        // Counted as a payment it would both invent an entry and leave the
        // first real row with nothing to check itself against.
        val rows = PdfStatementParser.parse(statement)
        assertTrue(rows.none { it.description.contains("brought forward", ignoreCase = true) })
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertNull(rows[0].problem)
    }

    @Test
    fun `the balance decides which way the money went`() {
        val rows = PdfStatementParser.parse(statement)

        // Down 42.15, so money out — regardless of which column it sat in.
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertNull(rows[0].moneyInMinor)

        // Up 1,862.23, so money in.
        assertEquals(186223L, rows[1].moneyInMinor)
        assertNull(rows[1].moneyOutMinor)

        // And down again.
        assertEquals(6140L, rows[2].moneyOutMinor)
    }

    @Test
    fun `the balance is kept alongside each row`() {
        val rows = PdfStatementParser.parse(statement)
        assertEquals(195785L, rows[0].balanceMinor)
        assertEquals(382008L, rows[1].balanceMinor)
        assertEquals(375868L, rows[2].balanceMinor)
    }

    @Test
    fun `a line whose figures do not match the balance is flagged`() {
        // 42.15 out should leave 1,957.85 but the statement says 1,900.00, so
        // something on this line was misread. Better shown than assumed.
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Mar 2026 Balance brought forward      2,000.00",
                "02 Mar 2026 TESCO STORES 3294   42.15    1,900.00",
            ),
        )
        assertEquals(1, rows.size)
        assertNotNull(rows[0].problem)
        // Naming the balance matters: the line also gave no clue which way the
        // money went, and saying only that hides the fact that there was a
        // check available and it failed.
        assertTrue(rows[0].problem!!.contains("balance"))
        // Still read. Returning no amount left the row with no value at all,
        // which the importer could only treat as unreadable — silently
        // dropping a real transaction rather than showing a doubtful one.
        assertEquals(4215L, rows[0].moneyOutMinor)
    }

    @Test
    fun `a reference number in the description is not mistaken for money`() {
        // "3294" has no pence, so it stays part of the payee.
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Mar 2026 Balance brought forward      2,000.00",
                "02 Mar 2026 TESCO STORES 3294   42.15    1,957.85",
            ),
        )
        assertEquals("TESCO STORES 3294", rows[0].description)
        assertEquals(4215L, rows[0].moneyOutMinor)
    }

    @Test
    fun `reads both the numeric and the written date`() {
        assertEquals(
            LocalDate.of(2026, 3, 1),
            PdfStatementParser.leadingDate("01/03/2026 TESCO 42.15"),
        )
        assertEquals(
            LocalDate.of(2026, 3, 1),
            PdfStatementParser.leadingDate("01 Mar 2026 TESCO 42.15"),
        )
        assertEquals(
            LocalDate.of(2026, 3, 1),
            PdfStatementParser.leadingDate("1 March 2026 TESCO 42.15"),
        )
    }

    @Test
    fun `a line with no date at the front is not a transaction`() {
        assertNull(PdfStatementParser.leadingDate("Balance brought forward 2,000.00"))
        assertNull(PdfStatementParser.leadingDate("Page 1 of 3"))
        // A date with no year would have to be guessed, and a wrong year files
        // the transaction in the wrong month.
        assertNull(PdfStatementParser.leadingDate("01 Mar TESCO 42.15"))
    }

    @Test
    fun `only a trailing run of figures counts as money`() {
        assertEquals(listOf(4215L, 195785L), PdfStatementParser.trailingAmounts(
            "01 Mar 2026 TESCO STORES 3294 42.15 1,957.85",
        ))
        // Nothing at the end that looks like money.
        assertEquals(emptyList<Long>(), PdfStatementParser.trailingAmounts("Page 1 of 3"))
    }

    @Test
    fun `a bracketed or trailing minus is read as money out`() {
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Mar 2026 Balance brought forward  2,000.00",
                "02 Mar 2026 TESCO   (42.15)          1,957.85",
            ),
        )
        assertEquals(4215L, rows[0].moneyOutMinor)
    }

    @Test
    fun `a balance printed only once a day still reads every row`() {
        // The likeliest reason half a real statement came back unimportable:
        // rows between the daily balances had nothing to reconcile against, so
        // they were returned with no amount at all.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement 1 March 2026 to 31 March 2026",
                "Date   Description                Paid out   Paid in    Balance",
                "01 Mar Balance brought forward                          2,000.00",
                "02 Mar TESCO STORES 3294             42.15",
                "02 Mar GREGGS PLC 1042                3.60              1,954.25",
                "03 Mar SALARY ACME LTD                        1,862.23",
                "03 Mar SHELL FILLING STN             61.40              3,755.08",
            ),
        )
        assertEquals(4, rows.size)
        assertTrue(rows.all { (it.moneyOutMinor ?: it.moneyInMinor) != null })
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertEquals(360L, rows[1].moneyOutMinor)
        // Read from its column: the balance said nothing about this row.
        assertEquals(186223L, rows[2].moneyInMinor)
        assertEquals(6140L, rows[3].moneyOutMinor)
        // And none of them is flagged. The balance on the Greggs line is two
        // transactions further on than the last one printed, so comparing the
        // two would fail — and marking every second row doubtful on a
        // perfectly ordinary statement teaches you to ignore the warning.
        assertTrue(rows.all { it.problem == null })
    }

    @Test
    fun `a statement with no balance column still reads both directions`() {
        // With no balance anywhere, the column a figure sits in is the only
        // evidence — and without it every credit was read as a payment.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date        Description              Paid out   Paid in",
                "01 Mar 2026 TESCO STORES 3294           42.15",
                "02 Mar 2026 SALARY ACME LTD                     1,862.23",
                "03 Mar 2026 SHELL FILLING STN           61.40",
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertEquals(186223L, rows[1].moneyInMinor)
        assertEquals(6140L, rows[2].moneyOutMinor)
    }

    @Test
    fun `an opening balance is never imported as a payment`() {
        // On a statement whose columns are not padded out there is no useful
        // position to go on, so the wording has to settle it. Read as a
        // payment it invents an entry the size of the whole balance.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date Reference Amount Balance",
                "01 Mar 2026 OPENING BALANCE 2,000.00",
                "02 Mar 2026 TESCO STORES 3294 -42.15 1,957.85",
                "03 Mar 2026 SALARY ACME LTD 1,862.23 3,820.08",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertEquals(186223L, rows[1].moneyInMinor)
    }

    @Test
    fun `a year from the statement heading dates rows that have none`() {
        // Lloyds, Halifax and others print the year once and then date each row
        // "01 Mar". Without this every one of those rows is dropped, which is
        // what made a real statement come back empty.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement period 1 March 2026 to 31 March 2026",
                "01 Mar Balance brought forward           2,000.00",
                "02 Mar TESCO STORES 3294        42.15    1,957.85",
                "03 Mar SALARY ACME LTD       1,862.23    3,820.08",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 3, 2), rows[0].date)
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertEquals(186223L, rows[1].moneyInMinor)
    }

    @Test
    fun `a row without a year is dropped when the document never states one`() {
        // Guessing a year files transactions into the wrong one, which is
        // worse than declining them.
        assertNull(PdfStatementParser.inferYear(listOf("01 Mar TESCO 42.15")))
        assertEquals(
            emptyList<PdfStatementParser.Row>(),
            PdfStatementParser.parse(listOf("01 Mar TESCO 42.15 1,957.85")),
        )
    }

    @Test
    fun `a row repeating the previous date is still a transaction`() {
        // Statements print the date only when it changes.
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Mar 2026 Balance brought forward       2,000.00",
                "02 Mar 2026 TESCO STORES 3294    42.15    1,957.85",
                "            GREGGS PLC 1042      3.60     1,954.25",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(LocalDate.of(2026, 3, 2), rows[1].date)
        assertEquals("GREGGS PLC 1042", rows[1].description)
        assertEquals(360L, rows[1].moneyOutMinor)
    }

    @Test
    fun `a debit or credit letter after the figure is not read as part of it`() {
        // Barclays and others mark direction with a letter rather than a column.
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Mar 2026 Balance brought forward        2,000.00",
                "02 Mar 2026 TESCO STORES 3294    42.15 D   1,957.85",
                "03 Mar 2026 SALARY ACME LTD   1,862.23 CR  3,820.08",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(4215L, rows[0].moneyOutMinor)
        assertEquals(186223L, rows[1].moneyInMinor)
    }

    @Test
    fun `the latest year in the document is the one used`() {
        // A statement spanning a year end names both; most of its rows sit in
        // the later one.
        assertEquals(
            2026,
            PdfStatementParser.inferYear(
                listOf("Statement period 28 December 2025 to 27 January 2026"),
            ),
        )
    }

    @Test
    fun `a document with no transactions is not offered as a statement`() {
        assertNull(
            PdfStatementParser.toSheet(
                listOf("Dear Mr Evans", "Thank you for your recent enquiry.", "Yours faithfully"),
                "letter",
            ),
        )
    }

    @Test
    fun `the sheet it produces looks like a CSV export`() {
        val sheet = PdfStatementParser.toSheet(statement, "statement")
        assertNotNull(sheet)
        assertEquals(PdfStatementParser.HEADINGS, sheet!!.rows.first())
        // Header plus the three transactions; the brought-forward line is not one.
        assertEquals(4, sheet.rows.size)
        assertEquals("2026-03-01", sheet.rows[1][0])
        assertEquals("TESCO STORES 3294", sheet.rows[1][1])
    }

    @Test
    fun `a statement that puts paid-in first is not read inside out`() {
        // What went wrong on a real statement: with no balance to prove which
        // column was which, the reader assumed paid-out came first, and every
        // figure that was not in that column was taken for money in. A whole
        // December of direct debits and card payments arrived as income —
        // £25,000 in against £2,900 out on a current account.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement 1 December 2025 to 31 December 2025",
                "Date        Description                    Paid in    Paid out",
                "16 Dec 2025 Direct debit SAMSUNGFIN                     12.99",
                "17 Dec 2025 TESCO PAY AT PUMP 383                       30.00",
                "18 Dec 2025 Direct Debit - First Payment                60.79",
                "19 Dec 2025 Direct debit CAPITAL ONE                     4.99",
                "20 Dec 2025 SALARY ACME LTD               1862.23",
            ),
        )
        assertEquals(5, rows.size)
        assertEquals(10_877L, rows.sumOf { it.moneyOutMinor ?: 0L })
        assertEquals(186_223L, rows.sumOf { it.moneyInMinor ?: 0L })
    }

    @Test
    fun `a figure not in the paid-out column is never money in on that alone`() {
        // The rule that matters: money in has to be positively evidenced.
        // Absence of evidence used to be treated as proof of income, which is
        // what inverted the statement above.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date        Description                    Paid in    Paid out",
                "16 Dec 2025 SOMETHING UNRECOGNISED                      12.99",
                "17 Dec 2025 ANOTHER MYSTERY                             30.00",
                "18 Dec 2025 A THIRD ONE                                 60.79",
            ),
        )
        assertTrue(rows.all { it.moneyInMinor == null })
        assertTrue(rows.all { it.moneyOutMinor != null })
    }

    @Test
    fun `an amount over a thousand without a comma is money`() {
        // "1862.23" was not matched as money at all, so the row carrying it was
        // not misread — it was dropped from the import without a word. Plenty
        // of statements print salaries and transfers with no separator.
        assertEquals(listOf(186223L), PdfStatementParser.trailingAmounts("20 Dec 2025 SALARY 1862.23"))
        assertEquals(listOf(1234567L), PdfStatementParser.trailingAmounts("20 Dec 2025 X 12345.67"))
        // Separated groups must still be groups of three.
        assertEquals(emptyList<Long>(), PdfStatementParser.trailingAmounts("20 Dec 2025 X 1,23.45"))
    }

    @Test
    fun `a debit or credit letter settles the direction the balance cannot`() {
        val rows = PdfStatementParser.parse(
            listOf(
                "01 Dec 2025 Balance brought forward 2000.00",
                "16 Dec 2025 Direct debit SAMSUNGFIN 12.99 D",
                "20 Dec 2025 SALARY ACME LTD 1862.23 CR",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals(1299L, rows[0].moneyOutMinor)
        assertEquals(186223L, rows[1].moneyInMinor)
        assertEquals("CR", PdfStatementParser.trailingMarker("20 Dec 2025 SALARY 1862.23 CR"))
        assertNull(PdfStatementParser.trailingMarker("20 Dec 2025 SALARY 1862.23"))
    }

    @Test
    fun `what a line calls itself decides when nothing else can`() {
        assertEquals(true, PdfStatementParser.directionFromWording("Direct debit CAPITAL ONE"))
        assertEquals(true, PdfStatementParser.directionFromWording("Contactless Payment"))
        assertEquals(true, PdfStatementParser.directionFromWording("ATM CASH WITHDRAWAL"))
        assertEquals(false, PdfStatementParser.directionFromWording("SALARY ACME LTD"))
        assertEquals(false, PdfStatementParser.directionFromWording("REFUND ASOS"))
        // Paying a credit card is money leaving, however much it says credit.
        assertEquals(
            true,
            PdfStatementParser.directionFromWording("DIRECT DEBIT CAPITAL ONE CREDIT CARD"),
        )
        // And an ordinary shop name says nothing either way.
        assertNull(PdfStatementParser.directionFromWording("TESCO STORES 3294"))
    }

    @Test
    fun `the busiest column is not a balance when calling it one empties the file`() {
        // A statement with no running total has its amounts in the rightmost
        // column. Taking that for a balance turns every row into a balance
        // line with no entry on it, and they vanish from the import.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date        Description                    Paid in    Paid out",
                "16 Dec 2025 Direct debit SAMSUNGFIN                     12.99",
                "17 Dec 2025 TESCO PAY AT PUMP 383                       30.00",
                "18 Dec 2025 Direct Debit - First Payment                60.79",
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(1299L, rows[0].moneyOutMinor)
    }
}
