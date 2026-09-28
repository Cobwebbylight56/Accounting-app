package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.income.IncomeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomeStatsTest {

    private val rhys = IncomeStats(grossYearlyMinor = 2_745_576L, netYearlyMinor = 2_234_676L)

    @Test
    fun `take-home a month and what is taken before it arrives`() {
        assertEquals(186_223L, rhys.netMonthlyMinor)
        assertEquals(510_900L, rhys.deductionsYearlyMinor)
        assertEquals(19, rhys.deductionPercent)
    }

    @Test
    fun `a month's spending and saving as a share of take-home`() {
        assertEquals(50, rhys.shareOfMonthlyTakeHome(93_112L))
        assertEquals(11, rhys.shareOfMonthlyTakeHome(20_000L))
    }

    @Test
    fun `nothing is invented when only one figure is given`() {
        val grossOnly = IncomeStats(grossYearlyMinor = 1_669_248L, netYearlyMinor = null)
        assertTrue(grossOnly.hasAny)
        assertNull(grossOnly.netMonthlyMinor)
        assertNull(grossOnly.deductionPercent)
        assertNull(grossOnly.shareOfMonthlyTakeHome(10_000L))
        assertTrue(!IncomeStats.NONE.hasAny)
    }

    @Test
    fun `a household is everybody's pay added together`() {
        val hannah = IncomeStats(grossYearlyMinor = 1_669_248L, netYearlyMinor = 1_736_400L - 100_000L)
        val both = rhys + hannah
        assertEquals(2_745_576L + 1_669_248L, both.grossYearlyMinor)
        assertEquals(2_234_676L + 1_636_400L, both.netYearlyMinor)
        assertEquals(rhys, rhys + IncomeStats.NONE)
    }
}
