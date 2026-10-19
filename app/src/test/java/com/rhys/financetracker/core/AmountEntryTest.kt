package com.rhys.financetracker.core

import com.rhys.financetracker.core.money.AmountEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** Money typed like a till: the comma and the point put in for you. */
class AmountEntryTest {

    /** Each key pressed in turn, at the end of what the field shows, as the field does. */
    private fun press(vararg keys: String): String =
        keys.fold("") { pounds, key ->
            val shown = AmountEntry.shown(pounds)
            AmountEntry.typed(if (key == "⌫") shown.dropLast(1) else shown + key)
        }

    @Test
    fun `digits fill in from the pence`() {
        assertEquals("0.05", AmountEntry.shown(press("5")))
        assertEquals("24.99", AmountEntry.shown(press("2", "4", "9", "9")))
        assertEquals("1,234.56", AmountEntry.shown(press("1", "2", "3", "4", "5", "6")))
        assertEquals("1,000,000.00", AmountEntry.shown(press("1", "0", "0", "0", "0", "0", "0", "0", "0")))
    }

    @Test
    fun `the form keeps plain pounds`() {
        assertEquals("1234.56", press("1", "2", "3", "4", "5", "6"))
        assertEquals("", press("5", "⌫"))
    }

    @Test
    fun `taking a digit off takes the last one`() {
        assertEquals("1.23", press("1", "2", "3", "4", "⌫"))
        assertEquals("", press("1", "⌫", "⌫"))
    }

    @Test
    fun `a typed point or comma changes nothing`() {
        assertEquals("0.12", press("1", "2", "."))
        assertEquals("0.12", press("1", ",", "2"))
    }

    @Test
    fun `a minus makes it negative and a second takes it off`() {
        assertEquals("-12.50", AmountEntry.shown(press("-", "1", "2", "5", "0")))
        assertEquals("-", AmountEntry.shown(press("-")))
        assertEquals("12.50", AmountEntry.shown(press("1", "2", "5", "0", "-", "-")))
    }

    @Test
    fun `amounts the form already holds are laid out`() {
        assertEquals("32,000.00", AmountEntry.shown("32000"))
        assertEquals("12.30", AmountEntry.shown("12.3"))
        assertEquals("1,234.56", AmountEntry.shown("£1,234.56"))
        assertEquals("", AmountEntry.shown(""))
        assertEquals("0.00", AmountEntry.shown("0"))
    }

    @Test
    fun `something pasted in is read by its digits`() {
        assertEquals("12.50", AmountEntry.typed("£12.50"))
    }
}
