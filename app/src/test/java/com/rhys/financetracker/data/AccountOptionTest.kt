package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.labelFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Account names are unique per person rather than across the app, so two
 * people can each have a "Main account" — which is what they are both called
 * in real life. That only works if a picker can still tell them apart.
 */
class AccountOptionTest {

    private fun account(id: Long, name: String, owner: String?) =
        AccountOption(id = id, name = name, colorHex = null, personName = owner)

    @Test
    fun `a name only one account uses is shown plainly`() {
        val accounts = listOf(
            account(1, "Main account", "Rhys"),
            account(2, "Credit card", "Hannah"),
        )
        assertEquals("Main account", accounts.labelFor(accounts[0]))
        assertEquals("Credit card", accounts.labelFor(accounts[1]))
    }

    @Test
    fun `a shared name is qualified with its owner`() {
        val accounts = listOf(
            account(1, "Main account", "Rhys"),
            account(2, "Main account", "Hannah"),
        )
        assertEquals("Rhys · Main account", accounts.labelFor(accounts[0]))
        assertEquals("Hannah · Main account", accounts.labelFor(accounts[1]))
    }

    @Test
    fun `matching ignores capitals`() {
        // Saving is case-insensitive about clashes, so labelling has to agree
        // or one of the pair would be left unqualified.
        val accounts = listOf(
            account(1, "Main Account", "Rhys"),
            account(2, "main account", "Hannah"),
        )
        assertEquals("Rhys · Main Account", accounts.labelFor(accounts[0]))
        assertEquals("Hannah · main account", accounts.labelFor(accounts[1]))
    }

    @Test
    fun `a shared account with no owner keeps its plain name`() {
        // There is nobody to name it after, and "· Joint" would read as a fault.
        val accounts = listOf(
            account(1, "Savings", null),
            account(2, "Savings", "Rhys"),
        )
        assertEquals("Savings", accounts.labelFor(accounts[0]))
        assertEquals("Rhys · Savings", accounts.labelFor(accounts[1]))
    }

    @Test
    fun `one account on its own is never qualified`() {
        val accounts = listOf(account(1, "Main account", "Rhys"))
        assertEquals("Main account", accounts.labelFor(accounts[0]))
    }
}
