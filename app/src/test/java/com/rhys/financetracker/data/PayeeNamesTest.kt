package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.PayeeNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayeeNamesTest {

    @Test
    fun `the bank's paperwork is taken off a payee`() {
        assertEquals("J Smith", PayeeNames.of("FASTER PAYMENT TO J SMITH REF 88213"))
        assertEquals("Peter Roche", PayeeNames.of("Standing order PETER ROCHE"))
        assertEquals("Tesco Stores", PayeeNames.of("TESCO STORES 3294"))
    }

    @Test
    fun `a transfer to someone reads as a person`() {
        assertTrue(PayeeNames.looksLikeAPerson("Payment to J SMITH"))
        assertTrue(PayeeNames.looksLikeAPerson("FASTER PAYMENT J SMITH"))
        assertTrue(!PayeeNames.looksLikeAPerson("TESCO STORES 3294"))
    }
}
