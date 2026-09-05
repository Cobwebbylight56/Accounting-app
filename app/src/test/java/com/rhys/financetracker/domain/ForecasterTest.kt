package com.rhys.financetracker.domain

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.MonthTotals
import com.rhys.financetracker.domain.insight.Forecast
import com.rhys.financetracker.domain.insight.Forecaster
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * A forecast that is confidently wrong is worse than none, so these check the
 * two things that matter: that known amounts land in the month they are
 * actually due, and that the app declines to project at all when it has not
 * seen enough.
 */
class ForecasterTest {

    private val forecaster = Forecaster()
    private val today = LocalDate.of(2026, 3, 10)

    private fun rule(
        amountMajorMinor: Long,
        type: TransactionType,
        frequency: Frequency = Frequency.MONTHLY,
        start: LocalDate = LocalDate.of(2026, 1, 15),
    ) = RecurringRuleEntity(
        id = 0L,
        name = "Rule",
        amountMinor = amountMajorMinor,
        type = type,
        frequency = frequency,
        startDate = start,
        nextDueDate = start,
        accountId = 1L,
    )

    private fun history(months: Int, income: Long, expense: Long): List<MonthTotals> =
        (1..months).map { back ->
            val month = YearMonth.of(2026, 3).minusMonths(back.toLong())
            MonthTotals(month.toString(), income, expense)
        }

    @Test
    fun `with no history the forecast is marked unreliable`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 100_000L,
            history = emptyList(),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertFalse(forecast.isReliable)
        assertEquals(0, forecast.monthsOfHistory)
    }

    @Test
    fun `two complete months is enough to project`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 100_000L,
            history = history(2, income = 300_000L, expense = 250_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertTrue(forecast.isReliable)
        assertEquals(Forecast.MIN_MONTHS_FOR_FORECAST, forecast.monthsOfHistory)
    }

    @Test
    fun `the typical monthly net comes from the completed months`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 0L,
            history = history(3, income = 300_000L, expense = 250_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertEquals(50_000L, forecast.typicalMonthlyNetMinor)
    }

    @Test
    fun `an annual bill is counted once, in the month it falls due`() {
        // A yearly premium due in July must not be smeared across every month.
        val annual = rule(
            amountMajorMinor = 24_000L,
            type = TransactionType.EXPENSE,
            frequency = Frequency.YEARLY,
            start = LocalDate.of(2026, 7, 1),
        )
        val july = forecaster.expectedBetween(
            listOf(annual),
            TransactionType.EXPENSE,
            LocalDate.of(2026, 7, 1),
            LocalDate.of(2026, 7, 31),
        )
        val august = forecaster.expectedBetween(
            listOf(annual),
            TransactionType.EXPENSE,
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 31),
        )
        assertEquals(24_000L, july)
        assertEquals(0L, august)
    }

    @Test
    fun `a weekly bill is counted every time it falls in the window`() {
        val weekly = rule(
            amountMajorMinor = 1_000L,
            type = TransactionType.EXPENSE,
            frequency = Frequency.WEEKLY,
            start = LocalDate.of(2026, 3, 2),
        )
        val march = forecaster.expectedBetween(
            listOf(weekly),
            TransactionType.EXPENSE,
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 31),
        )
        // 2, 9, 16, 23, 30 March = five payments.
        assertEquals(5_000L, march)
    }

    @Test
    fun `a paused rule is not counted`() {
        val paused = rule(5_000L, TransactionType.EXPENSE).copy(isPaused = true)
        assertEquals(
            0L,
            forecaster.expectedBetween(
                listOf(paused),
                TransactionType.EXPENSE,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 12, 31),
            ),
        )
    }

    @Test
    fun `the projection covers this month plus six`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 100_000L,
            history = history(3, income = 300_000L, expense = 250_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertEquals(7, forecast.monthlyProjection.size)
        assertEquals(YearMonth.of(2026, 3), forecast.monthlyProjection.first().month)
        assertEquals(YearMonth.of(2026, 9), forecast.monthlyProjection.last().month)
        assertFalse(forecast.monthlyProjection.first().isProjected)
        assertTrue(forecast.monthlyProjection.last().isProjected)
    }

    @Test
    fun `a household spending more than it earns is told when it runs out`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 50_000L,
            history = history(3, income = 200_000L, expense = 260_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertNotNull("a shortfall should be predicted", forecast.firstShortfallMonth)
    }

    @Test
    fun `a household living within its means is not warned`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 500_000L,
            history = history(3, income = 300_000L, expense = 200_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        assertNull(forecast.firstShortfallMonth)
    }

    @Test
    fun `each month opens where the last one closed`() {
        val forecast = forecaster.forecast(
            openingBalanceMinor = 100_000L,
            history = history(3, income = 300_000L, expense = 250_000L),
            rules = emptyList(),
            spentSoFarMinor = 0L,
            receivedSoFarMinor = 0L,
            today = today,
        )
        forecast.monthlyProjection.zipWithNext().forEach { (earlier, later) ->
            assertEquals(
                "the projected balance must carry over",
                earlier.closingBalanceMinor,
                later.openingBalanceMinor,
            )
        }
    }
}
