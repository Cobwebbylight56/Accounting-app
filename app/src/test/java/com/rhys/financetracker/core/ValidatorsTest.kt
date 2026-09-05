package com.rhys.financetracker.core

import com.rhys.financetracker.core.validation.Validators
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    @Test
    fun `a blank name is rejected`() {
        assertFalse(Validators.validateName("").isValid)
        assertFalse(Validators.validateName("   ").isValid)
    }

    @Test
    fun `an over-long name is rejected`() {
        val name = "a".repeat(Validators.MAX_NAME_LENGTH + 1)
        assertFalse(Validators.validateName(name).isValid)
    }

    @Test
    fun `zero is rejected unless it is allowed`() {
        assertFalse(Validators.validateAmount("0").isValid)
        assertTrue(Validators.validateAmount("0", allowZero = true).isValid)
    }

    @Test
    fun `a negative amount is rejected unless it is allowed`() {
        assertFalse(Validators.validateAmount("-5").isValid)
        assertTrue(Validators.validateAmount("-5", allowNegative = true).isValid)
    }

    @Test
    fun `an end date before the start date is rejected`() {
        val start = LocalDate.of(2026, 3, 1)
        val end = LocalDate.of(2026, 2, 1)
        assertFalse(Validators.validateDateOrder(start, end).isValid)
        assertTrue(Validators.validateDateOrder(start, null).isValid)
    }

    @Test
    fun `a PIN must be four to twelve digits`() {
        assertFalse(Validators.validatePin("123").isValid)
        assertTrue(Validators.validatePin("1234").isValid)
        assertFalse(Validators.validatePin("12345678901234").isValid)
        assertFalse(Validators.validatePin("12a4").isValid)
    }

    @Test
    fun `firstError returns the first failure only`() {
        val error = Validators.firstError(
            Validators.validateName("ok"),
            Validators.validateAmount("nonsense"),
            Validators.validateDate(null),
        )
        assertNotNull(error)
        assertEquals("Enter a valid amount, for example 24.99", error)
    }

    @Test
    fun `firstError returns null when everything passes`() {
        assertNull(
            Validators.firstError(
                Validators.validateName("Groceries"),
                Validators.validateAmount("24.99"),
                Validators.validateDate(LocalDate.of(2026, 3, 1)),
            ),
        )
    }
}
