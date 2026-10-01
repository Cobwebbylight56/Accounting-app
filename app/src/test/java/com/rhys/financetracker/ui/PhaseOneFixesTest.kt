package com.rhys.financetracker.ui

import com.rhys.financetracker.domain.model.DashboardWidget
import com.rhys.financetracker.ui.transactions.LedgerRequests
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseOneFixesTest {

    @Test
    fun `advice opens the money tab on that category for that month`() {
        val requests = LedgerRequests()
        requests.openCategory(categoryId = 7L, month = YearMonth.of(2026, 8))
        val filter = requests.requests.value!!
        assertEquals(setOf(7L), filter.categoryIds)
        assertEquals("2026-08-01", filter.dateFrom.toString())
        assertEquals("2026-08-31", filter.dateTo.toString())
        assertFalse(filter.onlyUncategorised)

        requests.consumed()
        assertNull(requests.requests.value)
    }

    @Test
    fun `uncategorised advice opens the uncategorised payments`() {
        val requests = LedgerRequests()
        requests.openCategory(categoryId = null, month = YearMonth.of(2026, 9))
        val filter = requests.requests.value!!
        assertTrue(filter.onlyUncategorised)
        assertTrue(filter.categoryIds.isEmpty())
    }

    @Test
    fun `the cards Home no longer draws have no switch`() {
        assertFalse(DashboardWidget.BALANCE_SUMMARY.isSwitchable)
        assertFalse(DashboardWidget.MONTH_SUMMARY.isSwitchable)
        assertTrue(DashboardWidget.UPCOMING_BILLS.isSwitchable)
        assertEquals("Coming up", DashboardWidget.UPCOMING_BILLS.title)
    }
}
