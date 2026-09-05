package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Filing a thousand imported rows by hand is a spending history nobody builds,
 * so the descriptions a UK statement actually contains have to be recognised —
 * including the ones where a shorter rule would grab the wrong category first.
 */
class MerchantCategoriserTest {

    private fun categoryFor(
        description: String,
        type: TransactionType = TransactionType.EXPENSE,
        learned: Map<String, String> = emptyMap(),
    ) = MerchantCategoriser.categoryFor(description, type, learned)

    private fun learned(vararg pairs: Pair<String, String>): Map<String, String> =
        pairs.associate { (merchant, category) ->
            TransactionFingerprint.normaliseDescription(merchant) to category
        }

    @Test
    fun `recognises the everyday shops`() {
        assertEquals("Groceries", categoryFor("TESCO STORES 3294"))
        assertEquals("Groceries", categoryFor("ALDI 812 CARDIFF"))
        assertEquals("Fuel", categoryFor("SHELL FILLING STN"))
        assertEquals("Eating out", categoryFor("GREGGS PLC 1042"))
        assertEquals("Subscriptions", categoryFor("NETFLIX.COM"))
        assertEquals("Council tax", categoryFor("CARDIFF COUNCIL TAX"))
        assertEquals("Shopping", categoryFor("AMAZON.CO.UK*A12BC"))
    }

    @Test
    fun `a longer rule wins over the shorter one it contains`() {
        // Each of these would be filed wrongly if the broader rule ran first.
        assertEquals("Mobile", categoryFor("TESCO MOBILE LTD"))
        assertEquals("Eating out", categoryFor("UBER EATS"))
        assertEquals("Energy", categoryFor("SHELL ENERGY RETAIL"))
        assertEquals("Fuel", categoryFor("SAINSBURYS PETROL 41"))
    }

    @Test
    fun `income is read separately from spending`() {
        assertEquals("Salary", categoryFor("ACME LTD SALARY", TransactionType.INCOME))
        assertEquals("Refunds", categoryFor("REFUND ASOS", TransactionType.INCOME))
        // The same words mean nothing on the spending side.
        assertNull(categoryFor("SALARY", TransactionType.EXPENSE))
    }

    @Test
    fun `what you decided before beats the built-in guess`() {
        // Sainsbury's is groceries by default, but this one was filed as Fuel
        // and should stay there on every later import.
        val history = learned("SAINSBURYS SPRUCE HILL" to "Fuel")
        assertEquals("Fuel", categoryFor("SAINSBURYS SPRUCE HILL", learned = history))
        assertEquals("Groceries", categoryFor("SAINSBURYS LOCAL 42"))
    }

    @Test
    fun `a remembered payee still matches when the reference changes`() {
        // Banks add and drop trailing numbers between exports.
        val history = learned("VIRGIN MEDIA PAYMENTS" to "Broadband")
        assertEquals(
            "Broadband",
            categoryFor("VIRGIN MEDIA PAYMENTS 998812", learned = history),
        )
    }

    @Test
    fun `a keyword buried inside a longer word does not match`() {
        // "tfl" sits inside "neTFLix", which filed every Netflix payment under
        // public transport until matching was anchored to the start of a word.
        assertEquals("Subscriptions", categoryFor("NETFLIX.COM"))
        // The anchor must not cost us the fragment matching it is there for:
        // "sainsbury" still has to match "sainsburys".
        assertEquals("Groceries", categoryFor("SAINSBURYS 0421"))
    }

    @Test
    fun `an unknown payee is left alone rather than guessed at`() {
        // An empty category is obvious and quick to fix; a wrong one is neither.
        assertNull(categoryFor("J SMITH"))
        assertNull(categoryFor("FPO 88213"))
        assertNull(categoryFor(""))
        assertNull(categoryFor("   "))
    }
}
