package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.model.TransactionType.INCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `money moved to a saver is savings, not spending`() {
        // The payment that starts the saving was the one thing never counted
        // as saving: a standing order to a saver at another bank looked like
        // ordinary spending, so a household saving every month was shown as
        // having saved nothing.
        assertEquals("Savings", categoryFor("TRANSFER TO SAVER"))
        assertEquals("Savings", categoryFor("REGULAR SAVER 12345678"))
        assertEquals("Savings", categoryFor("NATIONWIDE ISA 998812"))
        assertEquals("Savings", categoryFor("MONEYBOX"))
        assertEquals("Savings", categoryFor("NS AND I PREMIUM BONDS"))
        assertEquals("Savings", categoryFor("STANDING ORDER TO SAVINGS"))
        // Nationwide's saver, and the one that was still coming through as
        // ordinary spending: "Transfer to START TO SAVE".
        assertEquals("Savings", categoryFor("Transfer to START TO SAVE"))
        assertEquals("Savings", categoryFor("Transfer to START TO SAVE ISA 998812"))
    }

    @Test
    fun `money coming back out of a saver is savings, not income`() {
        // The half that was missing. Only payments into a saver were counted,
        // so a month that emptied its savings was reported as a month that
        // saved nothing — rather than one that took money out.
        assertEquals("Savings", categoryFor("Transfer from START TO SAVE", INCOME))
        assertEquals("Savings", categoryFor("SAVINGS WITHDRAWAL", INCOME))
        assertEquals("Savings", categoryFor("TRANSFER FROM SAVINGS", INCOME))
        assertEquals("Savings", categoryFor("NS AND I", INCOME))
        // And a real wage is still a wage.
        assertEquals("Salary", categoryFor("SALARY ACME LTD", INCOME))
    }

    @Test
    fun `cash out of a machine is cash, not spending`() {
        // Taking £50 out spends nothing — it is £50 in a pocket. Counted as
        // spending it inflates the month twice: once at the machine and again
        // when the cash is actually spent.
        assertEquals("Cash", categoryFor("CASH WITHDRAWAL"))
        assertEquals("Cash", categoryFor("ATM 4432 CARDIFF"))
        assertEquals("Cash", categoryFor("LINK CASH MACHINE"))
        assertEquals("Cash", categoryFor("CASHPOINT HIGH ST"))
        assertEquals("Cash", categoryFor("NOTEMACHINE LTD"))
        assertEquals("Cash", categoryFor("COUNTER WITHDRAWAL"))
        // And cash going back in is the same words the other way.
        assertEquals("Cash", categoryFor("CASH DEPOSIT", INCOME))
        assertEquals("Cash", categoryFor("BRANCH DEPOSIT", INCOME))
    }

    @Test
    fun `savings wins over cash when a withdrawal names the saver`() {
        // "Withdrawal" alone is cash; a withdrawal from savings is savings.
        // The order of the two rules is what decides it.
        assertEquals("Savings", categoryFor("SAVINGS WITHDRAWAL", INCOME))
        assertEquals("Cash", categoryFor("WITHDRAWAL", INCOME))
    }

    @Test
    fun `the everyday wordings a saver actually uses are all recognised`() {
        listOf(
            "TRANSFER TO SAVER", "REGULAR SAVER 12345678", "TRIPLE ACCESS SAVER 15",
            "FLEX INSTANT SAVER", "ONLINE SAVER", "HELP TO SAVE", "Transfer to START TO SAVE",
            "NATIONWIDE ISA 998812", "CASH ISA 4432", "LIFETIME ISA", "FIXED RATE BOND",
            "NS AND I PREMIUM BONDS", "MONEYBOX", "VANGUARD INVESTMENTS",
            "HARGREAVES LANSDOWN", "MONZO POT", "EMERGENCY FUND", "SAVINGS ACCOUNT 88",
            "STANDING ORDER TO SAVINGS",
        ).forEach { description ->
            assertEquals(description, "Savings", categoryFor(description))
        }
    }

    @Test
    fun `a shop and a person are not savings for starting the same way`() {
        // Keywords match at the start of a word, so "saver" reaches SAVERS the
        // shop and "isa" reaches ISABELLAS. Both are ordinary spending.
        listOf(
            "SAVERS HEALTH AND BEAUTY 204", "ISABELLAS FLOWERS", "LISA EVANS",
            "SAVE THE CHILDREN", "CHIP SHOP", "THE CHIPPY", "POT NOODLE CO",
            "GOALS SOCCER CENTRE", "SPACE NK APOTHECARY", "ATMOSPHERE BAR",
        ).forEach { description ->
            assertNotEquals(description, "Savings", categoryFor(description))
            assertNotEquals(description, "Cash", categoryFor(description))
        }
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
