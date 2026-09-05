package com.rhys.financetracker.domain.insight

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.MonthTotals
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out what the months ahead are likely to look like.
 *
 * The method is deliberately boring, because a forecast that is clever and
 * wrong is worse than one that is simple and roughly right:
 *
 *  * **Known amounts are used as known.** Every recurring rule's occurrences are
 *    counted exactly, using the same engine that actually posts them, so a
 *    quarterly water bill lands in the right month rather than being smeared
 *    across all of them.
 *  * **Unknown amounts come from the household's own average.** Discretionary
 *    spending — everything not covered by a rule — is taken as the mean of the
 *    completed months on record.
 *  * **Nothing is projected from one month.** Below
 *    [Forecast.MIN_MONTHS_FOR_FORECAST] months of history the forecast is marked
 *    unreliable and the UI says so rather than drawing a confident line.
 *
 * All of this is pure arithmetic on values passed in, so it is fully testable
 * without a database.
 */
@Singleton
class Forecaster @Inject constructor() {

    /** How far ahead to project. Beyond this, a household budget is fiction. */
    private companion object {
        const val MONTHS_AHEAD = 6
    }

    /**
     * @param openingBalanceMinor spendable balance today, excluding savings.
     * @param history completed months, oldest first, excluding the current one.
     * @param rules every active recurring rule.
     * @param spentSoFarMinor what has already gone out this month.
     * @param receivedSoFarMinor what has already come in this month.
     */
    fun forecast(
        openingBalanceMinor: Long,
        history: List<MonthTotals>,
        rules: List<RecurringRuleEntity>,
        spentSoFarMinor: Long,
        receivedSoFarMinor: Long,
        today: LocalDate = DateUtils.today(),
    ): Forecast {
        val thisMonth = YearMonth.from(today)
        val monthEnd = thisMonth.atEndOfMonth()

        // --- what is still definitely coming this month ---------------------
        val remainingBills = expectedBetween(rules, TransactionType.EXPENSE, today, monthEnd)
        val remainingIncome = expectedBetween(rules, TransactionType.INCOME, today, monthEnd)
        val remainingSavings = expectedBetween(rules, TransactionType.TRANSFER, today, monthEnd)

        // --- the household's own averages -----------------------------------
        val completed = history.filter { it.yearMonth != DateUtils.yearMonthKey(thisMonth) }
        val monthsOfHistory = completed.size
        val averageIncome = completed.map { it.incomeMinor }.averageOrZero()
        val averageSpending = completed.map { it.expenseMinor }.averageOrZero()
        val typicalNet = averageIncome - averageSpending

        // Spending that no rule explains — the shopping, the days out — taken
        // as an average and assumed to continue at the same rate.
        val ruleDrivenMonthly = rules
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf {
                RecurrenceCalculator.monthlyEquivalentMinor(it.amountMinor, it.frequency, it.interval)
            }
        val discretionaryMonthly = (averageSpending - ruleDrivenMonthly).coerceAtLeast(0L)

        // --- the rest of this month -----------------------------------------
        val daysLeft = DateUtils.daysBetween(today, monthEnd).coerceAtLeast(0)
        val daysInMonth = thisMonth.lengthOfMonth()
        val discretionaryLeft = discretionaryMonthly * daysLeft / daysInMonth

        val endOfMonth = openingBalanceMinor + remainingIncome -
            remainingBills - remainingSavings - discretionaryLeft

        // --- the months after that -------------------------------------------
        val projection = mutableListOf(
            ProjectedMonth(
                month = thisMonth,
                openingBalanceMinor = openingBalanceMinor,
                expectedIncomeMinor = receivedSoFarMinor + remainingIncome,
                expectedSpendingMinor = spentSoFarMinor + remainingBills + remainingSavings +
                    discretionaryLeft,
                closingBalanceMinor = endOfMonth,
                isProjected = false,
            ),
        )

        var balance = endOfMonth
        var firstShortfall: YearMonth? = endOfMonth.takeIf { it < 0L }?.let { thisMonth }

        for (step in 1..MONTHS_AHEAD) {
            val month = thisMonth.plusMonths(step.toLong())
            val start = month.atDay(1)
            val end = month.atEndOfMonth()

            val income = expectedBetween(rules, TransactionType.INCOME, start, end)
                // Fall back to the average when nothing is set up, so a
                // household that records income by hand still gets a line.
                .takeIf { it > 0L } ?: averageIncome
            val bills = expectedBetween(rules, TransactionType.EXPENSE, start, end)
            val savings = expectedBetween(rules, TransactionType.TRANSFER, start, end)
            val spending = bills + savings + discretionaryMonthly

            val opening = balance
            balance = opening + income - spending
            if (firstShortfall == null && balance < 0L) firstShortfall = month

            projection += ProjectedMonth(
                month = month,
                openingBalanceMinor = opening,
                expectedIncomeMinor = income,
                expectedSpendingMinor = spending,
                closingBalanceMinor = balance,
                isProjected = true,
            )
        }

        return Forecast(
            endOfMonthBalanceMinor = endOfMonth,
            remainingBillsMinor = remainingBills + remainingSavings,
            remainingIncomeMinor = remainingIncome,
            monthlyProjection = projection,
            firstShortfallMonth = firstShortfall,
            typicalMonthlyNetMinor = typicalNet,
            monthsOfHistory = monthsOfHistory,
        )
    }

    /**
     * The total of every occurrence of every rule of [type] falling between the
     * two dates, counted exactly rather than averaged — so an annual insurance
     * premium appears once, in the month it is actually due.
     */
    internal fun expectedBetween(
        rules: List<RecurringRuleEntity>,
        type: TransactionType,
        from: LocalDate,
        to: LocalDate,
    ): Long = rules
        .filter { it.type == type && !it.isPaused && !it.isArchived }
        .sumOf { rule ->
            RecurrenceCalculator.upcomingOccurrences(rule, from, to).size * rule.amountMinor
        }

    private fun List<Long>.averageOrZero(): Long =
        if (isEmpty()) 0L else sum() / size
}
