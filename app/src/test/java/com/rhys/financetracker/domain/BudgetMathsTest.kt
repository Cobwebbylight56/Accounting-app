package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.budget.BudgetMaths
import com.rhys.financetracker.domain.budget.BudgetPlan
import com.rhys.financetracker.domain.budget.BudgetSource
import com.rhys.financetracker.domain.budget.Grade
import com.rhys.financetracker.domain.budget.LineGoal
import com.rhys.financetracker.domain.budget.LineStatus
import com.rhys.financetracker.domain.budget.MonthScore
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limits are kept or broken; aims like fuel can go over without it being a big problem. */
class BudgetMathsTest {

    private val month = YearMonth.of(2026, 10)
    private val phone = BudgetPlan(1, "Phone", 3_000)
    private val fuel = BudgetPlan(2, "Fuel", 15_000, aim = true)
    private val saver = BudgetPlan(3, "Saver", 20_000, goal = LineGoal.PUT_ASIDE_AT_LEAST)

    @Test
    fun `a limit over is over, an aim a little over is about right and well over is half marks`() {
        assertEquals(LineStatus.UNDER, BudgetMaths.status(phone, 3_000, 3_000, inProgress = false))
        assertEquals(LineStatus.OVER, BudgetMaths.status(phone, 3_000, 3_001, inProgress = false))
        assertEquals(LineStatus.UNDER, BudgetMaths.status(fuel, 15_000, 14_000, inProgress = false))
        assertEquals(LineStatus.ABOUT_RIGHT, BudgetMaths.status(fuel, 15_000, 18_000, inProgress = false))
        assertEquals(LineStatus.OVER_AIM, BudgetMaths.status(fuel, 15_000, 18_001, inProgress = false))
    }

    @Test
    fun `money put aside is met, short at the month's end, and not yet while it's going`() {
        assertEquals(LineStatus.MET, BudgetMaths.status(saver, 20_000, 20_000, inProgress = false))
        assertEquals(LineStatus.SHORT, BudgetMaths.status(saver, 20_000, 5_000, inProgress = false))
        assertEquals(LineStatus.NOT_YET, BudgetMaths.status(saver, 20_000, 5_000, inProgress = true))
        assertEquals(LineStatus.NO_BUDGET, BudgetMaths.status(phone, 0, 500, inProgress = false))
    }

    @Test
    fun `a month's budget is its change, else the average for an aim that follows it, else the usual`() {
        assertEquals(12_000L to BudgetSource.THIS_MONTH_ONLY, BudgetMaths.budgetFor(fuel, 12_000, listOf(20_000)))
        val following = fuel.copy(followAverage = true)
        // £180.50, £160.25 and nothing: the empty month is no evidence.
        assertEquals(17_100L to BudgetSource.AVERAGE, BudgetMaths.budgetFor(following, null, listOf(18_050, 16_025, 0)))
        assertEquals(15_000L to BudgetSource.SET, BudgetMaths.budgetFor(following, null, emptyList()))
        // A limit never follows its average.
        assertEquals(3_000L to BudgetSource.SET, BudgetMaths.budgetFor(phone.copy(followAverage = true), null, listOf(9_000)))
    }

    @Test
    fun `the score gives an aim gone over half marks, and adds up spending lines only`() {
        val results = listOf(
            BudgetMaths.result(phone, month, 3_000, null, emptyList(), inProgress = false),
            BudgetMaths.result(fuel, month, 25_000, null, emptyList(), inProgress = false),
            BudgetMaths.result(saver, month, 20_000, null, emptyList(), inProgress = false),
            BudgetMaths.result(BudgetPlan(4, "Gym", 0), month, 2_000, null, emptyList(), inProgress = false),
        )
        val score = BudgetMaths.score(month, results, inProgress = false)
        assertEquals(3, score.counted)
        assertEquals(2, score.kept)
        // (1 + 0.5 + 1) of 3.
        assertEquals(83, score.percent)
        assertEquals(Grade.GOOD, score.grade)
        assertEquals(18_000L, score.budgetMinor)
        assertEquals(28_000L, score.spentMinor)
        assertEquals(-10_000L, score.leftMinor)
    }

    @Test
    fun `a streak counts finished months within budget, newest first`() {
        fun s(m: Int, budget: Long, spent: Long, going: Boolean = false) =
            MonthScore(YearMonth.of(2026, m), going, 1, 1, budget, spent, 100, Grade.EXCELLENT)
        val scores = listOf(s(11, 100, 500, going = true), s(10, 100, 90), s(9, 100, 100), s(8, 100, 120), s(7, 100, 50))
        assertEquals(2, BudgetMaths.streak(scores))
    }

    @Test
    fun `a budget that doesn't fit gets a better amount`() {
        // A bill that went up: over in every month.
        val raise = BudgetMaths.suggestion(phone, listOf(3_000 to 3_499, 3_000 to 3_499, 3_000 to 3_499))!!
        assertTrue(raise.raise)
        assertEquals(3_500L, raise.toMinor)
        // Fuel over by under a fifth is fine; by more in two months, raise the aim.
        assertNull(BudgetMaths.suggestion(fuel, listOf(15_000 to 17_000, 15_000 to 17_500, 15_000 to 16_000)))
        assertEquals(19_500L, BudgetMaths.suggestion(fuel, listOf(15_000 to 19_000, 15_000 to 19_400, 15_000 to 19_100))!!.toMinor)
        // Well under every month: lower it.
        val lower = BudgetMaths.suggestion(BudgetPlan(5, "Clothes", 20_000, aim = true), listOf(20_000 to 5_000, 20_000 to 7_000))!!
        assertEquals(6_000L, lower.toMinor)
        // Too little to go on, or an aim that follows its average already.
        assertNull(BudgetMaths.suggestion(phone, listOf(3_000 to 9_000)))
        assertNull(BudgetMaths.suggestion(fuel.copy(followAverage = true), listOf(15_000 to 30_000, 15_000 to 30_000)))
    }
}
