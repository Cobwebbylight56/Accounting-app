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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.rhys.financetracker.ui.components.SectionCard
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
    onOpenInsights: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categoryDetail by viewModel.categoryDetail.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The cash buttons change a number further up the card, which is easy to
    // miss on a screen this busy — so what happened is said outright.
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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

            // Only when there is genuinely nothing set up. A person with no
            // accounts of their own is a different problem with a different
            // answer, and showing this instead took the person tabs off the
            // screen along with everything else — so there was no way back to
            // Everyone except leaving Home.
            !state.hasAnyData && !state.scopeHasNothingButAppDoes -> EmptyState(
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

                if (state.scopeHasNothingButAppDoes) {
                    item { NoAccountsForThisPerson(state, onOpenAccounts) }
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
                        onOpenInsights = onOpenInsights,
                        onCategoryClick = viewModel::showCategoryDetail,
                        onMonthClick = viewModel::showMonth,
                        onRecordCash = viewModel::recordCash,
                        onCountCash = viewModel::countCash,
                        onRemoveCash = viewModel::removeCashEntry,
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
/**
 * Shown when the person picked has no accounts, but the app has some.
 *
 * Almost always because the accounts are not under anybody's name — which is
 * the state an imported statement leaves them in, and nothing anywhere said
 * so. The old answer here was "add an account", which makes a second copy of
 * one the app already holds.
 */
@Composable
private fun NoAccountsForThisPerson(state: DashboardState, onOpenAccounts: () -> Unit) {
    val name = state.people.firstOrNull { it.id == state.scope.personId }?.name
    SectionCard(title = name?.let { "$it has no accounts yet" } ?: "Nothing under this filter") {
        Text(
            text = if (state.unassignedAccounts > 0) {
                val many = state.unassignedAccounts > 1
                "You have ${state.unassignedAccounts} account${if (many) "s" else ""} that " +
                    "${if (many) "are" else "is"} not under anybody's name, so no person " +
                    "shows ${if (many) "them" else "it"}. Open Accounts and tap a name " +
                    "beside ${if (many) "them" else "it"} to fix that — do not add a new " +
                    "one, or the same money is counted twice."
            } else {
                "Their accounts live under their name. Open Accounts to add one, or to " +
                    "move an existing account across."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onOpenAccounts, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.unassignedAccounts > 0) "Sort this out in Accounts" else "Open Accounts")
        }
    }
}

@Composable
private fun ScopeSelector(
    state: DashboardState,
    onScopeChange: (DashboardScope) -> Unit,
) {
    // Never hidden once a person is picked. [accounts] is the *filtered* list,
    // so choosing somebody with one account or none could satisfy this and
    // take the chips off the screen — including the "Everyone" chip, which is
    // the only way back. There was then no way out of that person's view but
    // to kill the app. The unfiltered count is what this question was always
    // about.
    // These chips only ever offer people, so one person is nothing to choose
    // between: "Everyone" and the one name mean the same screen. They appear
    // when a second person is added.
    //
    // Still shown while a filter is on, whatever the count. [accounts] is the
    // filtered list, and hiding the chips there once took "Everyone" off the
    // screen — the only way back — and left killing the app as the way out.
    val isFiltered = state.scope.personId != null || state.scope.accountId != null
    if (!isFiltered && state.people.size <= 1) return

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
    onOpenInsights: () -> Unit,
    onCategoryClick: (Long?, String, String?) -> Unit,
    onMonthClick: (java.time.YearMonth) -> Unit,
    onRecordCash: (String, String, Boolean) -> Unit,
    onCountCash: (String) -> Unit,
    onRemoveCash: (com.rhys.financetracker.data.local.entity.CashPotEntryEntity) -> Unit,
) {
    when (widget) {
        DashboardWidget.ACCOUNT_ACTIVITY -> AccountActivityCard(state, onOpenAccounts)
        DashboardWidget.CATEGORY_TILES -> CategoryTilesCard(state, onCategoryClick)
        DashboardWidget.BALANCE_SUMMARY -> BalanceSummaryCard(state, onOpenAccounts)
        DashboardWidget.MONTH_SUMMARY -> MonthSummaryCard(state)
        DashboardWidget.DISPOSABLE_INCOME -> DisposableIncomeCard(state)
        DashboardWidget.UPCOMING_BILLS -> UpcomingBillsCard(state, onOpenRecurring)
        DashboardWidget.OVERDUE_BILLS -> OverdueBillsCard(state, onOpenRecurring)
        DashboardWidget.RECENT_TRANSACTIONS ->
            RecentTransactionsCard(state, onOpenTransaction, onAddTransaction, onMonthClick)
        DashboardWidget.SAVINGS_AND_CASH -> SavingsAndCashCard(state, onOpenAccounts)
        DashboardWidget.CASH_IN_HAND ->
            CashInHandCard(state, onRecordCash, onCountCash, onRemoveCash)
        DashboardWidget.SAVINGS_PROGRESS -> SavingsProgressCard(state, onOpenSavings)
        DashboardWidget.SPENDING_BY_CATEGORY ->
            SpendingByCategoryCard(state, onCategoryClick)
        DashboardWidget.INCOME_VS_EXPENSE -> IncomeVsExpenseCard(state, onMonthClick)
        DashboardWidget.NET_WORTH -> NetWorthCard(state)
        DashboardWidget.ACCOUNTS_LIST -> AccountsListCard(state, onOpenAccounts)
        DashboardWidget.EXTERNAL_DATA -> ExternalDataCard(state, onOpenExternalData)
        DashboardWidget.INSIGHTS -> InsightsCard(state, onOpenInsights)
    }
}

/** The three headline balances. */
@Composable
private fun BalanceSummaryCard(state: DashboardState, onOpenAccounts: () -> Unit) {
    val summary = state.summary
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Which tile an account lands in is its "Counts as" setting — To
            // spend, Set aside or Owed — and nothing else.
            StatTile(
                label = "Available",
                caption = "To spend",
                value = Money.format(summary.totalBalanceMinor),
                emphasis = if (summary.totalBalanceMinor < 0L) {
                    StatEmphasis.NEGATIVE
                } else {
                    StatEmphasis.NEUTRAL
                },
                modifier = Modifier.weight(1f),
                onClick = onOpenAccounts,
            )
            // Saved is a balance and only a balance: every set-aside account,
            // plus the cash pot when the whole household is on screen. It
            // once stood in a total of savings payments where there was no
            // savings account, which showed movements as if they were money
            // held and could go negative; what the month put in is the
            // caption's job, never the figure's.
            val hasSomewhereSetAside = state.accounts.any { it.isSavings } ||
                summary.cashPotMinor != 0L
            StatTile(
                label = "Saved",
                caption = when {
                    !hasSomewhereSetAside -> "Nothing set aside yet"
                    summary.savingsNetMinor > 0L ->
                        "${Money.format(summary.savingsNetMinor)} put aside this month"
                    summary.savingsNetMinor < 0L ->
                        "${Money.format(-summary.savingsNetMinor)} taken out this month"
                    else -> "Set aside"
                },
                value = Money.format(summary.totalSavingsMinor),
                emphasis = if (summary.totalSavingsMinor < 0L) {
                    StatEmphasis.NEGATIVE
                } else {
                    StatEmphasis.POSITIVE
                },
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
