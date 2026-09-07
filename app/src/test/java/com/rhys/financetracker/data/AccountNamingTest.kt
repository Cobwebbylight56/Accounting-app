package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.AccountNaming
import com.rhys.financetracker.domain.model.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the banks call an account, and what that makes it.
 *
 * The whole of "my savings show as available" comes down to the type on the
 * account, and the type is guessed from the name.
 */
class AccountNamingTest {

    @Test
    fun `every way a saver is named counts as savings`() {
        listOf(
            "Start to Save", "Instant Access Saver", "Limited Access Saver",
            "Triple Access Saver", "Fixed Term Bond", "Fixed Rate Saver",
            "Cash ISA", "LISA", "Lifetime ISA", "Junior ISA", "Regular Saver",
            "Easy Access Savings", "Notice Account", "Premium Bonds",
            "Christmas Saver", "Help to Save", "Online Saver", "Savings",
        ).forEach { name ->
            assertTrue(name, AccountNaming.typeFor(name).isSavings)
        }
    }

    @Test
    fun `a cash ISA is savings, not a tin of notes`() {
        // The fault that put a saver inside the cash-in-hand card: the word
        // "cash" was tested before anything else.
        assertEquals(AccountType.SAVINGS, AccountNaming.typeFor("Cash ISA"))
        assertEquals(AccountType.SAVINGS, AccountNaming.typeFor("Cash Savings"))
        assertEquals(AccountType.CASH, AccountNaming.typeFor("Cash in the house"))
    }

    @Test
    fun `the ordinary accounts are still what they are`() {
        assertEquals(AccountType.CURRENT, AccountNaming.typeFor("Current account"))
        assertEquals(AccountType.CURRENT, AccountNaming.typeFor("Main account"))
        assertEquals(AccountType.CREDIT_CARD, AccountNaming.typeFor("Barclaycard"))
        assertEquals(AccountType.MORTGAGE, AccountNaming.typeFor("Mortgage"))
        assertEquals(AccountType.LOAN, AccountNaming.typeFor("Car loan"))
        assertEquals(AccountType.PENSION, AccountNaming.typeFor("Pension"))
    }

    @Test
    fun `an account the name says is savings but is counted as spendable is flagged`() {
        // Fixing the guess only helps accounts made afterwards. The ones
        // already in the app are the ones that are wrong, so the list offers
        // to put them right.
        assertTrue(
            AccountNaming.looksMistyped("Start to Save", AccountType.CURRENT, null),
        )
        // Already savings: nothing to say.
        assertTrue(
            !AccountNaming.looksMistyped("Start to Save", AccountType.SAVINGS, null),
        )
        // The owner has said either way, so they are not second-guessed.
        assertTrue(
            !AccountNaming.looksMistyped("Start to Save", AccountType.CURRENT, false),
        )
        assertTrue(
            !AccountNaming.looksMistyped("Start to Save", AccountType.CURRENT, true),
        )
        // And an ordinary current account is left alone.
        assertTrue(
            !AccountNaming.looksMistyped("Current account", AccountType.CURRENT, null),
        )
    }

    @Test
    fun `a dormant current account cannot be read from its name`() {
        // Money kept in a second current account in case the main one is lost
        // is set aside in practice and a current account by name. Nothing here
        // can know that, which is what the per-account switch is for.
        assertEquals(AccountType.CURRENT, AccountNaming.typeFor("Spare account"))
        assertTrue(!AccountNaming.looksMistyped("Spare account", AccountType.CURRENT, null))
    }
}
