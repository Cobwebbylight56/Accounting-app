package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.StatementKind
import com.rhys.financetracker.domain.model.Holding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What kind of account a statement is for, read from its heading. */
class StatementKindTest {

    @Test
    fun `a Start to Save statement is a savings statement`() {
        val found = StatementKind.detect(
            listOf(
                "Nationwide Building Society",
                "Mr Rhys Evans",
                "Start to Save",
                "Account number 12345678",
                "Date Description Payments Receipts Balance",
                "01 Sep 2026 Transfer from FlexDirect 200.00 200.00",
            ),
        )
        assertEquals(StatementKind.SAVINGS, found?.kind)
        assertEquals("Start to Save", found?.productName)
        assertEquals(Holding.SET_ASIDE, found?.kind?.holding)
    }

    @Test
    fun `a current account is not made a saver by what it pays`() {
        // The transactions are where the words mislead: every current account
        // statement is full of TRANSFER TO SAVINGS. Lines with amounts are
        // never read as the heading.
        val found = StatementKind.detect(
            listOf(
                "FlexDirect",
                "Mr Rhys Evans",
                "Date Description Paid out Paid in Balance",
                "01 Sep 2026 Transfer to Start to Save 200.00 1,000.00",
            ),
        )
        assertEquals(StatementKind.CURRENT, found?.kind)
    }

    @Test
    fun `the first thing the heading says wins`() {
        val found = StatementKind.detect(
            listOf("Your current account statement", "Why not open a Regular Saver?"),
        )
        assertEquals(StatementKind.CURRENT, found?.kind)
    }

    @Test
    fun `an ISA and a card are recognised`() {
        assertEquals(StatementKind.SAVINGS, StatementKind.detect(listOf("Cash ISA"))?.kind)
        assertEquals(
            StatementKind.CREDIT_CARD,
            StatementKind.detect(listOf("Barclaycard statement"))?.kind,
        )
    }

    @Test
    fun `a CSV is read from its file name`() {
        assertEquals(
            StatementKind.SAVINGS,
            StatementKind.detect(emptyList(), "StartToSave_Sept.csv")?.kind,
        )
    }

    @Test
    fun `a heading that says nothing gives no answer`() {
        assertNull(StatementKind.detect(listOf("Statement", "Mr R Evans"), "export.csv"))
    }
}
