package com.rhys.financetracker.ui

import com.rhys.financetracker.domain.model.DashboardWidget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseThreeHomeTest {

    @Test
    fun `cards that repeat the top of Home start switched off`() {
        val repeats = DashboardWidget.entries.filter { it.repeatsHome }
        assertTrue(repeats.isNotEmpty())
        repeats.forEach { assertFalse("${it.title} should start off", it.defaultVisible) }
    }

    @Test
    fun `the cards Home keeps by default each say something new`() {
        val shown = DashboardWidget.entries.filter { it.defaultVisible && it.isSwitchable }
        assertTrue(DashboardWidget.CATEGORY_TILES in shown)
        assertTrue(DashboardWidget.UPCOMING_BILLS in shown)
        assertTrue(DashboardWidget.RECENT_TRANSACTIONS in shown)
        assertFalse(DashboardWidget.SPENDING_BY_CATEGORY in shown)
        assertFalse(DashboardWidget.DISPOSABLE_INCOME in shown)
    }
}
