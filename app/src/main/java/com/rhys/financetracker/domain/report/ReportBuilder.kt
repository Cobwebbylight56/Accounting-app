package com.rhys.financetracker.domain.report

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.SavingsGoalDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.domain.model.TransactionType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Assembles every report the app offers.
 *
 * Reports are built as plain data ([Report]) rather than as views, so the same
 * object drives the on-screen report, the PDF, the CSV and the Excel export.
 * Adding a new report means adding a `ReportType` and a `build…` function here;
 * nothing in the UI or the exporters has to change.
 */
@Singleton
class ReportBuilder @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val savingsGoalDao: SavingsGoalDao,
) {

    suspend fun build(
        type: ReportType,
        period: ReportPeriod,
        scope: ReportScope = ReportScope.HOUSEHOLD,
    ): Report = when (type) {
        ReportType.MONTHLY_SPENDING -> monthlySpending(period, scope)
        ReportType.YEARLY_SPENDING -> yearlySpending(period, scope)
        ReportType.CATEGORY_BREAKDOWN -> categoryBreakdown(period, scope)
        ReportType.INCOME_VS_EXPENSES -> incomeVsExpenses(period, scope)
        ReportType.ACCOUNT_BALANCES -> accountBalances(period, scope)
        ReportType.NET_WORTH -> netWorth(period, scope)
        ReportType.CASH_FLOW -> cashFlow(period, scope)
        ReportType.SAVINGS_HISTORY -> savingsHistory(period, scope)
        ReportType.FULL_SUMMARY -> fullSummary(period, scope)
    }

    // ------------------------------------------------------------- reports

    private suspend fun monthlySpending(period: ReportPeriod, scope: ReportScope): Report {
        val expenses = transactionDao.getCategoryTotals(
            TransactionType.EXPENSE.name, period.start, period.end, scope.accountId, scope.personId,
        )
        val totals = transactionDao.getIncomeExpense(
            period.start, period.end, scope.accountId, scope.personId,
        )
        val expenseTotal = expenses.sumOf { it.totalMinor }

        val sections = listOf(
            ReportSection(
                title = "Summary",
                rows = listOf(
                    ReportRow("Income", Money.format(totals?.incomeMinor ?: 0L)),
                    ReportRow("Expenses", Money.format(totals?.expenseMinor ?: 0L)),
                    ReportRow(
                        label = "Left over",
                        value = Money.format((totals?.netMinor ?: 0L), showSign = true),
                        isTotal = true,
                    ),
                ),
            ),
            ReportSection(
                title = "Spending by category",
                rows = expenses.map { category ->
                    ReportRow(
                        label = category.categoryName ?: "Uncategorised",
                        value = Money.format(category.totalMinor),
                        secondary = percentText(category.totalMinor, expenseTotal),
                        colorHex = category.categoryColor,
                    )
                } + ReportRow("Total spent", Money.format(expenseTotal), isTotal = true),
                note = if (expenses.isEmpty()) "Nothing was spent in this period." else null,
            ),
        )

        return Report(
            type = ReportType.MONTHLY_SPENDING,
            title = "Monthly spending",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = sections,
            charts = ReportCharts(categoryTotals = expenses),
        )
    }

    private suspend fun yearlySpending(period: ReportPeriod, scope: ReportScope): Report {
        val months = transactionDao.getMonthlyTotals(
            period.start, period.end, scope.accountId, scope.personId,
        )
        val series = months.mapNotNull { row ->
            DateUtils.parseYearMonthKey(row.yearMonth)?.let {
                MonthPoint(it, row.incomeMinor, row.expenseMinor)
            }
        }
        val incomeTotal = series.sumOf { it.incomeMinor }
        val expenseTotal = series.sumOf { it.expenseMinor }

        val rows = series.map { point ->
            ReportRow(
                label = DateUtils.formatMonth(point.yearMonth),
                value = Money.format(point.expenseMinor),
                secondary = Money.format(point.incomeMinor),
            )
        } + ReportRow(
            label = "Total",
            value = Money.format(expenseTotal),
            secondary = Money.format(incomeTotal),
            isTotal = true,
        )

        return Report(
            type = ReportType.YEARLY_SPENDING,
            title = "Yearly spending",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "Month by month",
                    rows = rows,
                    note = "The second column is income; the first is spending.",
                ),
                ReportSection(
                    title = "Averages",
                    rows = listOf(
                        ReportRow(
                            "Average monthly spend",
                            Money.format(average(expenseTotal, series.size)),
                        ),
                        ReportRow(
                            "Average monthly income",
                            Money.format(average(incomeTotal, series.size)),
                        ),
                        ReportRow(
                            "Average left over",
                            Money.format(average(incomeTotal - expenseTotal, series.size), showSign = true),
                            isTotal = true,
                        ),
                    ),
                ),
            ),
            charts = ReportCharts(monthlySeries = series),
        )
    }

    private suspend fun categoryBreakdown(period: ReportPeriod, scope: ReportScope): Report {
        val expenses = transactionDao.getCategoryTotals(
            TransactionType.EXPENSE.name, period.start, period.end, scope.accountId, scope.personId,
        )
        val income = transactionDao.getCategoryTotals(
            TransactionType.INCOME.name, period.start, period.end, scope.accountId, scope.personId,
        )
        val expenseTotal = expenses.sumOf { it.totalMinor }
        val incomeTotal = income.sumOf { it.totalMinor }

        return Report(
            type = ReportType.CATEGORY_BREAKDOWN,
            title = "Category breakdown",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "Money out",
                    rows = expenses.map {
                        ReportRow(
                            label = it.categoryName ?: "Uncategorised",
                            value = Money.format(it.totalMinor),
                            secondary = "${it.transactionCount} entries · " +
                                percentText(it.totalMinor, expenseTotal),
                            colorHex = it.categoryColor,
                        )
                    } + ReportRow("Total", Money.format(expenseTotal), isTotal = true),
                ),
                ReportSection(
                    title = "Money in",
                    rows = income.map {
                        ReportRow(
                            label = it.categoryName ?: "Uncategorised",
                            value = Money.format(it.totalMinor),
                            secondary = "${it.transactionCount} entries · " +
                                percentText(it.totalMinor, incomeTotal),
                            colorHex = it.categoryColor,
                        )
                    } + ReportRow("Total", Money.format(incomeTotal), isTotal = true),
                ),
            ),
            charts = ReportCharts(categoryTotals = expenses),
        )
    }

    private suspend fun incomeVsExpenses(period: ReportPeriod, scope: ReportScope): Report {
        val months = transactionDao.getMonthlyTotals(
            period.start, period.end, scope.accountId, scope.personId,
        )
        val series = months.mapNotNull { row ->
            DateUtils.parseYearMonthKey(row.yearMonth)?.let {
                MonthPoint(it, row.incomeMinor, row.expenseMinor)
            }
        }
        val incomeTotal = series.sumOf { it.incomeMinor }
        val expenseTotal = series.sumOf { it.expenseMinor }
        val savedMonths = series.count { it.netMinor >= 0 }

        return Report(
            type = ReportType.INCOME_VS_EXPENSES,
            title = "Income vs expenses",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "Totals",
                    rows = listOf(
                        ReportRow("Total income", Money.format(incomeTotal)),
                        ReportRow("Total expenses", Money.format(expenseTotal)),
                        ReportRow(
                            "Difference",
                            Money.format(incomeTotal - expenseTotal, showSign = true),
                            isTotal = true,
                        ),
                        ReportRow(
                            "Months in the black",
                            "$savedMonths of ${series.size}",
                        ),
                        ReportRow(
                            "Share of income spent",
                            percentText(expenseTotal, incomeTotal),
                        ),
                    ),
                ),
                ReportSection(
                    title = "By month",
                    rows = series.map {
                        ReportRow(
                            label = DateUtils.formatMonth(it.yearMonth),
                            value = Money.format(it.netMinor, showSign = true),
                            secondary = "${Money.format(it.incomeMinor)} in · " +
                                "${Money.format(it.expenseMinor)} out",
                        )
                    },
                ),
            ),
            charts = ReportCharts(monthlySeries = series),
        )
    }

    private suspend fun accountBalances(period: ReportPeriod, scope: ReportScope): Report {
        val accounts = accountDao.observeActiveWithBalances().first()
            .filter { scope.matches(it) }

        val grouped = accounts.groupBy { it.account.type }
        val sections = grouped.map { (type, items) ->
            ReportSection(
                title = type.displayName,
                rows = items.map {
                    ReportRow(
                        label = it.account.name,
                        value = Money.format(it.balanceMinor),
                        secondary = it.personName,
                        colorHex = it.account.colorHex,
                    )
                } + ReportRow(
                    "Subtotal",
                    Money.format(items.sumOf { it.balanceMinor }),
                    isTotal = true,
                ),
            )
        }

        return Report(
            type = ReportType.ACCOUNT_BALANCES,
            title = "Account balances",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = sections + ReportSection(
                title = "Overall",
                rows = listOf(
                    ReportRow(
                        "Total across all accounts",
                        Money.format(accounts.sumOf { it.balanceMinor }),
                        isTotal = true,
                    ),
                ),
            ),
            charts = ReportCharts(accountBalances = accounts),
        )
    }

    private suspend fun netWorth(period: ReportPeriod, scope: ReportScope): Report {
        val accounts = accountDao.observeActiveWithBalances().first()
            .filter { scope.matches(it) && it.account.includeInNetWorth }

        val assets = accounts.filterNot { it.isLiability }
        val liabilities = accounts.filter { it.isLiability }
        val assetTotal = assets.sumOf { it.balanceMinor }
        // Liability balances are held as negatives; show them as positive debts.
        val liabilityTotal = liabilities.sumOf { it.balanceMinor }

        val slices = accounts
            .groupBy { it.account.type }
            .map { (type, items) -> AccountTypeSlice(type, items.sumOf { it.balanceMinor }) }
            .sortedByDescending { it.totalMinor }

        return Report(
            type = ReportType.NET_WORTH,
            title = "Net worth",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "What you own",
                    rows = assets.map {
                        ReportRow(it.account.name, Money.format(it.balanceMinor), it.personName)
                    } + ReportRow("Total assets", Money.format(assetTotal), isTotal = true),
                ),
                ReportSection(
                    title = "What you owe",
                    rows = liabilities.map {
                        ReportRow(it.account.name, Money.format(it.balanceMinor), it.personName)
                    } + ReportRow("Total debts", Money.format(liabilityTotal), isTotal = true),
                    note = if (liabilities.isEmpty()) "No debts recorded." else null,
                ),
                ReportSection(
                    title = "Net worth",
                    rows = listOf(
                        ReportRow(
                            "Assets less debts",
                            Money.format(assetTotal + liabilityTotal, showSign = true),
                            isTotal = true,
                        ),
                    ),
                ),
            ),
            charts = ReportCharts(accountBalances = accounts, typeTotals = slices),
        )
    }

    private suspend fun cashFlow(period: ReportPeriod, scope: ReportScope): Report {
        val transactions = transactionDao.getBetween(period.start, period.end)
            .filter { scope.accountId == null || it.accountId == scope.accountId }

        // Start from the balance the day before the period so the line is
        // absolute rather than relative.
        val openingBalance = if (scope.accountId != null) {
            accountDao.getBalanceAsOf(scope.accountId, period.start.minusDays(1)) ?: 0L
        } else {
            accountDao.getAllActive().sumOf {
                accountDao.getBalanceAsOf(it.id, period.start.minusDays(1)) ?: 0L
            }
        }

        var running = openingBalance
        val byDate = transactions.groupBy { it.date }.toSortedMap()
        val series = mutableListOf(DatedAmount(period.start.minusDays(1), openingBalance))
        for ((date, entries) in byDate) {
            running += entries.sumOf { entry ->
                when (entry.type) {
                    TransactionType.INCOME -> entry.amountMinor
                    TransactionType.EXPENSE -> -entry.amountMinor
                    // A transfer between two accounts we hold nets to zero
                    // across the household, so it only moves the line when a
                    // single account is in scope.
                    TransactionType.TRANSFER ->
                        if (scope.accountId != null) -entry.amountMinor else 0L
                }
            }
            series += DatedAmount(date, running)
        }

        val lowest = series.minByOrNull { it.amountMinor }
        val highest = series.maxByOrNull { it.amountMinor }

        return Report(
            type = ReportType.CASH_FLOW,
            title = "Cash flow",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "Movement",
                    rows = listOfNotNull(
                        ReportRow("Opening balance", Money.format(openingBalance)),
                        ReportRow("Closing balance", Money.format(running)),
                        ReportRow(
                            "Change over the period",
                            Money.format(running - openingBalance, showSign = true),
                            isTotal = true,
                        ),
                        lowest?.let {
                            ReportRow(
                                "Lowest point",
                                Money.format(it.amountMinor),
                                DateUtils.format(it.date),
                            )
                        },
                        highest?.let {
                            ReportRow(
                                "Highest point",
                                Money.format(it.amountMinor),
                                DateUtils.format(it.date),
                            )
                        },
                    ),
                ),
            ),
            charts = ReportCharts(balanceSeries = series),
        )
    }

    private suspend fun savingsHistory(period: ReportPeriod, scope: ReportScope): Report {
        val goals = savingsGoalDao.observeActiveWithProgress().first()
        val savingsAccounts = accountDao.observeActiveWithBalances().first()
            .filter { it.isSavings && scope.matches(it) }

        return Report(
            type = ReportType.SAVINGS_HISTORY,
            title = "Savings",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = listOf(
                ReportSection(
                    title = "Goals",
                    rows = goals.map { goal ->
                        ReportRow(
                            label = goal.goal.name,
                            value = "${Money.format(goal.currentAmountMinor)} of " +
                                Money.format(goal.goal.targetAmountMinor),
                            secondary = "${goal.percentComplete}% · " +
                                "${Money.format(goal.remainingMinor)} to go",
                            colorHex = goal.goal.colorHex,
                        )
                    },
                    note = if (goals.isEmpty()) "No savings goals have been set up yet." else null,
                ),
                ReportSection(
                    title = "Savings accounts",
                    rows = savingsAccounts.map {
                        ReportRow(it.account.name, Money.format(it.balanceMinor), it.personName)
                    } + ReportRow(
                        "Total saved",
                        Money.format(savingsAccounts.sumOf { it.balanceMinor }),
                        isTotal = true,
                    ),
                ),
            ),
        )
    }

    /** Every section in one document — the "printable financial summary". */
    private suspend fun fullSummary(period: ReportPeriod, scope: ReportScope): Report {
        val parts = listOf(
            incomeVsExpenses(period, scope),
            categoryBreakdown(period, scope),
            accountBalances(period, scope),
            netWorth(period, scope),
            savingsHistory(period, scope),
        )
        return Report(
            type = ReportType.FULL_SUMMARY,
            title = "Financial summary",
            period = period,
            scope = scope,
            generatedOn = DateUtils.today(),
            sections = parts.flatMap { report ->
                report.sections.map { it.copy(title = "${report.title} — ${it.title}") }
            },
            charts = ReportCharts(
                categoryTotals = parts[1].charts.categoryTotals,
                monthlySeries = parts[0].charts.monthlySeries,
                accountBalances = parts[2].charts.accountBalances,
                typeTotals = parts[3].charts.typeTotals,
            ),
        )
    }

    // ------------------------------------------------------------- helpers

    private fun average(totalMinor: Long, count: Int): Long =
        if (count <= 0) 0L else totalMinor / count

    private fun percentText(part: Long, whole: Long): String =
        if (whole == 0L) "—" else "${((part.toDouble() / whole) * 100).roundToInt()}%"
}

/** True when [account] belongs to the slice this scope describes. */
private fun ReportScope.matches(account: AccountWithBalance): Boolean = when {
    accountId != null -> account.account.id == accountId
    personId != null -> account.account.personId == personId
    else -> true
}
