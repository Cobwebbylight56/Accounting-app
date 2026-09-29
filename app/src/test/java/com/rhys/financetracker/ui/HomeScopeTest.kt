package com.rhys.financetracker.ui

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.dashboard.ScopeChoice
import com.rhys.financetracker.ui.dashboard.scopeFor
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which money each tab on Home covers. */
class HomeScopeTest {

    private val rhys = PersonEntity(id = 1, name = "Rhys Evans", colorHex = "#1565C0")
    private val hannah = PersonEntity(id = 2, name = "Hannah", colorHex = "#AD1457")
    private val lodger = PersonEntity(id = 3, name = "Sam", colorHex = "#558B2F")
    private val joint = PersonEntity(id = 9, name = "Joint", colorHex = "#455A64", isShared = true)
    private val people = listOf(joint, rhys, hannah, lodger)

    @Test
    fun `Home opens on the first person, not on everybody`() {
        val scope = scopeFor(null, people, null)
        assertEquals(setOf(1L), scope.personIds)
        assertEquals(1L, scope.personId)
        assertTrue(!scope.includesHousehold)
    }

    @Test
    fun `a person's tab is only theirs`() {
        val scope = scopeFor(ScopeChoice.Person(2), people, null)
        assertEquals(setOf(2L), scope.personIds)
        assertEquals("Hannah", scope.label)
    }

    @Test
    fun `Shared covers the chosen people and what is held jointly`() {
        val scope = scopeFor(ScopeChoice.Shared, people, setOf(1L, 2L))
        assertEquals(setOf(1L, 2L, 9L), scope.personIds)
        assertTrue(scope.isShared)
        assertNull(scope.personId)
        assertTrue(scope.includesHousehold)
    }

    @Test
    fun `Shared covers everybody until somebody chooses`() {
        val scope = scopeFor(ScopeChoice.Shared, people, null)
        assertNull(scope.personIds)
        assertTrue(scope.isShared)
    }

    @Test
    fun `a loan is put away once paid off, but a card at zero is not`() {
        fun item(type: AccountType, opening: Long, balance: Long, limit: Long? = null) =
            AccountWithBalance(
                account = AccountEntity(
                    name = type.displayName,
                    type = type,
                    personId = 1L,
                    openingBalanceMinor = opening,
                    openingBalanceDate = LocalDate.of(2026, 1, 1),
                    creditLimitMinor = limit,
                    colorHex = "#000000",
                ),
                balanceMinor = balance,
                personName = null,
            )
        assertTrue(AccountRepository.isPaidOffLoan(item(AccountType.LOAN, -500_000L, 0L)))
        assertTrue(AccountRepository.isPaidOffLoan(item(AccountType.MORTGAGE, -1L, 12L)))
        assertTrue(!AccountRepository.isPaidOffLoan(item(AccountType.LOAN, -500_000L, -1L)))
        // Never owed anything: added at £0 by mistake, not paid off.
        assertTrue(!AccountRepository.isPaidOffLoan(item(AccountType.LOAN, 0L, 0L)))
        assertTrue(!AccountRepository.isPaidOffLoan(item(AccountType.CREDIT_CARD, -20_000L, 0L)))
    }
}
