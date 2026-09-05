package com.rhys.financetracker.ui.savings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.labelFor
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorPicker
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.ProgressBarRow
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.StatEmphasis
import com.rhys.financetracker.ui.components.StatTile
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/**
 * Savings goals.
 *
 * Each goal shows not just how far along it is, but whether the current
 * contribution will actually get there in time — the question a progress bar
 * on its own does not answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsScreen(
    onEditGoal: (Long) -> Unit,
    onAddGoal: () -> Unit,
    viewModel: SavingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableLongStateOf(0L) }
    var contributeTo by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Savings") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddGoal) {
                Icon(Icons.Default.Add, contentDescription = "Add a savings goal")
            }
        },
    ) { padding ->
        if (state.goals.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Savings,
                title = "No savings goals yet",
                message = "A goal turns \"saving a bit each month\" into something you can " +
                    "watch getting closer — a holiday, an emergency fund, Christmas.",
                actionLabel = "Add a goal",
                onAction = onAddGoal,
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
                        label = "In savings accounts",
                        value = Money.format(state.totalSavedMinor),
                        emphasis = StatEmphasis.POSITIVE,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Put by each month",
                        value = Money.format(state.monthlyContributionMinor),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            item {
                SectionCard(
                    title = "All goals together",
                    subtitle = "${Money.format(state.totalInGoalsMinor)} of " +
                        Money.format(state.totalTargetMinor),
                ) {
                    ProgressBarRow(
                        label = "Overall progress",
                        value = "${(state.overallProgress * 100).toInt()}%",
                        fraction = state.overallProgress,
                        color = FinanceTheme.colors.savings,
                    )
                }
            }

            items(state.goals, key = { it.goal.goal.id }) { summary ->
                GoalCard(
                    summary = summary,
                    onClick = { onEditGoal(summary.goal.goal.id) },
                    onContribute = { contributeTo = summary.goal.goal.id },
                    onDuplicate = { viewModel.duplicate(summary.goal.goal.id) },
                    onArchive = { viewModel.archive(summary.goal.goal.id, true) },
                    onDelete = { pendingDelete = summary.goal.goal.id },
                )
            }
        }
    }

    if (pendingDelete != 0L) {
        ConfirmDialog(
            title = "Delete this goal?",
            message = "The money stays exactly where it is — only the goal is removed.",
            confirmLabel = "Delete",
            isDestructive = true,
            alternativeLabel = "Archive instead",
            onAlternative = { viewModel.archive(pendingDelete, true) },
            onConfirm = { viewModel.delete(pendingDelete) },
            onDismiss = { pendingDelete = 0L },
        )
    }

    if (contributeTo != 0L) {
        ContributeDialog(
            onConfirm = { amount ->
                viewModel.addToGoal(contributeTo, amount)
                contributeTo = 0L
            },
            onDismiss = { contributeTo = 0L },
        )
    }
}

@Composable
private fun GoalCard(
    summary: GoalSummary,
    onClick: () -> Unit,
    onContribute: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val goal = summary.goal

    SectionCard(
        title = goal.goal.name,
        subtitle = goal.accountName ?: "Tracked by hand",
        action = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        onClick = { onClick(); showMenu = false },
                    )
                    if (goal.goal.accountId == null) {
                        DropdownMenuItem(
                            text = { Text("Add money") },
                            onClick = { onContribute(); showMenu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Duplicate") },
                        onClick = { onDuplicate(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Archive") },
                        onClick = { onArchive(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { onDelete(); showMenu = false },
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        ProgressBarRow(
            label = "${Money.format(goal.currentAmountMinor)} of " +
                Money.format(goal.goal.targetAmountMinor),
            value = "${goal.percentComplete}%",
            fraction = goal.progressFraction,
            color = colorFromHex(goal.goal.colorHex),
            secondary = "${Money.format(goal.remainingMinor)} still to find",
        )

        Spacer(Modifier.height(8.dp))

        goal.goal.targetDate?.let { target ->
            Text(
                text = "Target date ${DateUtils.format(target)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        summary.requiredMonthlyMinor?.let { required ->
            Text(
                text = "Needs ${Money.format(required)} a month to get there on time",
                style = MaterialTheme.typography.bodySmall,
                color = if (summary.isBehind) {
                    FinanceTheme.colors.warning
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        summary.projectedCompletion?.let { projected ->
            Text(
                text = "At ${Money.format(goal.goal.monthlyContributionMinor)} a month you " +
                    "will reach it around ${DateUtils.format(projected)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Adds to a goal that is tracked by hand rather than linked to an account. */
@Composable
private fun ContributeDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to this goal") },
        text = {
            Column {
                Text(
                    text = "Enter an amount to add. Use a minus sign to take money back out.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                AmountField(
                    label = "Amount",
                    value = amount,
                    onValueChange = { amount = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(amount) }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Add or edit one savings goal. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsEditScreen(
    onBack: () -> Unit,
    viewModel: SavingsEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.form.isSaved) { if (state.form.isSaved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New savings goal" else "Edit savings goal") },
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
                label = "What are you saving for?",
                value = state.form.name,
                onValueChange = { text -> viewModel.update { it.copy(name = text) } },
                placeholder = "Holiday",
            )

            AmountField(
                label = "Target amount",
                value = state.form.targetText,
                onValueChange = { text -> viewModel.update { it.copy(targetText = text) } },
            )

            AmountField(
                label = "Already saved",
                value = state.form.startingText,
                onValueChange = { text -> viewModel.update { it.copy(startingText = text) } },
            )

            AmountField(
                label = "Putting by each month",
                value = state.form.monthlyText,
                onValueChange = { text -> viewModel.update { it.copy(monthlyText = text) } },
            )

            DateField(
                label = "Want it by (optional)",
                date = state.form.targetDate,
                onDateChange = { date -> viewModel.update { it.copy(targetDate = date) } },
                allowClear = true,
                onClear = { viewModel.update { it.copy(targetDate = null) } },
            )

            DropdownField(
                label = "Linked account",
                options = state.accounts,
                selected = state.accounts.firstOrNull { it.id == state.form.accountId },
                onSelect = { account -> viewModel.update { it.copy(accountId = account.id) } },
                optionLabel = { state.accounts.labelFor(it) },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "Track this goal by hand",
            )

            DropdownField(
                label = "Whose goal is it?",
                options = state.people,
                selected = state.people.firstOrNull { it.id == state.form.personId },
                onSelect = { person -> viewModel.update { it.copy(personId = person.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "Everyone",
            )

            ColorPicker(
                colors = DefaultData.PALETTE,
                selected = state.form.colorHex,
                onSelect = { hex -> viewModel.update { it.copy(colorHex = hex) } },
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
                Text(if (state.isNew) "Add goal" else "Save changes")
            }
        }
    }
}
