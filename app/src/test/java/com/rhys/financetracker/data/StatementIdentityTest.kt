package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.StatementIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

/** A statement's own numbers and bank say whose account it is, where a name can't. */
class StatementIdentityTest {

    @Test
    fun `sort code and account number are read however they're laid out`() {
        assertEquals(listOf("sc:070116:12345678"), StatementIdentity.of(listOf("Your FlexDirect", "Sort code 07-01-16   Account number 12345678")))
        assertEquals(listOf("sc:309602:87654321"), StatementIdentity.of(listOf("Sort code: 30 96 02", "Account no. 87654321")))
        assertEquals(listOf("sc:309602:87654321"), StatementIdentity.of(listOf("Sort code 30-96-02 87654321")))
    }

    @Test
    fun `a card statement is known by its last four digits`() {
        assertEquals(listOf("card:4321"), StatementIdentity.of(listOf("CARD NUMBER ************4321")))
        assertEquals(listOf("card:1486"), StatementIdentity.of(listOf("Card ending in 1486")))
        assertEquals(listOf("card:9876"), StatementIdentity.of(listOf("XXXX XXXX XXXX 9876")))
    }

    @Test
    fun `scrubbed or missing numbers say nothing`() {
        assertEquals(emptyList<String>(), StatementIdentity.of(listOf("Sort code 00-00-00 Account number 00000000")))
        assertEquals(emptyList<String>(), StatementIdentity.of(listOf("Statement for October", "Money in £100")))
    }

    @Test
    fun `the bank is read from the heading`() {
        assertEquals("Nationwide", StatementIdentity.bankOf(listOf("Nationwide Building Society", "FlexDirect")))
        assertEquals("Lloyds", StatementIdentity.bankOf(listOf("Lloyds Bank plc", "Classic account")))
        assertEquals("Capital One", StatementIdentity.bankOf(listOf("Capital One (Europe) plc")))
        assertEquals("Barclaycard", StatementIdentity.bankOf(listOf("Barclaycard Platinum")))
        assertEquals(null, StatementIdentity.bankOf(listOf("Statement", "Your account")))
    }

    @Test
    fun `a payment line naming a sort code and account number gives the statement's key`() {
        assertEquals("sc:070806:21646684", StatementIdentity.numbersIn("Transfer to 070806 21646684"))
        assertEquals("sc:070806:21646684", StatementIdentity.numbersIn("FPO 07-08-06 21646684 REF HOLIDAY"))
        assertEquals(null, StatementIdentity.numbersIn("TESCO STORES 2231"))
        assertEquals(null, StatementIdentity.numbersIn("Transfer to 000000 00000000"))
    }
}
