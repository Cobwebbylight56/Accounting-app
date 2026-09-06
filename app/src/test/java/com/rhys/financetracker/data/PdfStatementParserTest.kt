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
        // The balance said nothing about this row and no column was proven, so
        // what the line calls itself decides it. A salary is money arriving.
        assertEquals(186223L, rows[2].moneyInMinor)
        assertEquals(6140L, rows[3].moneyOutMinor)
        // And none of them is flagged. The balance on the Greggs line is two
        // transactions further on than the last one printed, so comparing the
        // two would fail; the shop names are read as money out because that is
        // what a current account mostly holds. Neither is a fault worth
        // marking — doing so makes half an ordinary statement look doubtful
        // and teaches you to ignore the flag that does mean something.
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
        // The standard UK markings for a credit arriving.
        assertEquals(false, PdfStatementParser.directionFromWording("BGC ACME LTD"))
        assertEquals(false, PdfStatementParser.directionFromWording("FASTER PAYMENT RECEIVED"))
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

    /** Out, then In, then Balance — with the balance only at the end of a day. */
    private val endOfDayBalances = listOf(
        "Date        Description                     £ Out      £ In   £ Balance",
        "01 Dec 2025 Balance brought forward                            2,603.34",
        "02 Dec 2025 CARD PAYMENT A                   20.13             2,583.21",
        "03 Dec 2025 CARD PAYMENT B                   48.78             2,534.43",
        "04 Dec 2025 CARD PAYMENT C                    2.10",
        "05 Dec 2025 CARD PAYMENT D                   12.00",
        "06 Dec 2025 CARD PAYMENT E                   28.15",
        "07 Dec 2025 CARD PAYMENT F                    8.99             2,483.19",
        "08 Dec 2025 CARD PAYMENT G                    1.65",
        "09 Dec 2025 CARD PAYMENT H                   35.00",
        "10 Dec 2025 CARD PAYMENT I                   50.00             2,396.54",
        "11 Dec 2025 CARD PAYMENT J                    5.99             2,390.55",
        "12 Dec 2025 ACME ENGINEERING LTD                     1,858.33",
        "13 Dec 2025 CARD PAYMENT K                  183.95",
        "14 Dec 2025 INTEREST                                     1.27  4,066.20",
        "15 Dec 2025 J SMITH                                    500.00",
        "16 Dec 2025 CARD PAYMENT L                   14.85",
        "17 Dec 2025 CARD PAYMENT M                  174.29             4,377.06",
    )

    @Test
    fun `a balance printed only at the end of a day still proves the columns`() {
        // A real statement, and the one this reader kept getting wrong. The
        // balance column is on eight rows where the paid-out column is on
        // thirteen, so judging it by how often it is used rejected it — and
        // without a balance nothing proves direction, so every credit came
        // through as a payment.
        val rows = PdfStatementParser.parse(endOfDayBalances)

        assertEquals(16, rows.size)
        assertEquals(235_960L, rows.sumOf { it.moneyInMinor ?: 0L })
        assertEquals(58_588L, rows.sumOf { it.moneyOutMinor ?: 0L })
        // The three credits, and nothing else.
        assertEquals(
            listOf("ACME ENGINEERING LTD", "INTEREST", "J SMITH"),
            rows.filter { it.moneyInMinor != null }.map { it.description },
        )
    }

    @Test
    fun `a credit is found even where no balance sits beside it`() {
        // Only the paid-out column ever lands next to a printed balance here,
        // so only it is proved. The other money column is then paid-in by
        // elimination — there are two — rather than being left unknown and
        // defaulting every credit to a payment.
        val rows = PdfStatementParser.parse(endOfDayBalances)
        val salary = rows.single { it.description == "ACME ENGINEERING LTD" }
        assertEquals(185_833L, salary.moneyInMinor)
        assertNull(salary.moneyOutMinor)
    }

    @Test
    fun `a payee ending in a single letter is not read as a credit marker`() {
        // "C" and "D" are how some banks mark credit and debit, so a payee
        // whose last word is one letter had it stripped from the name and the
        // row turned into a credit. A marker only counts with a figure to its
        // left.
        val rows = PdfStatementParser.parse(endOfDayBalances)
        assertEquals("CARD PAYMENT C", rows[2].description)
        assertEquals(210L, rows[2].moneyOutMinor)
        assertNull(PdfStatementParser.trailingMarker("04 Dec 2025 CARD PAYMENT C 2.10"))
        // A real marker still is one.
        assertEquals("D", PdfStatementParser.trailingMarker("02 Dec 2025 TESCO 42.15 D 1,957.85"))
    }

    @Test
    fun `with no balance at all the busier money column is the paid-out one`() {
        // A statement is mostly outgoings. That is a fact about accounts
        // rather than a guess about layout, and it is the only thing left when
        // no running total is printed anywhere.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date        Description                    £ In      £ Out",
                "16 Dec 2025 CARD PAYMENT A                            12.99",
                "17 Dec 2025 CARD PAYMENT B                            30.00",
                "18 Dec 2025 CARD PAYMENT C                            60.79",
                "19 Dec 2025 CARD PAYMENT D                             4.99",
                "20 Dec 2025 ACME ENGINEERING LTD        1862.23",
            ),
        )
        assertEquals(5, rows.size)
        assertEquals(186_223L, rows.sumOf { it.moneyInMinor ?: 0L })
        assertEquals(10_877L, rows.sumOf { it.moneyOutMinor ?: 0L })
    }

    @Test
    fun `the balance settles a whole group of rows, not just one`() {
        // Your statement. The balance is printed on some rows only, and it has
        // moved by the *sum* of the rows since the last one — comparing it
        // against a single row reconciles a group of one and nothing else, so
        // almost every row fell through to guesswork and came out as a payment.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement 23 December 2025 to 22 January 2026",
                "Date        Description                   Money out  Money in   Balance",
                "20 Dec 2025 Balance brought forward                             4976.74",
                "21 Dec 2025 Contactless Payment               10.00",
                "22 Dec 2025 Direct debit UTILITY              71.18             4905.56",
                "23 Dec 2025 Contactless Payment               20.50             4885.06",
                "24 Dec 2025 Contactless Payment               17.50",
                "24 Dec 2025 SAINSBURYS S/MKTS                 23.97             4843.59",
                "29 Dec 2025 Contactless Payment               19.77",
                "29 Dec 2025 Contactless Payment                7.05             4816.77",
                "30 Dec 2025 Bank credit INDUSTRIAL AUTOMAT              1801.15  6617.92",
            ),
        )
        assertEquals(8, rows.size)
        // 4,885.06 to 4,843.59 is 41.47, which is 17.50 and 23.97 together. The
        // pair either side of it is the same shape.
        assertEquals(1750L, rows[3].moneyOutMinor)
        assertEquals(2397L, rows[4].moneyOutMinor)
        assertEquals(1977L, rows[5].moneyOutMinor)
        assertEquals(705L, rows[6].moneyOutMinor)
        // And the credit is a credit.
        assertEquals(180_115L, rows[7].moneyInMinor)
        assertNull(rows[7].moneyOutMinor)
        assertEquals(16_997L, rows.sumOf { it.moneyOutMinor ?: 0L })
    }

    @Test
    fun `bank credit is money arriving`() {
        assertEquals(
            false,
            PdfStatementParser.directionFromWording("Bank credit INDUSTRIAL AUTOMAT"),
        )
    }

    @Test
    fun `a statement over a year end dates December to the earlier year`() {
        // "23 December to 22 January" names both years and the later one is
        // right for most of the file — but not for December. Those rows came
        // out as next December, thirteen months after the payments they
        // describe, filing a month of spending in the wrong year.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement 23 December 2025 to 22 January 2026",
                "23 Dec Contactless Payment      21.16   4900.00",
                "31 Dec Direct debit UTILITY    183.95   4716.05",
                "05 Jan Contactless Payment       5.90   4710.15",
                "20 Jan SAINSBURYS S/MKTS        25.02   4685.13",
            ),
        )
        assertEquals(
            listOf(
                LocalDate.of(2025, 12, 23),
                LocalDate.of(2025, 12, 31),
                LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 20),
            ),
            rows.map { it.date },
        )
    }

    /**
     * A savings statement from a building society: Payments and Receipts
     * rather than money out and money in, a single movement in the period,
     * and the year printed once above the rows.
     */
    private val saver = listOf(
        "Nationwide Building Society",
        "Flex Instant Saver",
        "Sort code 07-01-16   Account number 12345678",
        "Date       Details                          Payments   Receipts    Balance",
        "2026",
        "15 Jan     Balance from statement 0028                             3,000.00",
        "31 Jan     Interest                                       1.23     3,001.23",
        "14 Feb     Balance carried forward                                 3,001.23",
    )

    @Test
    fun `a saver with one movement in the month is still a statement`() {
        // Two rows is the ordinary bar and it is there to keep letters and
        // payslips out. A saver honestly breaks it: a month can be one
        // interest payment, and refusing it told the user their real statement
        // was a layout the app did not recognise.
        val rows = PdfStatementParser.parse(saver)
        assertEquals(1, rows.size)
        assertEquals(123L, rows[0].moneyInMinor)
        assertNotNull(PdfStatementParser.toSheet(saver, "saver"))
    }

    @Test
    fun `a letter with a single dated figure is still not a statement`() {
        // The other half of the same rule: dropping the bar to one row must
        // not start offering anything with a date and an amount on it.
        assertNull(
            PdfStatementParser.toSheet(
                listOf(
                    "Dear Mr Evans",
                    "01 Mar 2026 Your monthly premium is 42.15",
                    "Yours faithfully",
                ),
                "letter",
            ),
        )
    }

    @Test
    fun `on a saver what a row calls itself beats the busier column`() {
        // With no running balance the columns are told apart by which is
        // busier, which is sound on a current account and exactly backwards on
        // a saver — where money arriving is the busy direction. Every deposit
        // came through as a withdrawal.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date        Details                          Payments   Receipts",
                "20 Jan 2026 Received from MR R M W EVANS                  500.00",
                "28 Jan 2026 Faster payment to CURRENT ACCOUNT   250.00",
                "31 Jan 2026 Interest                                        1.23",
            ),
        )
        assertEquals(3, rows.size)
        assertEquals(50_000L, rows[0].moneyInMinor)
        assertEquals(25_000L, rows[1].moneyOutMinor)
        assertEquals(123L, rows[2].moneyInMinor)
    }

    @Test
    fun `interest is money in unless the statement says it was charged`() {
        assertEquals(false, PdfStatementParser.directionFromWording("Interest"))
        assertEquals(false, PdfStatementParser.directionFromWording("Received from J SMITH"))
        // The ways it can mean money leaving are tested first.
        assertEquals(true, PdfStatementParser.directionFromWording("Overdraft interest"))
        assertEquals(true, PdfStatementParser.directionFromWording("Interest charged"))
    }

    @Test
    fun `a statement whose cells come out one to a line is still read`() {
        // Some layouts extract a cell at a time, and not one of those lines is
        // a transaction by itself — so a real statement came back as "no
        // transaction rows" with nothing to be done about it.
        val rows = PdfStatementParser.parse(
            listOf(
                "Date Details Payments Receipts Balance",
                "15 Jan 2026",
                "Balance from statement 0028",
                "3,000.00",
                "20 Jan 2026",
                "Received from R EVANS",
                "500.00",
                "3,500.00",
                "31 Jan 2026",
                "Interest",
                "1.23",
                "3,501.23",
            ),
        )
        assertEquals(2, rows.size)
        assertEquals("Received from R EVANS", rows[0].description)
        assertEquals(50_000L, rows[0].moneyInMinor)
        assertEquals(123L, rows[1].moneyInMinor)
        assertEquals(350_123L, rows[1].balanceMinor)
    }

    @Test
    fun `stitching never runs on a statement that already reads`() {
        // The joining pass is a last resort and must not touch a file the
        // ordinary read handles, or a layout that works could be broken by it.
        assertEquals(3, PdfStatementParser.parse(statement).size)
        assertEquals(
            listOf("TESCO STORES 3294", "SALARY ACME LTD", "SHELL FILLING STN"),
            PdfStatementParser.parse(statement).map { it.description },
        )
    }

    @Test
    fun `a statement inside one year is not rolled back`() {
        // The rollback must only fire on an actual year end, not on a file
        // that happens to have a gap in it.
        val rows = PdfStatementParser.parse(
            listOf(
                "Statement 1 March 2026 to 31 March 2026",
                "02 Mar TESCO STORES 3294        42.15    1957.85",
                "28 Mar SHELL FILLING STN        61.40    1896.45",
            ),
        )
        assertEquals(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 28)), rows.map { it.date })
    }
}
