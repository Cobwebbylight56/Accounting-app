package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import java.time.YearMonth

/**
 * The individual dashboard cards.
 *
 * Each one is small, self-contained and takes only the state it needs, so a
 * card can be reordered, hidden or previewed on its own.
 */

/**
 * Every account with what went in and what went out this month.
 *
 * The balance alone does not answer "how are we doing?" — £400 could be a good
 * month or a bad one depending on what passed through to get there. In, out
 * and the balance together do answer it, which is why all three are on one row.
 */
@Composable
internal fun AccountActivityCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val byAccount = state.accountActivity.associateBy { it.accountId }
    val accounts = state.accounts.filter { state.scope.matches(it) }

    SectionCard(
        title = "Accounts this month",
        subtitle = DateUtils.formatMonth(state.month),
        action = { TextButton(onClick = onOpenAccounts) { Text("All") } },
    ) {
        if (accounts.isEmpty()) {
            Text(
                "No accounts yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            accounts.forEach { account ->
                val activity = byAccount[account.account.id]
                AccountActivityRow(
                    name = account.account.name,
                    colorHex = account.account.colorHex,
                    inMinor = activity?.incomeMinor ?: 0L,
                    outMinor = activity?.expenseMinor ?: 0L,
                    balanceMinor = account.balanceMinor,
                    onClick = onOpenAccounts,
                )
            }
        }
    }
}

@Composable
private fun AccountActivityRow(
    name: String,
    colorHex: String?,
    inMinor: Long,
    outMinor: Long,
    balanceMinor: Long,
    onClick: () -> Unit,
) {
    val colors = FinanceTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ColorDot(colorFromHex(colorHex))
            Spacer(Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                Money.format(balanceMinor),
                style = MaterialTheme.typography.titleSmall,
                color = if (balanceMinor < 0L) colors.negative else MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "In ${Money.format(inMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.positive,
            )
            Text(
                "Out ${Money.format(outMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = colors.negative,
            )
        }
    }
}

/**
 * Where the money went, as a plain list of the biggest categories.
 *
 * The donut chart below shows the same figures in proportion; this shows them
 * in pounds. Reading "Groceries £412" off a card takes no interpretation at
 * all, which is what makes it the right thing to see first.
 */
@Composable
internal fun CategoryTilesCard(
    state: DashboardState,
    onCategoryClick: (Long?, String, String?) -> Unit,
) {
    val top = state.spendingByCategory
        .filter { it.totalMinor > 0L }
        .take(CATEGORY_TILE_COUNT)

    SectionCard(
        title = "Where it went",
        subtitle = DateUtils.formatMonth(state.month),
    ) {
        if (top.isEmpty()) {
            Text(
                "Nothing spent yet this month.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        // Two to a row: wide enough for "Household bills" and a figure without
        // either being cut short.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            top.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { offset, entry ->
                        StatTile(
                            label = entry.categoryName ?: "Uncategorised",
                            value = Money.format(entry.totalMinor),
                            caption = "${entry.transactionCount} " +
                                if (entry.transactionCount == 1) "entry" else "entries",
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onCategoryClick(
                                    entry.categoryId,
                                    entry.categoryName ?: "Uncategorised",
                                    entry.categoryColor,
                                )
                            },
                        )
                        // Keeps a lone tile on the last row half-width rather
                        // than letting it stretch across.
                        if (pair.size == 1 && offset == 0) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Enough to cover a household's regular spending without becoming a wall. */
private const val CATEGORY_TILE_COUNT = 6

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

/**
 * The month's transactions, a few at a time.
 *
 * A statement import puts two hundred rows into a month, and this card showed
 * eight of them with no way to reach the ninth. It now holds the whole month
 * and opens on request, because scrolling past two hundred rows to reach the
 * cards underneath is its own kind of unusable — so it closes again too.
 */
@Composable
internal fun RecentTransactionsCard(
    state: DashboardState,
    onOpenTransaction: (Long) -> Unit,
    onAddTransaction: () -> Unit,
    onMonthClick: (YearMonth) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val all = state.monthTransactions
    val shown = if (showAll) all else all.take(TRANSACTIONS_SHOWN)

    SectionCard(
        title = "This month",
        action = { TextButton(onClick = onAddTransaction) { Text("Add") } },
    ) {
        if (all.isEmpty()) {
            // Opening on today's month and finding it empty looks like the app
            // has lost everything, when the entries are simply in an earlier
            // month — which is exactly what importing old statements leaves
            // you with. So it says where the money actually is.
            val elsewhere = state.latestEntryDate
                ?.let { YearMonth.from(it) }
                ?.takeIf { it != state.month }
            Text(
                text = if (elsewhere != null) {
                    "Nothing in ${DateUtils.formatMonth(state.month)}. Your most recent " +
                        "entries are in ${DateUtils.formatMonth(elsewhere)}."
                } else {
                    "Nothing recorded this month. Tap Add to enter one."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (elsewhere != null) {
                TextButton(onClick = { onMonthClick(elsewhere) }) {
                    Text("Go to ${DateUtils.formatMonth(elsewhere)}")
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEach { item ->
                    TransactionRowCompact(item) { onOpenTransaction(item.transaction.id) }
                }
                if (all.size > TRANSACTIONS_SHOWN) {
                    TextButton(
                        onClick = { showAll = !showAll },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (showAll) {
                                "Show less"
                            } else {
                                "Show all ${all.size}"
                            },
                        )
                    }
                }
            }
        }
    }
}

/** How many of the month's transactions the card shows before it is opened. */
private const val TRANSACTIONS_SHOWN = 8

/**
 * Money moved rather than spent: into and out of savings, and into and out of
 * cash.
 *
 * Both directions, because either alone lies. A month that put £200 into a
 * saver and took £500 back out has not saved £200, and the app used to say it
 * had. The same for cash: £50 from a machine is not £50 spent, it is £50 in a
 * pocket — what it then went on is something no statement can say.
 */
@Composable
internal fun SavingsAndCashCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val summary = state.summary
    SectionCard(
        title = "Savings and cash",
        subtitle = DateUtils.formatMonth(state.month),
    ) {
        if (!summary.hasPotActivity) {
            Text(
                text = "Nothing moved into savings or taken out as cash this month. " +
                    "Payments to a saver and withdrawals from a machine are recognised " +
                    "on import, and either can be set by hand on any entry.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        PotRow("Into savings", summary.savingsInMinor, isGood = true)
        PotRow("Out of savings", summary.savingsOutMinor, isGood = false)
        PotRow(
            label = if (summary.savingsNetMinor < 0L) "Savings went down by" else "Saved this month",
            amountMinor = kotlin.math.abs(summary.savingsNetMinor),
            isGood = summary.savingsNetMinor >= 0L,
            isTotal = true,
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        PotRow("Cash taken out", summary.cashOutMinor, isGood = false)
        PotRow("Cash paid back in", summary.cashInMinor, isGood = true)

        Spacer(Modifier.height(10.dp))
        Text(
            text = "Cash out of a machine is not spending — it is the same money in a " +
                "pocket. What it was spent on is only in the app if you enter it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (summary.totalSavingsMinor == 0L && summary.savingsEverMovedMinor != 0L) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "You have no savings account in the app, so \"Saved\" on Home shows " +
                    "${Money.format(summary.savingsEverMovedMinor)} — everything it has " +
                    "watched move into savings, less what came back out. Add the saver as " +
                    "an account and its real balance is shown instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenAccounts) { Text("Add the saver") }
        }
    }
}

/** One line of the savings and cash card. */
@Composable
private fun PotRow(
    label: String,
    amountMinor: Long,
    isGood: Boolean,
    isTotal: Boolean = false,
) {
    val colors = FinanceTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = if (isTotal) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (isTotal) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Money.format(amountMinor),
            style = if (isTotal) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyLarge
            },
            color = when {
                amountMinor == 0L -> MaterialTheme.colorScheme.onSurfaceVariant
                isGood -> colors.positive
                else -> colors.negative
            },
        )
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
