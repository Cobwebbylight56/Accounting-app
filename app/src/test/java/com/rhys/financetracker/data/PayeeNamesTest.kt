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

    @Test
    fun `the same person gives the same key however the bank writes them`() {
        val key = PayeeNames.personKey("J Smith")
        assertEquals("j smith", key)
        assertEquals(key, PayeeNames.personKey("John Smith"))
        assertEquals(key, PayeeNames.personKey("Mr J Smith"))
        assertEquals(key, PayeeNames.personKey("Smith J"))
        assertTrue(PayeeNames.personKey("Hannah Evans") != key)
    }

    @Test
    fun `money in from someone reads as from a person`() {
        assertTrue(PayeeNames.looksLikeFromAPerson("FASTER PAYMENT FROM J SMITH"))
        assertTrue(PayeeNames.looksLikeFromAPerson("From HANNAH EVANS ref dinner"))
        assertTrue(!PayeeNames.looksLikeFromAPerson("ACME LTD SALARY"))
    }

    @Test
    fun `money that came in is recognised however the bank words it`() {
        assertTrue(PayeeNames.readsLikeMoneyIn("Bank credit H Payne"))
        assertTrue(PayeeNames.readsLikeMoneyIn("FASTER PAYMENT FROM HANNAH PAYNE"))
        assertTrue(PayeeNames.readsLikeMoneyIn("Payment received H PAYNE"))
        org.junit.Assert.assertFalse(PayeeNames.readsLikeMoneyIn("FASTER PAYMENT TO H PAYNE"))
        org.junit.Assert.assertFalse(PayeeNames.readsLikeMoneyIn("Standing order Hannah Payne"))
        // "Credit card" in a name is not money in.
        org.junit.Assert.assertFalse(PayeeNames.readsLikeMoneyIn("CREDITON GARAGE"))
    }
}
