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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.insight.InsightSeverity
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ChartLegend
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DonutChart
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.LabelledTextField
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
    // Drawn however it was last left: tiles, a chart, bars or a list.
    val (view, setView) = com.rhys.financetracker.ui.components.rememberCardView(
        "home_where",
        com.rhys.financetracker.ui.components.BreakdownView.TILES,
    )
    SectionCard(
        title = "Where it went",
        subtitle = DateUtils.formatMonth(state.month),
        action = {
            com.rhys.financetracker.ui.components.ViewSwitchButton(
                view,
                com.rhys.financetracker.ui.components.CATEGORY_VIEWS,
                setView,
            )
        },
    ) {
        com.rhys.financetracker.ui.components.CategoryBreakdown(
            totals = state.spendingByCategory,
            view = view,
            maxRows = CATEGORY_TILE_COUNT,
            onOpen = { entry ->
                onCategoryClick(entry.categoryId, entry.categoryName ?: "Uncategorised", entry.categoryColor)
            },
        )
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
                (
                    if (summary.savingsNetMinor != 0L) {
                        "less ${Money.format(summary.savingsNetMinor)} put aside, "
                    } else {
                        ""
                    }
                ) +
                "less ${Money.format(summary.committedRecurringMinor)} of bills still due",
            emphasis = when {
                summary.disposableMinor < 0L -> StatEmphasis.NEGATIVE
                summary.disposableMinor < 10_000L -> StatEmphasis.WARNING
                else -> StatEmphasis.POSITIVE
            },
        )
        // Measured against yearly pay where it has been given: what a month
        // of take-home is, and how much of it this month has used.
        val income = state.incomeInScope
        income.netMonthlyMinor?.let { monthly ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Take-home from yearly pay: ${Money.format(monthly)} a month. " +
                    listOfNotNull(
                        income.shareOfMonthlyTakeHome(summary.monthExpenseMinor)
                            ?.let { "$it% spent" },
                        income.shareOfMonthlyTakeHome(summary.savingsNetMinor)
                            ?.takeIf { summary.savingsNetMinor > 0L }
                            ?.let { "$it% put aside" },
                    ).joinToString(", ").let { if (it.isEmpty()) it else "$it so far." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        title = "This month's payments",
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
 * Where the household's savings are, and what this month did to them.
 *
 * The top half is balances: every account set aside, and the cash pot when
 * the whole household is on screen. Those add up to exactly the Saved tile on
 * Home, because they are the same figures — there is no second definition of
 * savings anywhere for them to disagree with.
 *
 * The bottom half is movement, read from the spending side: what left the
 * accounts you spend from for savings, and what came back. Both directions,
 * because either alone lies — a month that put £200 in and took £500 out has
 * not saved £200.
 */
@Composable
internal fun SavingsAndCashCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val summary = state.summary
    val setAside = state.accounts.filter { it.isSavings }
    val potCounts = state.scope.includesHousehold
    val activity = state.accountActivity.associateBy { it.accountId }
    SectionCard(
        title = "Savings",
        subtitle = DateUtils.formatMonth(state.month),
    ) {
        if (setAside.isEmpty() && !(potCounts && summary.cashPotMinor != 0L)) {
            Text(
                text = "Nothing is set aside yet. Any account can count as savings: on " +
                    "Accounts, set it to \"Set aside\" — a saver, an ISA, or a current " +
                    "account you keep untouched.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenAccounts) { Text("Go to accounts") }
        } else {
            setAside.forEach { account ->
                val moved = activity[account.account.id]
                    ?.let { it.incomeMinor - it.expenseMinor } ?: 0L
                SavingsPlaceRow(
                    name = account.account.name,
                    owner = account.personName,
                    detail = when {
                        moved > 0L -> "+${Money.format(moved)} this month"
                        moved < 0L -> "−${Money.format(-moved)} this month"
                        else -> null
                    },
                    balanceMinor = account.balanceMinor,
                    colorHex = account.account.colorHex,
                )
            }
            if (potCounts && summary.cashPotMinor != 0L) {
                SavingsPlaceRow(
                    name = "Cash pot",
                    owner = "Household",
                    detail = null,
                    balanceMinor = summary.cashPotMinor,
                    colorHex = CASH_POT_COLOUR,
                )
            }
            PotRow("Saved", summary.totalSavingsMinor, isGood = true, isTotal = true)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        if (!summary.hasSavingsActivity) {
            Text(
                text = "Nothing moved from your spending accounts into savings this month. " +
                    "Payments to a saver are recognised on import, and any entry can be " +
                    "filed under Savings by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PotRow("Put aside", summary.savingsInMinor, isGood = true)
            PotRow("Taken back out", summary.savingsOutMinor, isGood = false)
            PotRow(
                label = if (summary.savingsNetMinor < 0L) {
                    "Savings went down by"
                } else {
                    "Put aside this month"
                },
                amountMinor = kotlin.math.abs(summary.savingsNetMinor),
                isGood = summary.savingsNetMinor >= 0L,
                isTotal = true,
            )
            Text(
                text = "Money moved to savings is not counted as spending, and money back " +
                    "out of savings is not counted as income.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One place savings are kept, with its balance. */
@Composable
private fun SavingsPlaceRow(
    name: String,
    owner: String?,
    detail: String?,
    balanceMinor: Long,
    colorHex: String,
) {
    val colors = FinanceTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(colorHex))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(owner, name).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = Money.format(balanceMinor),
            style = MaterialTheme.typography.bodyLarge,
            color = if (balanceMinor < 0L) colors.negative else colors.positive,
        )
    }
}

/**
 * The household cash pot: the notes and coins in the house.
 *
 * Not an account and not anybody's. It is kept entirely by hand and fed by
 * nothing: cash out of a machine, from any account, is treated as spent and
 * stops there, because guessing how much of a £50 withdrawal is still in a
 * wallet three days later is a guess the app would be wrong about most days.
 *
 * So this holds only what somebody puts in it — what was left over, what
 * something sold for, what somebody was given — and what is spent from it.
 * Its total counts in Saved and net worth when the whole household is shown.
 */
@Composable
internal fun CashInHandCard(
    state: DashboardState,
    onRecordCash: (String, String, Boolean) -> Boolean,
    onCountCash: (String) -> Boolean,
    onRemoveCash: (CashPotEntryEntity) -> Unit,
) {
    val colors = FinanceTheme.colors
    val total = state.summary.cashPotMinor
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var showWholeLog by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CashPotEntryEntity?>(null) }

    SectionCard(title = "Cash pot", subtitle = "The household's, not in any account") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "In the pot",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Money.format(total),
                style = MaterialTheme.typography.titleLarge,
                color = if (total < 0L) colors.negative else colors.positive,
            )
        }
        if (state.summary.cashOutMinor != 0L) {
            Text(
                text = "${Money.format(state.summary.cashOutMinor)} was taken out as cash " +
                    "across all accounts this month. That counts as spent; put in here " +
                    "only what is actually left.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Optional, because a total nobody can account for is only half
        // useful: "Sold the bike" three weeks later is worth the one line it
        // takes to type, and blank still works.
        LabelledTextField(
            label = "What for? (optional)",
            value = note,
            onValueChange = { note = it },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmountField(
                label = "Amount",
                value = amount,
                onValueChange = { amount = it },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    // Cleared only once it has gone in: a missing amount
                    // used to wipe the note that had just been typed.
                    if (onRecordCash(amount, note, true)) {
                        amount = ""
                        note = ""
                    }
                },
            ) { Text("Put in") }
            TextButton(
                onClick = {
                    if (onRecordCash(amount, note, false)) {
                        amount = ""
                        note = ""
                    }
                },
            ) { Text("Spent") }
        }
        TextButton(
            onClick = {
                if (onCountCash(amount)) {
                    amount = ""
                    note = ""
                }
            },
        ) { Text("I counted it: set the total to this amount") }

        // The log. A running total on its own is a number you cannot check —
        // where it came from and what it went on is the part worth keeping,
        // and no statement anywhere holds any of it.
        if (state.cashLog.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Cash in and out",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.cashLog.size > CASH_LOG_SHOWN) {
                    TextButton(onClick = { showWholeLog = !showWholeLog }) {
                        Text(if (showWholeLog) "Show less" else "Show all ${state.cashLog.size}")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val shown = if (showWholeLog) {
                state.cashLog
            } else {
                state.cashLog.take(CASH_LOG_SHOWN)
            }
            shown.forEach { entry -> CashLogRow(entry, onTap = { removing = entry }) }
        }
    }

    removing?.let { entry ->
        ConfirmDialog(
            title = "Remove this entry?",
            message = "${entry.note ?: "This entry"} (${Money.format(entry.amountMinor)}) " +
                "will be taken out of the log and the pot's total.",
            confirmLabel = "Remove",
            onConfirm = { onRemoveCash(entry) },
            onDismiss = { removing = null },
            isDestructive = true,
        )
    }
}

/** One line of the cash log: when, what for, and which way it went. */
@Composable
private fun CashLogRow(entry: CashPotEntryEntity, onTap: () -> Unit) {
    val colors = FinanceTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.note ?: if (entry.isIn) "Put in" else "Spent",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DateUtils.formatShort(entry.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (entry.isIn) {
                "+${Money.format(entry.amountMinor)}"
            } else {
                "−${Money.format(entry.amountMinor)}"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (entry.isIn) colors.positive else colors.negative,
        )
    }
}

/** How much of the cash log the card shows before it is opened out. */
private const val CASH_LOG_SHOWN = 5

/** The cash pot's colour wherever it is listed beside accounts. */
private const val CASH_POT_COLOUR = "#6D4C41"

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
                    item.regularKind?.shortName,
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
    val (view, setView) = com.rhys.financetracker.ui.components.rememberCardView(
        "home_spending",
        com.rhys.financetracker.ui.components.BreakdownView.CHART,
    )
    SectionCard(
        title = "Spending by category",
        subtitle = "Tap one to see what is in it",
        action = {
            com.rhys.financetracker.ui.components.ViewSwitchButton(
                view,
                com.rhys.financetracker.ui.components.CATEGORY_VIEWS,
                setView,
            )
        },
    ) {
        com.rhys.financetracker.ui.components.CategoryBreakdown(
            totals = state.spendingByCategory,
            view = view,
            onOpen = { entry ->
                onCategoryClick(entry.categoryId, entry.categoryName ?: "Uncategorised", entry.categoryColor)
            },
        )
    }
}

@Composable
internal fun IncomeVsExpenseCard(
    state: DashboardState,
    onMonthClick: (java.time.YearMonth) -> Unit,
) {
    val (view, setView) = com.rhys.financetracker.ui.components.rememberCardView(
        "home_trend_line",
        com.rhys.financetracker.ui.components.BreakdownView.LINE,
    )
    SectionCard(
        title = "Income against spending",
        subtitle = "The last ${state.monthlyTrend.size} months · tap a month to open it",
        action = {
            com.rhys.financetracker.ui.components.ViewSwitchButton(
                view,
                com.rhys.financetracker.ui.components.TREND_VIEWS,
                setView,
            )
        },
    ) {
        com.rhys.financetracker.ui.components.MonthTrend(
            points = state.monthlyTrend,
            view = view,
            selected = state.month,
            onMonth = onMonthClick,
        )
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
