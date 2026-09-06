package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.SheetData
import com.rhys.financetracker.data.importer.StatementDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures are the shapes real UK bank exports arrive in: a preamble above
 * the headings, either a paid-out/paid-in pair or one signed amount, and a
 * running balance sitting next to the figure that actually matters.
 */
class StatementDetectorTest {

    private fun row(vararg cells: String) = cells.toList()

    /** Lloyds, Halifax, Nationwide: separate columns, and two preamble rows. */
    private val twoColumnStatement = SheetData(
        name = "Statement",
        rows = listOf(
            /* 0 */ row("Your account", "", "", "", ""),
            /* 1 */ row("Sort code 00-00-00", "Account 12345678", "", "", ""),
            /* 2 */ row("Date", "Description", "Paid out", "Paid in", "Balance"),
            /* 3 */ row("01/03/2026", "TESCO STORES 3294", "42.15", "", "1957.85"),
            /* 4 */ row("02/03/2026", "SALARY ACME LTD", "", "1862.23", "3820.08"),
            /* 5 */ row("03/03/2026", "SHELL FILLING STN", "61.40", "", "3758.68"),
        ),
    )

    /**
     * A building society savings statement: Payments and Receipts rather than
     * paid out and paid in, and one movement in the period.
     */
    private val saverStatement = SheetData(
        name = "Statement",
        rows = listOf(
            /* 0 */ row("Flex Instant Saver", "", "", "", ""),
            /* 1 */ row("Date", "Details", "Payments", "Receipts", "Balance"),
            /* 2 */ row("31/01/2026", "Interest", "", "1.23", "3001.23"),
        ),
    )

    @Test
    fun `payments and receipts are money out and money in`() {
        // How the building societies word it. Read as anything else, a saver
        // statement had no money columns at all and was not a statement.
        val mapping = StatementDetector.detect(saverStatement)
        assertNotNull(mapping)
        assertEquals(ColumnRole.DATE, mapping!!.columnRoles[0])
        assertEquals(ColumnRole.MONEY_OUT, mapping.columnRoles[2])
        assertEquals(ColumnRole.MONEY_IN, mapping.columnRoles[3])
        assertEquals(ColumnRole.BALANCE, mapping.columnRoles[4])
    }

    @Test
    fun `one movement in the month is enough to be a statement`() {
        // A saver really can have a single line in it, and refusing that told
        // the user their statement was a layout the app did not recognise.
        assertTrue(StatementDetector.looksLikeStatement(saverStatement))
    }

    /** Monzo, Starling and most CSV exports: one signed column. */
    private val signedStatement = SheetData(
        name = "Statement",
        rows = listOf(
            /* 0 */ row("Date", "Reference", "Amount", "Balance"),
            /* 1 */ row("01/03/2026", "TESCO STORES 3294", "-42.15", "1957.85"),
            /* 2 */ row("02/03/2026", "SALARY ACME LTD", "1862.23", "3820.08"),
            /* 3 */ row("03/03/2026", "SHELL FILLING STN", "-61.40", "3758.68"),
        ),
    )

    @Test
    fun `finds the headings below a preamble`() {
        val mapping = StatementDetector.detect(twoColumnStatement)
        assertNotNull(mapping)
        assertEquals(2, mapping!!.headerRow)
        assertEquals(3, mapping.firstDataRow)
        assertEquals(5, mapping.lastDataRow)
    }

    @Test
    fun `reads paid out and paid in as separate columns`() {
        val roles = StatementDetector.detect(twoColumnStatement)!!.columnRoles
        assertEquals(ColumnRole.DATE, roles[0])
        assertEquals(ColumnRole.NAME, roles[1])
        assertEquals(ColumnRole.MONEY_OUT, roles[2])
        assertEquals(ColumnRole.MONEY_IN, roles[3])
    }

    @Test
    fun `never mistakes the running balance for the amount`() {
        // The balance column parses as money and would otherwise be imported,
        // adding the account's whole history a second time.
        val twoColumn = StatementDetector.detect(twoColumnStatement)!!.columnRoles
        assertEquals(ColumnRole.BALANCE, twoColumn[4])

        val signed = StatementDetector.detect(signedStatement)!!.columnRoles
        assertEquals(ColumnRole.BALANCE, signed[3])
        assertEquals(ColumnRole.AMOUNT, signed[2])
    }

    @Test
    fun `only a single amount column lets the sign mean direction`() {
        // With paid-out and paid-in columns the direction is already known, so
        // a stray minus sign is a problem to report rather than a fact to use.
        assertFalse(StatementDetector.detect(twoColumnStatement)!!.amountSignIsDirection)
        assertTrue(StatementDetector.detect(signedStatement)!!.amountSignIsDirection)
    }

    @Test
    fun `rows become transactions rather than bills`() {
        assertEquals(
            ImportTarget.TRANSACTION,
            StatementDetector.detect(twoColumnStatement)!!.target,
        )
    }

    @Test
    fun `files the rows against the account it is given`() {
        val mapping = StatementDetector.detect(twoColumnStatement, accountName = "Rhys bank")
        assertEquals("Rhys bank", mapping!!.defaultAccountName)
    }

    @Test
    fun `finds the description even when the heading is unfamiliar`() {
        val roles = StatementDetector.detect(signedStatement)!!.columnRoles
        assertEquals(ColumnRole.NAME, roles[1])
    }

    @Test
    fun `a household budget is not a statement`() {
        val budget = SheetData(
            name = "Sheet1",
            rows = listOf(
                row("", "", "Rhys", "Hannah"),
                row("income", "", "1862.23", "1447"),
                row("council tax", "", "104.10", "104.10"),
            ),
        )
        assertNull(StatementDetector.detect(budget))
        assertFalse(StatementDetector.looksLikeStatement(budget))
    }

    @Test
    fun `a file with headings but no rows is not offered`() {
        val empty = SheetData(
            name = "Statement",
            rows = listOf(row("Date", "Description", "Paid out", "Paid in", "Balance")),
        )
        assertNull(StatementDetector.detect(empty))
    }
}
