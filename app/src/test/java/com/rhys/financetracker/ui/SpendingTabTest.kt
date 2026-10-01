package com.rhys.financetracker.ui

import com.rhys.financetracker.ui.transactions.LedgerRequests
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendingTabTest {

    @Test
    fun `a payee opens the money tab searched for that payee in that month`() {
        val requests = LedgerRequests()
        requests.openSearch("Tesco Stores", YearMonth.of(2026, 1))
        val filter = requests.requests.value!!
        assertEquals("Tesco Stores", filter.text)
        assertEquals(LocalDate.of(2026, 1, 1), filter.dateFrom)
        assertEquals(LocalDate.of(2026, 1, 31), filter.dateTo)
    }

    @Test
    fun `a report's category opens over the report's whole period`() {
        val requests = LedgerRequests()
        requests.openCategoryBetween(3L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val filter = requests.requests.value!!
        assertEquals(setOf(3L), filter.categoryIds)
        assertEquals(LocalDate.of(2026, 12, 31), filter.dateTo)
    }
}
