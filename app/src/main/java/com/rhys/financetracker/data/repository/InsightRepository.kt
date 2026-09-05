package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.SavingsGoalDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.domain.insight.CategoryTrend
import com.rhys.financetracker.domain.insight.Forecaster
import com.rhys.financetracker.domain.insight.InsightEngine
import com.rhys.financetracker.domain.insight.InsightReport
import com.rhys.financetracker.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gathers everything the advice needs and hands it to the engine.
 *
 * The split is deliberate: this class knows about the database and nothing
 * about what makes good advice; [InsightEngine] and [Forecaster] know about the
 * advice and nothing about the database, which is what makes every rule
 * testable without one.
 */
@Singleton
class InsightRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val savingsGoalDao: SavingsGoalDao,
    private val insightEngine: InsightEngine,
    private val forecaster: Forecaster,
) {

    private companion object {
        /** How many completed months a category is compared against. */
        const val TREND_MONTHS = 3

        /** How much history the forecast reads. */
        const val HISTORY_MONTHS = 6
    }

    suspend fun buildReport(
        month: YearMonth = DateUtils.currentYearMonth(),
        personId: Long? = null,
        accountId: Long? = null,
    ): InsightReport {
        val today = DateUtils.today()
        val range = DateUtils.monthRange(month)

        val thisMonthTotals = transactionDao.getIncomeExpense(
            range.start, range.endInclusive, accountId, personId,
        )
        val income = thisMonthTotals?.incomeMinor ?: 0L
        val spending = thisMonthTotals?.expenseMinor ?: 0L

        val accounts = accountDao.observeActiveWithBalances().first()
        val inScope = accounts.filter { account ->
            when {
                accountId != null -> account.account.id == accountId
                personId != null -> account.account.personId == personId
                else -> true
            }
        }
        val spendable = inScope.filterNot { it.isSavings || it.isLiability }
            .sumOf { it.balanceMinor }
        val savingsBalance = inScope.filter { it.isSavings }.sumOf { it.balanceMinor }

        val history = transactionDao.getMonthlyTotals(
            start = month.minusMonths(HISTORY_MONTHS.toLong()).atDay(1),
            end = month.minusMonths(1).atEndOfMonth(),
            accountId = accountId,
            personId = personId,
        )

        val rules = recurringRuleDao.getAllActive()
        val goals = savingsGoalDao.observeActiveWithProgress().first()

        // Money moved into savings accounts this month, which is what "saved"
        // means here — not simply income less spending.
        val savedThisMonth = inScope
            .filter { it.isSavings }
            .sumOf { transactionDao.getTransfersIn(it.account.id, range.start, range.endInclusive) }

        val forecast = forecaster.forecast(
            openingBalanceMinor = spendable,
            history = history,
            rules = rules,
            spentSoFarMinor = spending,
            receivedSoFarMinor = income,
            today = today,
        )

        return insightEngine.analyse(
            month = month,
            trends = buildTrends(month, accountId, personId),
            forecast = forecast,
            incomeMinor = income,
            spendingMinor = spending,
            savedThisMonthMinor = savedThisMonth,
            savingsBalanceMinor = savingsBalance,
            goals = goals,
            rules = rules,
            unconfirmedCount = transactionDao.observeUnconfirmed().first().size,
            overdueCount = recurringRuleDao.observeOverdue(today).first().size,
            today = today,
        )
    }

    /**
     * This month against each category's own mean over the preceding months.
     *
     * The comparison is per category rather than overall, because "you spent
     * more this month" is useless — it is knowing *which* line moved that is
     * worth anything.
     */
    private suspend fun buildTrends(
        month: YearMonth,
        accountId: Long?,
        personId: Long?,
    ): List<CategoryTrend> {
        val range = DateUtils.monthRange(month)
        val current = transactionDao.getCategoryTotals(
            TransactionType.EXPENSE.name, range.start, range.endInclusive, accountId, personId,
        )

        // Each preceding month separately, so a category that only appears in
        // some of them is averaged over the months it was actually present.
        val previousMonths = (1..TREND_MONTHS).map { back ->
            val past = month.minusMonths(back.toLong())
            transactionDao.getCategoryTotals(
                TransactionType.EXPENSE.name,
                past.atDay(1),
                past.atEndOfMonth(),
                accountId,
                personId,
            )
        }

        // A category's own budget, when one has been set, so going over it can
        // be reported against the figure the user chose rather than an average.
        val budgets = categoryDao.getAll().associate { it.id to it.monthlyBudgetMinor }

        return current.map { total ->
            val history = previousMonths.mapNotNull { monthTotals ->
                monthTotals.firstOrNull { it.categoryId == total.categoryId }?.totalMinor
            }
            CategoryTrend(
                categoryId = total.categoryId,
                name = total.categoryName ?: "Uncategorised",
                colorHex = total.categoryColor,
                thisMonthMinor = total.totalMinor,
                averageMinor = if (history.isEmpty()) 0L else history.sum() / history.size,
                monthsCompared = history.size,
                budgetMinor = budgets[total.categoryId],
            )
        }
    }
}
