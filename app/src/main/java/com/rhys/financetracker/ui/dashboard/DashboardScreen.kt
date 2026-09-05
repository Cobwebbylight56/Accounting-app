package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.model.DashboardWidget
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.LoadingState
import com.rhys.financetracker.ui.components.StatEmphasis
import com.rhys.financetracker.ui.components.StatTile

/**
 * The home screen.
 *
 * It is a single scrolling list of cards whose order and visibility the user
 * controls (Settings → Dashboard layout).  Rendering from a list rather than a
 * fixed column is what makes that customisation possible without a rewrite —
 * adding a card means adding a `DashboardWidget` constant and one branch in
 * [DashboardCard].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenTransaction: (Long) -> Unit,
    onAddTransaction: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSavings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDashboardSettings: () -> Unit,
    onOpenExternalData: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categoryDetail by viewModel.categoryDetail.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Finance Tracker") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            !state.hasAnyData -> EmptyState(
                icon = Icons.Outlined.AccountBalanceWallet,
                title = "Let's set things up",
                message = "Add an account to begin, or load the example household from " +
                    "Settings to see how everything fits together.",
                actionLabel = "Add an account",
                onAction = onOpenAccounts,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    MonthSelector(
                        label = DateUtils.formatMonth(state.month),
                        isCurrentMonth = state.isCurrentMonth,
                        onPrevious = viewModel::showPreviousMonth,
                        onNext = viewModel::showNextMonth,
                        onToday = viewModel::showCurrentMonth,
                    )
                }

                item {
                    ScopeSelector(
                        state = state,
                        onScopeChange = viewModel::setScope,
                    )
                }

                items(
                    items = state.widgets.filter { it.isVisible },
                    key = { it.widget.key },
                ) { visible ->
                    DashboardCard(
                        widget = visible.widget,
                        state = state,
                        onOpenTransaction = onOpenTransaction,
                        onAddTransaction = onAddTransaction,
                        onOpenAccounts = onOpenAccounts,
                        onOpenRecurring = onOpenRecurring,
                        onOpenSavings = onOpenSavings,
                        onOpenExternalData = onOpenExternalData,
                        onCategoryClick = viewModel::showCategoryDetail,
                        onMonthClick = viewModel::showMonth,
                    )
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onOpenDashboardSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose which cards appear here")
                    }
                }
            }
        }
    }

    categoryDetail?.let { detail ->
        CategoryDetailSheet(
            detail = detail,
            onDismiss = viewModel::clearCategoryDetail,
            onOpenTransaction = { id ->
                viewModel.clearCategoryDetail()
                onOpenTransaction(id)
            },
        )
    }
}

/** Steps through months, and offers a way straight back to the current one. */
@Composable
private fun MonthSelector(
    label: String,
    isCurrentMonth: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleLarge)
            if (!isCurrentMonth) {
                Text(
                    text = "Looking back",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!isCurrentMonth) {
            TextButton(onClick = onToday) { Text("Today") }
        }
        IconButton(onClick = onNext, enabled = !isCurrentMonth) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
        }
    }
}

/** Switches between the household, one person, and one account. */
@Composable
private fun ScopeSelector(
    state: DashboardState,
    onScopeChange: (DashboardScope) -> Unit,
) {
    if (state.people.size <= 1 && state.accounts.size <= 1) return

    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            androidx.compose.material3.FilterChip(
                selected = state.scope.personId == null && state.scope.accountId == null,
                onClick = { onScopeChange(DashboardScope()) },
                label = { Text("Everyone") },
            )
        }
        items(state.people) { person ->
            androidx.compose.material3.FilterChip(
                selected = state.scope.personId == person.id,
                onClick = {
                    onScopeChange(DashboardScope(personId = person.id, label = person.name))
                },
                label = { Text(person.name) },
            )
        }
    }
}

/**
 * Renders one dashboard card.
 *
 * This `when` is the extension point for the dashboard: a new card needs a
 * `DashboardWidget` constant and a branch here, and it will then appear in the
 * layout settings automatically.
 */
@Composable
private fun DashboardCard(
    widget: DashboardWidget,
    state: DashboardState,
    onOpenTransaction: (Long) -> Unit,
    onAddTransaction: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSavings: () -> Unit,
    onOpenExternalData: () -> Unit,
    onCategoryClick: (Long?, String, String?) -> Unit,
    onMonthClick: (java.time.YearMonth) -> Unit,
) {
    when (widget) {
        DashboardWidget.BALANCE_SUMMARY -> BalanceSummaryCard(state, onOpenAccounts)
        DashboardWidget.MONTH_SUMMARY -> MonthSummaryCard(state)
        DashboardWidget.DISPOSABLE_INCOME -> DisposableIncomeCard(state)
        DashboardWidget.UPCOMING_BILLS -> UpcomingBillsCard(state, onOpenRecurring)
        DashboardWidget.OVERDUE_BILLS -> OverdueBillsCard(state, onOpenRecurring)
        DashboardWidget.RECENT_TRANSACTIONS ->
            RecentTransactionsCard(state, onOpenTransaction, onAddTransaction)
        DashboardWidget.SAVINGS_PROGRESS -> SavingsProgressCard(state, onOpenSavings)
        DashboardWidget.SPENDING_BY_CATEGORY ->
            SpendingByCategoryCard(state, onCategoryClick)
        DashboardWidget.INCOME_VS_EXPENSE -> IncomeVsExpenseCard(state, onMonthClick)
        DashboardWidget.NET_WORTH -> NetWorthCard(state)
        DashboardWidget.ACCOUNTS_LIST -> AccountsListCard(state, onOpenAccounts)
        DashboardWidget.EXTERNAL_DATA -> ExternalDataCard(state, onOpenExternalData)
    }
}

/** The three headline balances. */
@Composable
private fun BalanceSummaryCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val summary = state.summary
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Available",
                value = Money.format(summary.totalBalanceMinor),
                emphasis = if (summary.totalBalanceMinor < 0L) {
                    StatEmphasis.NEGATIVE
                } else {
                    StatEmphasis.NEUTRAL
                },
                modifier = Modifier.weight(1f),
                onClick = onOpenAccounts,
            )
            StatTile(
                label = "Saved",
                value = Money.format(summary.totalSavingsMinor),
                emphasis = StatEmphasis.POSITIVE,
                modifier = Modifier.weight(1f),
                onClick = onOpenAccounts,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "Owed",
                value = Money.format(summary.totalLiabilitiesMinor),
                emphasis = if (summary.totalLiabilitiesMinor < 0L) {
                    StatEmphasis.NEGATIVE
                } else {
                    StatEmphasis.NEUTRAL
                },
                modifier = Modifier.weight(1f),
                onClick = onOpenAccounts,
            )
            StatTile(
                label = "Net worth",
                value = Money.format(summary.netWorthMinor),
                caption = "Everything owned less everything owed",
                emphasis = if (summary.netWorthMinor < 0L) {
                    StatEmphasis.NEGATIVE
                } else {
                    StatEmphasis.POSITIVE
                },
                modifier = Modifier.weight(1f),
                onClick = onOpenAccounts,
            )
        }
    }
}
