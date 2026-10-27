package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.report.StatementGaps
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "A statement is missing": only where one comes every month, and never once said empty. */
class StatementGapsTest {

    private fun ym(month: Int) = YearMonth.of(2026, month)

    @Test
    fun `a month with no statement between others is missing`() {
        assertEquals(listOf(ym(8)), StatementGaps.missing(setOf(ym(6), ym(7), ym(9)), ym(9), 11, emptySet()))
    }

    @Test
    fun `a month said to have been empty is not missing`() {
        assertEquals(emptyList<YearMonth>(), StatementGaps.missing(setOf(ym(6), ym(7), ym(9)), ym(10), 11, setOf(ym(8), ym(10))))
    }

    @Test
    fun `nothing is missing before the first statement or with none at all`() {
        assertEquals(emptyList<YearMonth>(), StatementGaps.missing(emptySet(), ym(10), 11, emptySet()))
        assertEquals(listOf(ym(10)), StatementGaps.missing(setOf(ym(9)), ym(10), 11, emptySet()))
    }

    @Test
    fun `current accounts and cards get a statement a month, savings and loans don't`() {
        assertTrue(StatementGaps.monthly(AccountType.CURRENT, Holding.SPEND))
        assertTrue(StatementGaps.monthly(AccountType.CREDIT_CARD, Holding.OWED))
        assertFalse(StatementGaps.monthly(AccountType.SAVINGS, Holding.SET_ASIDE))
        assertFalse(StatementGaps.monthly(AccountType.LOAN, Holding.OWED))
    }

    @Test
    fun `a month said empty is kept by account and month`() {
        assertEquals("12:2026-10", StatementGaps.key(12, ym(10)))
    }
}
