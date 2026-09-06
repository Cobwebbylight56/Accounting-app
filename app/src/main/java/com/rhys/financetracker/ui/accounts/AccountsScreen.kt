package com.rhys.financetracker.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ColorPicker
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.StatEmphasis
import com.rhys.financetracker.ui.components.StatTile
import com.rhys.financetracker.ui.components.SwitchRow
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/** The accounts list, grouped by who owns them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onEditAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onOpenPeople: () -> Unit,
    onImportStatement: (Long) -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    androidx.compose.material3.TextButton(onClick = onOpenPeople) {
                        Text("People")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Default.Add, contentDescription = "Add an account")
            }
        },
    ) { padding ->
        if (state.isEmpty) {
            EmptyState(
                icon = Icons.Outlined.AccountBalance,
                title = "No accounts yet",
                message = "Add the accounts your money sits in — current accounts, savings, " +
                    "cash, credit cards. Everything else builds on these.",
                actionLabel = "Add an account",
                onAction = onAddAccount,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = "Assets",
                        value = Money.format(state.totalAssetsMinor),
                        emphasis = StatEmphasis.POSITIVE,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Debts",
                        value = Money.format(state.totalLiabilitiesMinor),
                        emphasis = if (state.totalLiabilitiesMinor < 0L) {
                            StatEmphasis.NEGATIVE
                        } else {
                            StatEmphasis.NEUTRAL
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                StatTile(
                    label = "Net worth",
                    value = Money.format(state.netWorthMinor),
                    caption = "Everything owned less everything owed",
                    emphasis = if (state.netWorthMinor < 0L) {
                        StatEmphasis.NEGATIVE
                    } else {
                        StatEmphasis.POSITIVE
                    },
                )
            }

            state.groups.forEach { group ->
                item(key = "group-${group.personName}") {
                    SectionCard(
                        title = group.personName,
                        subtitle = Money.format(group.totalMinor),
                    ) {
                        // An account nobody owns is invisible everywhere the
                        // app works by person: their tab on Home says they have
                        // nothing and offers to add an account, while their
                        // money sits down here under a heading that does not
                        // say what to do about it.
                        if (group.isUnassigned && state.people.isNotEmpty()) {
                            Text(
                                text = "These are not under anybody's name yet, so they do " +
                                    "not show when you pick a person on Home. Tap a name to " +
                                    "put one under it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            group.accounts.forEach { account ->
                                AccountRow(
                                    account = account,
                                    onClick = { onEditAccount(account.account.id) },
                                    onImportStatement = {
                                        onImportStatement(account.account.id)
                                    },
                                    onDuplicate = { viewModel.duplicate(account.account.id) },
                                    onArchive = {
                                        viewModel.archive(
                                            account.account.id,
                                            !account.account.isArchived,
                                        )
                                    },
                                    onDelete = { pendingDelete = account.account.id },
                                )
                                if (group.isUnassigned && state.people.isNotEmpty()) {
                                    OwnerChips(
                                        people = state.people,
                                        onPick = { person ->
                                            viewModel.assign(account.account.id, person)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Show archived accounts",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.showArchived,
                        onCheckedChange = viewModel::setShowArchived,
                    )
                }
            }
        }
    }

    if (pendingDelete != 0L) {
        ConfirmDialog(
            title = "Delete this account?",
            message = "Every transaction on this account will be deleted too, and your " +
                "history will change. Archiving hides it while keeping all of that intact.",
            confirmLabel = "Delete everything",
            isDestructive = true,
            alternativeLabel = "Archive instead",
            onAlternative = { viewModel.archive(pendingDelete, true) },
            onConfirm = { viewModel.delete(pendingDelete) },
            onDismiss = { pendingDelete = 0L },
        )
    }
}

/**
 * "Whose is this?", answered in one tap.
 *
 * The account form already has an owner picker, but nothing on the list says
 * that an unowned account is the reason a person's tab on Home is empty — so
 * the connection is made here, next to the account it is about.
 */
@Composable
private fun OwnerChips(people: List<PersonEntity>, onPick: (PersonEntity) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 36.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Whose?",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        people.forEach { person ->
            AssistChip(
                onClick = { onPick(person) },
                label = { Text(person.name) },
                leadingIcon = { ColorDot(colorFromHex(person.colorHex)) },
            )
        }
    }
}

@Composable
private fun AccountRow(
    account: AccountWithBalance,
    onClick: () -> Unit,
    onImportStatement: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isOverdrawn = account.balanceMinor < 0L

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(colorFromHex(account.account.colorHex), size = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.account.name +
                        if (account.account.isArchived) " (archived)" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = account.account.type.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = Money.format(account.balanceMinor),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isOverdrawn) {
                    FinanceTheme.colors.negative
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Actions")
            }
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { onClick(); showMenu = false },
            )
            // Reached from the account itself, so there is nothing to choose:
            // the statement is filed here.
            DropdownMenuItem(
                text = { Text("Import a statement") },
                onClick = { onImportStatement(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Duplicate") },
                onClick = { onDuplicate(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (account.account.isArchived) "Restore" else "Archive") },
                onClick = { onArchive(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = { onDelete(); showMenu = false },
            )
        }
    }
}

/** Add or edit one account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEditScreen(
    onBack: () -> Unit,
    viewModel: AccountEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) { if (state.isSaved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New account" else "Edit account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.form.errorSummary?.let {
                ErrorBanner(message = it, onDismiss = viewModel::clearError)
            }

            LabelledTextField(
                label = "Account name",
                value = state.form.name,
                onValueChange = { text -> viewModel.update { it.copy(name = text) } },
                placeholder = "Everyday current account",
            )

            DropdownField(
                label = "Type",
                options = AccountType.entries,
                selected = state.form.type,
                onSelect = { type -> viewModel.update { it.copy(type = type) } },
                optionLabel = { it.displayName },
            )

            DropdownField(
                label = "Belongs to",
                options = state.people,
                selected = state.people.firstOrNull { it.id == state.form.personId },
                onSelect = { person -> viewModel.update { it.copy(personId = person.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "Not assigned",
            )
            // An account under nobody's name is invisible to every per-person
            // view in the app, and nothing said so — which is how a whole
            // imported statement ended up unreachable from the person's tab.
            if (state.form.personId == null && state.people.isNotEmpty()) {
                Text(
                    text = "Nobody's yet, so it will not show when you pick a person on " +
                        "Home. Pick a name above — there is one for anything shared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }

            AmountField(
                label = "Starting balance",
                value = state.form.openingBalanceText,
                onValueChange = { text ->
                    viewModel.update { it.copy(openingBalanceText = text) }
                },
            )

            DateField(
                label = "Balance as at",
                date = state.form.openingBalanceDate,
                onDateChange = { date ->
                    viewModel.update { it.copy(openingBalanceDate = date) }
                },
            )

            AmountField(
                label = "Agreed overdraft",
                value = state.form.overdraftText,
                onValueChange = { text -> viewModel.update { it.copy(overdraftText = text) } },
            )

            AmountField(
                label = "Warn me below",
                value = state.form.lowBalanceText,
                onValueChange = { text -> viewModel.update { it.copy(lowBalanceText = text) } },
            )

            if (state.form.type == AccountType.CREDIT_CARD ||
                state.form.type == AccountType.LOAN ||
                state.form.type == AccountType.MORTGAGE
            ) {
                AmountField(
                    label = "Credit limit or original advance",
                    value = state.form.creditLimitText,
                    onValueChange = { text ->
                        viewModel.update { it.copy(creditLimitText = text) }
                    },
                )
                LabelledTextField(
                    label = "Interest rate (%)",
                    value = state.form.interestRateText,
                    onValueChange = { text ->
                        viewModel.update { it.copy(interestRateText = text) }
                    },
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                )
            }

            ColorPicker(
                colors = DefaultData.PALETTE,
                selected = state.form.colorHex,
                onSelect = { hex -> viewModel.update { it.copy(colorHex = hex) } },
            )

            SwitchRow(
                label = "Count towards net worth",
                checked = state.form.includeInNetWorth,
                onCheckedChange = { value ->
                    viewModel.update { it.copy(includeInNetWorth = value) }
                },
            )
            SwitchRow(
                label = "Money set aside",
                description = "Counts under Saved on the home screen instead of " +
                    "Available. Use it for anything you are not planning to spend, " +
                    "whatever kind of account it is.",
                checked = state.form.countsAsSavings,
                onCheckedChange = { value ->
                    viewModel.update { it.copy(countsAsSavings = value) }
                },
            )
            SwitchRow(
                label = "Shared household account",
                checked = state.form.isShared,
                onCheckedChange = { value -> viewModel.update { it.copy(isShared = value) } },
            )

            LabelledTextField(
                label = "Notes",
                value = state.form.notes,
                onValueChange = { text -> viewModel.update { it.copy(notes = text) } },
                singleLine = false,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.isNew) "Add account" else "Save changes")
            }
        }
    }
}
