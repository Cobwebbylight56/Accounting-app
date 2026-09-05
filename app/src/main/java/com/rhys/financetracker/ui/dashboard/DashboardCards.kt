package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.insight.InsightSeverity
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ChartLegend
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.DonutChart
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.ProgressBarRow
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.StatEmphasis
import com.rhys.financetracker.ui.components.StatTile
import com.rhys.financetracker.ui.components.chartColorAt
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/**
 * The individual dashboard cards.
 *
 * Each one is small, self-contained and takes only the state it needs, so a
 * card can be reordered, hidden or previewed on its own.
 */

@Composable
internal fun MonthSummaryCard(state: DashboardState) {
    val summary = state.summary
    SectionCard(
        title = "This month",
        subtitle = DateUtils.formatMonth(state.month),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Money in",
                value = Money.format(summary.monthIncomeMinor),
                emphasis = StatEmphasis.POSITIVE,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "Money out",
                value = Money.format(summary.monthExpenseMinor),
                emphasis = StatEmphasis.NEGATIVE,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
        StatTile(
            label = "Left over",
            value = Money.format(summary.monthNetMinor, showSign = true),
            caption = if (summary.monthNetMinor < 0L) {
                "You have spent more than came in this month"
            } else {
                "Income less spending"
            },
            emphasis = if (summary.monthNetMinor < 0L) {
                StatEmphasis.NEGATIVE
            } else {
                StatEmphasis.POSITIVE
            },
        )
    }
}

/**
 * "Left to spend" — the figure most people actually want, and the one a
 * spreadsheet cannot easily produce: what remains once the bills still to come
 * this month are taken off.
 */
@Composable
internal fun DisposableIncomeCard(state: DashboardState) {
    val summary = state.summary
    SectionCard(title = "Left to spend") {
        StatTile(
            label = "After the bills still to come",
            value = Money.format(summary.disposableMinor, showSign = true),
            caption = "${Money.format(summary.monthNetMinor, showSign = true)} so far, " +
                "less ${Money.format(summary.committedRecurringMinor)} of bills still due",
            emphasis = when {
                summary.disposableMinor < 0L -> StatEmphasis.NEGATIVE
                summary.disposableMinor < 10_000L -> StatEmphasis.WARNING
                else -> StatEmphasis.POSITIVE
            },
        )
    }
}

@Composable
internal fun UpcomingBillsCard(state: DashboardState, onOpenRecurring: () -> Unit) {
    SectionCard(
        title = "Coming up",
        subtitle = if (state.upcomingBills.isEmpty()) {
            null
        } else {
            "${Money.format(state.upcomingBills.sumOf { it.rule.amountMinor })} over the next 30 days"
        },
        action = { TextButton(onClick = onOpenRecurring) { Text("All") } },
    ) {
        if (state.upcomingBills.isEmpty()) {
            Text(
                text = "No bills due in the next 30 days.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.upcomingBills.take(5).forEach { BillRow(it, onOpenRecurring) }
            }
        }
    }
}

@Composable
internal fun OverdueBillsCard(state: DashboardState, onOpenRecurring: () -> Unit) {
    if (state.overdueBills.isEmpty()) return

    SectionCard(
        title = "Overdue",
        subtitle = "${state.overdueBills.size} " +
            (if (state.overdueBills.size == 1) "payment has" else "payments have") +
            " passed their due date",
        action = { TextButton(onClick = onOpenRecurring) { Text("Fix") } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.overdueBills.take(5).forEach { BillRow(it, onOpenRecurring, isOverdue = true) }
        }
    }
}

@Composable
private fun BillRow(
    item: RecurringRuleWithDetails,
    onClick: () -> Unit,
    isOverdue: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(item.categoryColor))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.rule.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateUtils.relativeDescription(item.rule.nextDueDate) +
                    (item.personName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (isOverdue) {
                    FinanceTheme.colors.negative
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = Money.format(item.rule.amountMinor),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
internal fun RecentTransactionsCard(
    state: DashboardState,
    onOpenTransaction: (Long) -> Unit,
    onAddTransaction: () -> Unit,
) {
    SectionCard(
        title = "Recent",
        action = { TextButton(onClick = onAddTransaction) { Text("Add") } },
    ) {
        if (state.recentTransactions.isEmpty()) {
            Text(
                text = "Nothing recorded yet. Tap Add to enter your first transaction.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.recentTransactions.forEach { item ->
                    TransactionRowCompact(item) { onOpenTransaction(item.transaction.id) }
                }
            }
        }
    }
}

@Composable
private fun TransactionRowCompact(item: TransactionWithDetails, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val entry = item.transaction
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(item.categoryColor))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    DateUtils.formatShort(entry.date),
                    item.categoryName,
                    item.accountName,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = when (entry.type) {
                TransactionType.INCOME -> "+${Money.format(entry.amountMinor)}"
                TransactionType.EXPENSE -> "−${Money.format(entry.amountMinor)}"
                TransactionType.TRANSFER -> Money.format(entry.amountMinor)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = when (entry.type) {
                TransactionType.INCOME -> colors.income
                TransactionType.EXPENSE -> colors.expense
                TransactionType.TRANSFER -> colors.transfer
            },
        )
    }
}

@Composable
internal fun SavingsProgressCard(state: DashboardState, onOpenSavings: () -> Unit) {
    SectionCard(
        title = "Savings goals",
        action = { TextButton(onClick = onOpenSavings) { Text("All") } },
    ) {
        if (state.savingsGoals.isEmpty()) {
            Text(
                text = "No goals yet. A goal turns \"saving some money\" into a target you " +
                    "can see yourself reaching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.savingsGoals.take(4).forEach { goal -> GoalProgressRow(goal) }
        }
    }
}

@Composable
private fun GoalProgressRow(goal: SavingsGoalWithProgress) {
    ProgressBarRow(
        label = goal.goal.name,
        value = "${goal.percentComplete}%",
        fraction = goal.progressFraction,
        color = colorFromHex(goal.goal.colorHex),
        secondary = "${Money.format(goal.currentAmountMinor)} of " +
            "${Money.format(goal.goal.targetAmountMinor)} · " +
            "${Money.format(goal.remainingMinor)} to go",
    )
}

@Composable
internal fun SpendingByCategoryCard(
    state: DashboardState,
    onCategoryClick: (Long?, String, String?) -> Unit,
) {
    val totals = state.spendingByCategory
    val entries = totals.mapIndexed { index, total ->
        ChartEntry(
            label = total.categoryName ?: "Uncategorised",
            value = total.totalMinor.toFloat(),
            color = colorFromHex(total.categoryColor, index),
            displayValue = Money.format(total.totalMinor),
        )
    }
    // The chart drops zero-value entries, so index back through the same
    // filtered list rather than the raw totals.
    val tappable = totals.filter { it.totalMinor > 0L }

    fun open(index: Int) {
        tappable.getOrNull(index)?.let {
            onCategoryClick(it.categoryId, it.categoryName ?: "Uncategorised", it.categoryColor)
        }
    }

    SectionCard(
        title = "Where the money went",
        subtitle = if (entries.isEmpty()) null else "Tap a slice to see what is in it",
    ) {
        DonutChart(
            entries = entries,
            centreLabel = "spent",
            centreValue = Money.formatCompact(state.summary.monthExpenseMinor),
            onSliceClick = { index -> index?.let(::open) },
        )
        Spacer(Modifier.height(14.dp))
        ChartLegend(entries = entries, onEntryClick = ::open)
    }
}

@Composable
internal fun IncomeVsExpenseCard(
    state: DashboardState,
    onMonthClick: (java.time.YearMonth) -> Unit,
) {
    val colors = FinanceTheme.colors
    val groups = state.monthlyTrend.map { point ->
        BarGroup(
            label = DateUtils.monthNameShort(point.yearMonth.monthValue),
            bars = listOf(
                ChartEntry(
                    label = "In",
                    value = point.incomeMinor.toFloat(),
                    color = colors.income,
                    displayValue = Money.format(point.incomeMinor),
                ),
                ChartEntry(
                    label = "Out",
                    value = point.expenseMinor.toFloat(),
                    color = colors.expense,
                    displayValue = Money.format(point.expenseMinor),
                ),
            ),
        )
    }
    val selected = state.monthlyTrend.indexOfFirst { it.yearMonth == state.month }
        .takeIf { it >= 0 }

    SectionCard(
        title = "Income against spending",
        subtitle = "The last ${groups.size} months · tap a month to open it",
    ) {
        GroupedBarChart(
            groups = groups,
            selectedIndex = selected,
            onGroupClick = { index ->
                state.monthlyTrend.getOrNull(index)?.let { onMonthClick(it.yearMonth) }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendSwatch("Money in", colors.income)
            LegendSwatch("Money out", colors.expense)
        }
        state.monthlyTrend.firstOrNull { it.yearMonth == state.month }?.let { current ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "${DateUtils.formatMonth(current.yearMonth)}: " +
                    "${Money.format(current.incomeMinor)} in, " +
                    "${Money.format(current.expenseMinor)} out, " +
                    "${Money.format(current.netMinor, showSign = true)} left over",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendSwatch(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ColorDot(color)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun NetWorthCard(state: DashboardState) {
    val entries = state.accounts
        .filter { it.account.includeInNetWorth }
        .groupBy { it.account.type }
        .entries
        .mapIndexed { index, (type, accounts) ->
            ChartEntry(
                label = type.displayName,
                value = accounts.sumOf { it.balanceMinor }.coerceAtLeast(0L).toFloat(),
                color = chartColorAt(index),
                displayValue = Money.format(accounts.sumOf { it.balanceMinor }),
            )
        }

    SectionCard(
        title = "Net worth",
        subtitle = Money.format(state.summary.netWorthMinor),
    ) {
        DonutChart(
            entries = entries,
            centreLabel = "net worth",
            centreValue = Money.formatCompact(state.summary.netWorthMinor),
        )
        Spacer(Modifier.height(14.dp))
        ChartLegend(entries)
    }
}

@Composable
internal fun AccountsListCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    SectionCard(
        title = "Accounts",
        action = { TextButton(onClick = onOpenAccounts) { Text("Manage") } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.accounts.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenAccounts),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ColorDot(colorFromHex(account.account.colorHex))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(account.account.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = listOfNotNull(
                                account.account.type.displayName,
                                account.personName,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = Money.format(account.balanceMinor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (account.balanceMinor < 0L) {
                            FinanceTheme.colors.negative
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExternalDataCard(state: DashboardState, onOpenExternalData: () -> Unit) {
    val items = state.externalData.automatic + state.externalData.manual
    SectionCard(
        title = "Rates and figures",
        action = { TextButton(onClick = onOpenExternalData) { Text("Manage") } },
    ) {
        if (items.none { it.hasValue }) {
            Text(
                text = "Turn on rate updates in Settings, or enter figures such as your " +
                    "mortgage rate yourself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.filter { it.hasValue }.forEach { item ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.key.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = item.provenance,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(item.displayValue, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * The advice card.
 *
 * It shows only the single most pressing item, because a dashboard full of
 * advice is a dashboard nobody reads. The rest is a tap away.
 */
@Composable
internal fun InsightsCard(
    state: DashboardState,
    onOpenInsights: () -> Unit,
) {
    val colors = FinanceTheme.colors
    val insight = state.topInsight

    SectionCard(
        title = "Advice",
        action = { TextButton(onClick = onOpenInsights) { Text("All") } },
    ) {
        if (insight == null) {
            Text(
                text = "Once there is a month or two of records, this is where the app " +
                    "points out where the money goes and what is likely to happen next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        val accent = when (insight.severity) {
            InsightSeverity.ACT -> colors.negative
            InsightSeverity.WATCH -> colors.warning
            InsightSeverity.GOOD -> colors.positive
            InsightSeverity.INFO -> colors.neutral
        }

        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenInsights)) {
            Text(
                text = insight.title,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = insight.message, style = MaterialTheme.typography.bodyMedium)
            insight.annualImpactMinor?.takeIf { it > 0L }?.let { impact ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Worth ${Money.format(impact)} over a year",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.insightCount > 1) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "and ${state.insightCount - 1} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
