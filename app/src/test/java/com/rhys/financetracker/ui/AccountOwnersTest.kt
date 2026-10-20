package com.rhys.financetracker.ui

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.AccountOwners
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Paid from": the person first, then only their accounts. */
class AccountOwnersTest {

    private val rhys = PersonEntity(id = 1, name = "Rhys Evans", colorHex = "#1F5FD0")
    private val hannah = PersonEntity(id = 2, name = "Hannah", colorHex = "#B4307A")
    private val joint = PersonEntity(id = 3, name = "Joint", colorHex = "#888888", isShared = true)

    private fun account(id: Long, name: String, type: AccountType, personId: Long?, isShared: Boolean = false) =
        AccountEntity(id = id, name = name, type = type, personId = personId, colorHex = "#000000", isShared = isShared)

    private val accounts = listOf(
        account(1, "Lloyds Current Account", AccountType.CURRENT, 1),
        account(2, "Lloyds Current Account", AccountType.CURRENT, 2),
        account(3, "Zopa", AccountType.CREDIT_CARD, 1),
        account(4, "Help To Buy ISA", AccountType.SAVINGS, 2),
        account(5, "car", AccountType.LOAN, 1),
        account(6, "Nationwide Current Account", AccountType.CURRENT, 3),
    )

    @Test
    fun `each person with an account is a tab, by first name, and joint after them`() {
        val owners = AccountOwners.of(listOf(rhys, hannah, joint), accounts)
        assertEquals(listOf("Rhys", "Hannah", "Joint"), owners.groups.map { it.label })
    }

    @Test
    fun `the two Lloyds current accounts sit under their own person`() {
        val owners = AccountOwners.of(listOf(rhys, hannah, joint), accounts)
        assertEquals("p:1", owners.keyOf(accounts[0]))
        assertEquals("p:2", owners.keyOf(accounts[1]))
        assertEquals("joint", owners.keyOf(accounts[5]))
    }

    @Test
    fun `current accounts and cards are paid from, savings and loans are not`() {
        assertTrue(AccountOwners.paysFrom(accounts[0]))
        assertTrue(AccountOwners.paysFrom(accounts[2]))
        assertFalse(AccountOwners.paysFrom(accounts[3]))
        assertFalse(AccountOwners.paysFrom(accounts[4]))
    }

    @Test
    fun `two accounts with one name say whose each is`() {
        val people = listOf(rhys, hannah, joint)
        assertEquals("Lloyds Current Account · Rhys", AccountOwners.nameOf(accounts[0], accounts, people))
        assertEquals("Lloyds Current Account · Hannah", AccountOwners.nameOf(accounts[1], accounts, people))
        assertEquals("Zopa", AccountOwners.nameOf(accounts[2], accounts, people))
    }

    @Test
    fun `an account nobody owns goes under Other`() {
        val owners = AccountOwners.of(listOf(rhys), accounts + account(7, "Cash", AccountType.OTHER, null))
        assertEquals(listOf("Rhys", "Other"), owners.groups.map { it.label })
    }
}
