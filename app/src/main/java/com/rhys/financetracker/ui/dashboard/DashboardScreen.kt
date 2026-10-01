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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    onOpenSetup: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenSortSpending: () -> Unit = {},
    onOpenSentToPeople: () -> Unit = {},
    onOpenPeopleMoneyFor: (Long) -> Unit = {},
    onOpenBackup: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sharedPeople by viewModel.sharedPeopleIds.collectAsStateWithLifecycle()
    var choosingShared by rememberSaveable { mutableStateOf(false) }
    val individuals = state.people.filterNot { it.isShared }
    val categoryDetail by viewModel.categoryDetail.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val peopleMoney by viewModel.peopleMoney.collectAsStateWithLifecycle()
    val peopleYear by viewModel.peopleYear.collectAsStateWithLifecycle()
    val unsortedCount by viewModel.unsortedCount.collectAsStateWithLifecycle()
    val showBackupNudge by viewModel.showBackupNudge.collectAsStateWithLifecycle()
    val resortNote by viewModel.resortNote.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Reminders are on from the start, but on Android 13 and later none can
    // appear until Android has asked. Asked once, after the first person is
    // set up — not over the welcome screen, before anything makes sense.
    val askForNotifications by viewModel.askForNotifications.collectAsStateWithLifecycle()
    val notificationsAllowed = com.rhys.financetracker.ui.components.rememberNotificationsAllowed()
    val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val hasSomeone = individuals.isNotEmpty()
    LaunchedEffect(askForNotifications, hasSomeone) {
        if (askForNotifications && hasSomeone) {
            viewModel.markNotificationsAsked()
            if (!notificationsAllowed && android.os.Build.VERSION.SDK_INT >= 33) {
                askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // The cash buttons change a number further up the card, which is easy to
    // miss on a screen this busy — so what happened is said outright.
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val showsHome = !state.isLoading && !(individuals.isEmpty() && state.accountsInTotal == 0)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // Adding a payment is the commonest thing there is to do, and Home
        // had no way to do it in one tap.
        floatingActionButton = {
            if (showsHome) {
                androidx.compose.material3.FloatingActionButton(onClick = onAddTransaction) {
                    Icon(Icons.Default.Add, contentDescription = "Add a payment")
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> LoadingState(Modifier.padding(padding))

            // Nobody set up and nothing recorded: a fresh install. Everything
            // is built around a person, so that is where it starts.
            individuals.isEmpty() && state.accountsInTotal == 0 ->
                WelcomeScreen(
                    onStart = onOpenSetup,
                    onRestore = onOpenBackup,
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
                    HomeHeader(
                        state = state,
                        onMenu = onOpenSettings,
                        onAvatar = {
                            state.scope.personId?.let(onOpenPerson) ?: onOpenPeople()
                        },
                        onCustomise = onOpenDashboardSettings,
                    )
                }

                resortNote?.let { note ->
                    item {
                        SectionCard(title = "Payments re-sorted") {
                            Text(
                                text = "The app now knows more shops, so it moved ${note.moved} " +
                                    (if (note.moved == 1) "payment" else "payments") +
                                    " it had filed itself into the right category" +
                                    (if (note.examples.isEmpty()) "." else ": " + note.examples.joinToString(", ")) +
                                    (if (note.examples.isNotEmpty() && note.moved > note.examples.size) " and others." else ".") +
                                    " Anything you filed yourself was left alone.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = viewModel::dismissResortNote, modifier = Modifier.weight(1f)) {
                                    Text("OK")
                                }
                                TextButton(onClick = viewModel::undoResort) { Text("Put them back") }
                            }
                        }
                    }
                }

                if (showBackupNudge) {
                    item {
                        SectionCard(title = "Back up your money") {
                            Text(
                                text = "Everything here is only on this phone. A backup keeps it " +
                                    "safe if the phone is lost or the app is put on again — and " +
                                    "you can save it to Drive or OneDrive.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onOpenBackup, modifier = Modifier.weight(1f)) {
                                    Text("Back up now")
                                }
                                TextButton(onClick = viewModel::snoozeBackupNudge) { Text("Later") }
                            }
                        }
                    }
                }

                item {
                    PersonTabs(
                        individuals = individuals,
                        scope = state.scope,
                        onPerson = viewModel::showPerson,
                        onShared = viewModel::showShared,
                        onChooseShared = { choosingShared = true },
                    )
                }

                // Money exists but nobody has been set up to own it — an
                // install from before the app was built around people.
                if (individuals.isEmpty()) {
                    item {
                        SectionCard(title = "Who is this money for?") {
                            Text(
                                text = "Set yourself up as a person, then your accounts go " +
                                    "under your name and Home becomes your tab.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                                Text("Set up a person")
                            }
                        }
                    }
                }

                item {
                    MonthSelector(
                        label = DateUtils.formatMonth(state.month),
                        isCurrentMonth = state.isCurrentMonth,
                        onPrevious = viewModel::showPreviousMonth,
                        onNext = viewModel::showNextMonth,
                        onToday = viewModel::showCurrentMonth,
                    )
                }

                item { HomeTiles(state = state, onOpenAccounts = onOpenAccounts) }

                item { MonthList(state = state) }

                // Whenever money has gone to or from anyone this year — not only
                // when this month has some, or it vanished at the start of
                // every month.
                if (!peopleMoney?.people.isNullOrEmpty() || !peopleYear?.people.isNullOrEmpty()) {
                    item {
                        com.rhys.financetracker.ui.spending.PeopleMoneyCard(
                            money = peopleMoney,
                            year = peopleYear,
                            yearNumber = state.month.year,
                            monthLabel = com.rhys.financetracker.core.time.DateUtils.formatMonth(state.month),
                            onSeeAll = {
                                val person = state.scope.personId
                                if (person != null) onOpenPeopleMoneyFor(person) else onOpenSentToPeople()
                            },
                        )
                    }
                }

                // Says how many are waiting, and goes when none are.
                if (unsortedCount > 0) {
                    item {
                        androidx.compose.material3.OutlinedButton(
                            onClick = onOpenSortSpending,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Sort spending · $unsortedCount " +
                                    if (unsortedCount == 1) "payment to file" else "payments to file",
                            )
                        }
                    }
                }

                item { LoansCard(state = state, onOpenAccounts = onOpenAccounts) }

                if (state.scopeHasNothingButAppDoes) {
                    item { NoAccountsForThisPerson(state, onOpenAccounts) }
                }

                items(
                    // The tiles and the month list above are these two cards
                    // drawn the new way, so they are not shown twice.
                    // The cash pot is the household's, so a person's own tab
                    // does not show it.
                    items = state.widgets.filter {
                        it.isVisible && it.widget !in REPLACED_BY_TILES &&
                            (it.widget != DashboardWidget.CASH_IN_HAND || state.scope.includesHousehold)
                    },
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

    if (choosingShared) {
        SharedPeopleDialog(
            individuals = individuals,
            selected = sharedPeople,
            onSave = viewModel::setSharedPeople,
            onDismiss = { choosingShared = false },
        )
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

/** Cards the Home tiles and month list now show instead. */
private val REPLACED_BY_TILES = setOf(
    DashboardWidget.BALANCE_SUMMARY,
    DashboardWidget.MONTH_SUMMARY,
)

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
    onRecordCash: (String, String, Boolean) -> Boolean,
    onCountCash: (String) -> Boolean,
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
