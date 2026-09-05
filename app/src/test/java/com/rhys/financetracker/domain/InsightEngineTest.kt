package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.insight.CategoryTrend
import com.rhys.financetracker.domain.insight.Forecast
import com.rhys.financetracker.domain.insight.InsightEngine
import com.rhys.financetracker.domain.insight.InsightKind
import com.rhys.financetracker.domain.insight.InsightSeverity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Advice has to be right, specific and quiet when it has nothing to say. These
 * check all three: that a real change is reported with its figure, that noise
 * is not, and that a rule with too little history keeps its mouth shut.
 */
class InsightEngineTest {

    private val engine = InsightEngine()

    /** Late enough in the month that comparing against a monthly average is fair. */
    private val today = LocalDate.of(2026, 3, 20)

    private fun trend(
        name: String,
        thisMonth: Long,
        average: Long,
        months: Int = 3,
        budget: Long? = null,
    ) = CategoryTrend(
        categoryId = name.hashCode().toLong(),
        name = name,
        colorHex = "#AD1457",
        thisMonthMinor = thisMonth,
        averageMinor = average,
        monthsCompared = months,
        budgetMinor = budget,
    )

    private fun rule(name: String, amountMinor: Long, frequency: Frequency = Frequency.MONTHLY) =
        RecurringRuleEntity(
            id = name.hashCode().toLong(),
            name = name,
            amountMinor = amountMinor,
            type = TransactionType.EXPENSE,
            frequency = frequency,
            startDate = LocalDate.of(2026, 1, 1),
            nextDueDate = LocalDate.of(2026, 4, 1),
            accountId = 1L,
        )

    // ------------------------------------------------------------- spending

    @Test
    fun `a real rise is reported with the amount and the percentage`() {
        // £90 against a usual £60: a third up and £30 in cash terms.
        val insights = engine.spendingAdvice(listOf(trend("Takeaways", 9_000L, 6_000L)), today)
        val rise = insights.first { it.id.startsWith("spend-up") }
        assertEquals(InsightSeverity.WATCH, rise.severity)
        assertTrue(rise.message.contains("£90.00"))
        assertTrue(rise.message.contains("£30.00"))
        assertTrue(rise.message.contains("50%"))
        // £30 a month is £360 a year, which is the figure that changes minds.
        assertEquals(36_000L, rise.annualImpactMinor)
    }

    @Test
    fun `a rise that is large in percentage but trivial in money stays quiet`() {
        // £3 against £1: 200% up, and completely meaningless.
        val insights = engine.spendingAdvice(listOf(trend("Parking", 300L, 100L)), today)
        assertTrue(insights.none { it.id.startsWith("spend-up") })
    }

    @Test
    fun `a small percentage change stays quiet even when the sum is large`() {
        // £1,020 against £1,000: only 2% up.
        val insights = engine.spendingAdvice(listOf(trend("Mortgage", 102_000L, 100_000L)), today)
        assertTrue(insights.none { it.id.startsWith("spend-up") })
    }

    @Test
    fun `a fall is reported as good news`() {
        val insights = engine.spendingAdvice(listOf(trend("Fuel", 4_000L, 8_000L)), today)
        val fall = insights.first { it.id.startsWith("spend-down") }
        assertEquals(InsightSeverity.GOOD, fall.severity)
        assertTrue(fall.message.contains("£40.00"))
    }

    @Test
    fun `nothing is said early in the month, when any comparison would flatter`() {
        // The 3rd: a whole month's average against three days is meaningless.
        val early = LocalDate.of(2026, 3, 3)
        val insights = engine.spendingAdvice(listOf(trend("Takeaways", 9_000L, 6_000L)), early)
        assertTrue(insights.none { it.id.startsWith("spend-up") })
    }

    @Test
    fun `nothing is said without at least two months to compare against`() {
        val insights = engine.spendingAdvice(
            listOf(trend("Takeaways", 9_000L, 6_000L, months = 1)),
            today,
        )
        assertTrue(insights.none { it.id.startsWith("spend-up") })
    }

    @Test
    fun `going over a category budget is flagged for action`() {
        val insights = engine.spendingAdvice(
            listOf(trend("Groceries", 30_000L, 25_000L, budget = 25_000L)),
            today,
        )
        val over = insights.first { it.id.startsWith("over-budget") }
        assertEquals(InsightSeverity.ACT, over.severity)
        assertTrue(over.message.contains("£50.00"))
    }

    @Test
    fun `the biggest three categories are summarised with what trimming them is worth`() {
        val insights = engine.spendingAdvice(
            listOf(
                trend("Groceries", 50_000L, 50_000L),
                trend("Fuel", 30_000L, 30_000L),
                trend("Eating out", 20_000L, 20_000L),
                trend("Pets", 2_000L, 2_000L),
            ),
            today,
        )
        val top = insights.first { it.id == "top-categories" }
        // £1,000 across the top three; a tenth of that over a year is £1,200.
        assertEquals(120_000L, top.annualImpactMinor)
        assertTrue(top.message.contains("Groceries"))
    }

    // -------------------------------------------------------------- savings

    @Test
    fun `a good savings rate is recognised, not just a bad one`() {
        val insights = engine.savingsAdvice(
            incomeMinor = 300_000L,
            savedThisMonthMinor = 60_000L,
            savingsBalanceMinor = 1_000_000L,
            rules = emptyList(),
        )
        val rate = insights.first { it.id.startsWith("savings-rate") }
        assertEquals(InsightSeverity.GOOD, rate.severity)
        assertTrue(rate.title.contains("20%"))
    }

    @Test
    fun `saving almost nothing is flagged with an achievable next step`() {
        val insights = engine.savingsAdvice(
            incomeMinor = 300_000L,
            savedThisMonthMinor = 1_000L,
            savingsBalanceMinor = 0L,
            rules = emptyList(),
        )
        val rate = insights.first { it.id == "savings-rate-low" }
        assertEquals(InsightSeverity.WATCH, rate.severity)
        // Suggests 5% -- £150 a month -- rather than an unreachable figure.
        assertTrue(rate.message.contains("£150.00"))
    }

    @Test
    fun `the emergency fund target is three months of the real bills`() {
        val insights = engine.savingsAdvice(
            incomeMinor = 300_000L,
            savedThisMonthMinor = 30_000L,
            savingsBalanceMinor = 100_000L,
            rules = listOf(rule("Rent", 80_000L), rule("Energy", 20_000L)),
        )
        val fund = insights.first { it.id == "emergency-fund" }
        // £1,000 of monthly bills, so the target is £3,000 and £2,000 is short.
        assertTrue(fund.message.contains("£3,000.00"))
        assertTrue(fund.message.contains("£2,000.00"))
    }

    @Test
    fun `no advice is offered when nothing has come in`() {
        assertTrue(
            engine.savingsAdvice(
                incomeMinor = 0L,
                savedThisMonthMinor = 0L,
                savingsBalanceMinor = 0L,
                rules = emptyList(),
            ).isEmpty(),
        )
    }

    // ---------------------------------------------------------------- bills

    @Test
    fun `overdue payments are the most urgent thing on the list`() {
        val insights = engine.billsAdvice(
            rules = emptyList(),
            spendingMinor = 100_000L,
            unconfirmedCount = 0,
            overdueCount = 2,
        )
        assertEquals(InsightSeverity.ACT, insights.first { it.id == "overdue" }.severity)
    }

    @Test
    fun `small regular payments are added up, because that is how they hide`() {
        val insights = engine.billsAdvice(
            rules = listOf(
                rule("Netflix", 1_099L),
                rule("Spotify", 1_199L),
                rule("YouTube", 1_299L),
                rule("Cloud storage", 799L),
            ),
            spendingMinor = 100_000L,
            unconfirmedCount = 0,
            overdueCount = 0,
        )
        val subscriptions = insights.first { it.id == "subscriptions" }
        // £43.96 a month is £527.52 a year.
        assertEquals(52_752L, subscriptions.annualImpactMinor)
        assertTrue(subscriptions.message.contains("Netflix"))
    }

    @Test
    fun `two subscriptions are not worth mentioning`() {
        val insights = engine.billsAdvice(
            rules = listOf(rule("Netflix", 1_099L), rule("Spotify", 1_199L)),
            spendingMinor = 100_000L,
            unconfirmedCount = 0,
            overdueCount = 0,
        )
        assertTrue(insights.none { it.id == "subscriptions" })
    }

    // ------------------------------------------------------------- forecast

    @Test
    fun `an unreliable forecast says so instead of guessing`() {
        val insights = engine.forecastAdvice(Forecast.EMPTY)
        assertEquals(1, insights.size)
        assertEquals("forecast-early", insights.first().id)
    }

    @Test
    fun `a predicted shortfall is flagged for action with the month named`() {
        val forecast = Forecast.EMPTY.copy(
            monthsOfHistory = 3,
            firstShortfallMonth = java.time.YearMonth.of(2026, 6),
            typicalMonthlyNetMinor = -20_000L,
        )
        val shortfall = engine.forecastAdvice(forecast).first { it.id == "shortfall" }
        assertEquals(InsightSeverity.ACT, shortfall.severity)
        assertTrue(shortfall.message.contains("June 2026"))
        assertTrue(shortfall.message.contains("£200.00"))
    }

    @Test
    fun `a surplus is projected over a year too`() {
        val forecast = Forecast.EMPTY.copy(
            monthsOfHistory = 4,
            typicalMonthlyNetMinor = 25_000L,
        )
        val annual = engine.forecastAdvice(forecast).first { it.id == "annual-trajectory" }
        assertEquals(InsightSeverity.GOOD, annual.severity)
        assertTrue(annual.message.contains("£3,000.00"))
    }

    // -------------------------------------------------------------- overall

    @Test
    fun `the most urgent advice comes first`() {
        val report = engine.analyse(
            month = java.time.YearMonth.of(2026, 3),
            trends = listOf(trend("Groceries", 30_000L, 25_000L, budget = 25_000L)),
            forecast = Forecast.EMPTY.copy(monthsOfHistory = 3),
            incomeMinor = 300_000L,
            spendingMinor = 250_000L,
            savedThisMonthMinor = 50_000L,
            savingsBalanceMinor = 500_000L,
            goals = emptyList(),
            rules = emptyList(),
            unconfirmedCount = 0,
            overdueCount = 1,
            today = today,
        )
        assertEquals(InsightSeverity.ACT, report.insights.first().severity)
        assertTrue(report.insights.isNotEmpty())
    }

    @Test
    fun `the savings rate is reported as a fraction of income`() {
        val report = engine.analyse(
            month = java.time.YearMonth.of(2026, 3),
            trends = emptyList(),
            forecast = Forecast.EMPTY.copy(monthsOfHistory = 3),
            incomeMinor = 200_000L,
            spendingMinor = 150_000L,
            savedThisMonthMinor = 40_000L,
            savingsBalanceMinor = 0L,
            goals = emptyList(),
            rules = emptyList(),
            unconfirmedCount = 0,
            overdueCount = 0,
            today = today,
        )
        assertEquals(0.2f, report.savingsRate, 0.001f)
    }

    @Test
    fun `advice is grouped so a screen can show one kind at a time`() {
        val report = engine.analyse(
            month = java.time.YearMonth.of(2026, 3),
            trends = listOf(trend("Takeaways", 9_000L, 6_000L)),
            forecast = Forecast.EMPTY.copy(monthsOfHistory = 3),
            incomeMinor = 300_000L,
            spendingMinor = 250_000L,
            savedThisMonthMinor = 10_000L,
            savingsBalanceMinor = 0L,
            goals = emptyList(),
            rules = emptyList(),
            unconfirmedCount = 0,
            overdueCount = 0,
            today = today,
        )
        assertTrue(report.of(InsightKind.SPENDING).isNotEmpty())
        assertTrue(report.of(InsightKind.SAVING).isNotEmpty())
        assertFalse(report.of(InsightKind.SPENDING).any { it.kind != InsightKind.SPENDING })
    }
}
