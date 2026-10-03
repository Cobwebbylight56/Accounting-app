package com.rhys.financetracker.ui.people

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.People
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ColorPicker
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex

/**
 * The people in the household.
 *
 * People exist so that "who does this belong to?" can be answered on every
 * account, bill and transaction, which is what makes the per-person and
 * household views possible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onEditPerson: (Long) -> Unit,
    onAddPerson: () -> Unit,
    viewModel: PeopleViewModel = hiltViewModel(),
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
                title = { Text("People") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddPerson) {
                Icon(Icons.Default.Add, contentDescription = "Add a person")
            }
        },
    ) { padding ->
        if (state.people.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.People,
                title = "No one added yet",
                message = "Add yourself, your partner and any children whose money you " +
                    "want to keep track of.",
                actionLabel = "Add a person",
                onAction = onAddPerson,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        ) {
            item { HouseholdIncomeCard(state) }
            items(state.people, key = { it.person.id }) { item ->
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEditPerson(item.person.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorDot(colorFromHex(item.person.colorHex), size = 16.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.person.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "${item.accountCount} " +
                                    (if (item.accountCount == 1) "account" else "accounts") +
                                    if (item.person.isShared) " · shared" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            incomeLine(item)?.let { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                        }
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            onClick = { onEditPerson(item.person.id); showMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            onClick = { viewModel.duplicate(item.person.id); showMenu = false },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (item.person.isArchived) "Restore" else "Archive")
                            },
                            onClick = {
                                viewModel.archive(item.person.id, !item.person.isArchived)
                                showMenu = false
                            },
                        )
                        if (!item.person.isShared) {
                            DropdownMenuItem(
                                text = {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = { pendingDelete = item.person.id; showMenu = false },
                            )
                        }
                    }
                }
            }
        }
    }

    if (pendingDelete != 0L) {
        ConfirmDialog(
            title = "Delete this person?",
            message = "Their accounts and payments are kept but will no longer be " +
                "assigned to anyone.",
            confirmLabel = "Delete",
            isDestructive = true,
            alternativeLabel = "Archive instead",
            onAlternative = { viewModel.archive(pendingDelete, true) },
            onConfirm = { viewModel.delete(pendingDelete) },
            onDismiss = { pendingDelete = 0L },
        )
    }
}

/**
 * One line of what a person's yearly pay means for their month, or null when
 * they have not given it.
 */
private fun incomeLine(item: PersonSummary): String? {
    val income = item.income
    val monthly = income.netMonthlyMinor ?: return income.grossYearlyMinor
        ?.let { "${Money.format(it)} a year before tax — add take-home for more" }
    val parts = mutableListOf("${Money.format(monthly)} take-home a month")
    income.deductionPercent?.let { parts += "$it% taken in tax and deductions" }
    income.shareOfMonthlyTakeHome(item.spentThisMonthMinor)?.let {
        parts += "$it% spent this month"
    }
    if (item.savedThisMonthMinor > 0L) {
        income.shareOfMonthlyTakeHome(item.savedThisMonthMinor)?.let {
            parts += "$it% put aside"
        }
    }
    return parts.joinToString(" · ")
}

/**
 * The household's pay, and this month measured against it.
 *
 * Statements show what arrived; this shows what that is out of, and what
 * share of it the month has spent and put aside. Without yearly pay entered
 * it says how to get it.
 */
@Composable
private fun HouseholdIncomeCard(state: PeopleState) {
    val income = state.household
    SectionCard(title = "Yearly pay", subtitle = "Everyone together") {
        if (!income.hasAny) {
            Text(
                text = "Add each person's yearly pay — before and after tax — to see " +
                    "take-home a month, how much goes in tax, and what share of it " +
                    "each month is spent and saved. Tap a person to add theirs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        income.grossYearlyMinor?.let { StatLine("Before tax, a year", Money.format(it)) }
        income.netYearlyMinor?.let { StatLine("Take-home, a year", Money.format(it)) }
        income.netMonthlyMinor?.let { StatLine("Take-home, a month", Money.format(it)) }
        income.deductionsYearlyMinor?.let { deductions ->
            StatLine(
                "Tax and deductions",
                Money.format(deductions) +
                    (income.deductionPercent?.let { " ($it%)" } ?: ""),
            )
        }
        income.shareOfMonthlyTakeHome(state.householdSpentMinor)?.let {
            StatLine("Spent this month", "${Money.format(state.householdSpentMinor)} ($it%)")
        }
        income.shareOfMonthlyTakeHome(state.householdSavedMinor)?.let {
            StatLine("Put aside this month", "${Money.format(state.householdSavedMinor)} ($it%)")
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Add or edit one person. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonEditScreen(
    onBack: () -> Unit,
    viewModel: PersonEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.isSaved) { if (state.isSaved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New person" else "Edit person") },
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.errorSummary?.let {
                ErrorBanner(message = it, onDismiss = viewModel::clearError)
            }

            LabelledTextField(
                label = "Name",
                value = state.name,
                onValueChange = viewModel::setName,
                placeholder = "Hannah",
            )

            ColorPicker(
                colors = DefaultData.PERSON_COLORS + DefaultData.PALETTE,
                selected = state.colorHex,
                onSelect = viewModel::setColor,
            )

            // Used for statistics only: take-home a month, how much goes in
            // tax, and what share of pay is spent and saved.
            AmountField(
                label = "Yearly pay before tax (optional)",
                value = state.grossYearlyText,
                onValueChange = viewModel::setGrossYearly,
            )
            AmountField(
                label = "Yearly take-home after tax (optional)",
                value = state.netYearlyText,
                onValueChange = viewModel::setNetYearly,
            )

            LabelledTextField(
                label = "Notes",
                value = state.notes,
                onValueChange = viewModel::setNotes,
                singleLine = false,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.isNew) "Add person" else "Save changes")
            }
        }
    }
}
