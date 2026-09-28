package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.AccountNaming
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
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
            assertEquals(name, Holding.SET_ASIDE, AccountNaming.holdingFor(name))
        }
    }

    @Test
    fun `a cash ISA is savings, and a tin of notes is the cash pot`() {
        assertEquals(AccountType.SAVINGS, AccountNaming.typeFor("Cash ISA"))
        assertEquals(AccountType.SAVINGS, AccountNaming.typeFor("Cash Savings"))
        assertTrue(!AccountNaming.isCashPot("Cash ISA"))
        assertTrue(AccountNaming.isCashPot("Cash"))
        assertTrue(AccountNaming.isCashPot("£1 coins"))
        assertTrue(!AccountNaming.isCashPot("Main account"))
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
        assertTrue(AccountNaming.looksMistyped("Start to Save", Holding.SPEND))
        assertTrue(AccountNaming.looksMistyped("saver", Holding.SPEND))
        // Already set aside: nothing to say.
        assertTrue(!AccountNaming.looksMistyped("Start to Save", Holding.SET_ASIDE))
        // And an ordinary current account is left alone.
        assertTrue(!AccountNaming.looksMistyped("Current account", Holding.SPEND))
    }

    @Test
    fun `a dormant current account cannot be read from its name`() {
        // Money kept in a second current account in case the main one is lost
        // is set aside in practice and a current account by name. Nothing here
        // can know that, which is what each account's "Counts as" setting is for.
        assertEquals(AccountType.CURRENT, AccountNaming.typeFor("Spare account"))
        assertTrue(!AccountNaming.looksMistyped("Spare account", Holding.SPEND))
    }
}
