package com.rhys.financetracker.ui.recurring

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
import androidx.compose.material.icons.outlined.EventRepeat
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
import androidx.compose.material3.Surface
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
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.SegmentedChoice
import com.rhys.financetracker.ui.components.StatEmphasis
import com.rhys.financetracker.ui.components.StatTile
import com.rhys.financetracker.ui.components.SwitchRow
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/** The list of everything that repeats. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringScreen(
    onBack: () -> Unit,
    onEditRule: (Long) -> Unit,
    onAddRule: () -> Unit,
    viewModel: RecurringViewModel = hiltViewModel(),
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
                title = { Text("Regular payments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::catchUpNow) { Text("Update now") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRule) {
                Icon(Icons.Default.Add, contentDescription = "Add a regular payment")
            }
        },
    ) { padding ->
        if (state.rules.isEmpty() && state.typeFilter == null) {
            EmptyState(
                icon = Icons.Outlined.EventRepeat,
                title = "Nothing repeating yet",
                message = "Set up your salary and your bills once. From then on the app " +
                    "records them for you each time they are due.",
                actionLabel = "Add a regular payment",
                onAction = onAddRule,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile(
                        label = "In each month",
                        value = Money.format(state.monthlyIncomeMinor),
                        emphasis = StatEmphasis.POSITIVE,
                        modifier = Modifier.weight(1f),
                    )
                    StatTile(
                        label = "Out each month",
                        value = Money.format(state.monthlyExpenseMinor),
                        emphasis = StatEmphasis.NEGATIVE,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                StatTile(
                    label = "Left after regular payments",
                    value = Money.format(state.monthlyLeftOverMinor, showSign = true),
                    caption = "Including ${Money.format(state.monthlySavingsMinor)} put " +
                        "into savings each month",
                    emphasis = if (state.monthlyLeftOverMinor < 0L) {
                        StatEmphasis.NEGATIVE
                    } else {
                        StatEmphasis.POSITIVE
                    },
                )
            }

            item {
                SegmentedChoice(
                    options = listOf<TransactionType?>(null) + TransactionType.entries,
                    selected = state.typeFilter,
                    onSelect = viewModel::setTypeFilter,
                    optionLabel = { it?.displayName ?: "All" },
                )
            }

            items(state.rules, key = { it.rule.id }) { item ->
                RecurringRow(
                    item = item,
                    isOverdue = item.rule.id in state.overdueIds,
                    onClick = { onEditRule(item.rule.id) },
                    onPause = { viewModel.setPaused(item.rule.id, !item.rule.isPaused) },
                    onDuplicate = { viewModel.duplicate(item.rule.id) },
                    onArchive = { viewModel.archive(item.rule.id, true) },
                    onDelete = { pendingDelete = item.rule.id },
                )
            }
        }
    }

    if (pendingDelete != 0L) {
        ConfirmDialog(
            title = "Delete this regular payment?",
            message = "Entries it has already created stay in your history. It just stops " +
                "creating new ones.",
            confirmLabel = "Delete",
            isDestructive = true,
            alternativeLabel = "Pause instead",
            onAlternative = { viewModel.setPaused(pendingDelete, true) },
            onConfirm = { viewModel.delete(pendingDelete) },
            onDismiss = { pendingDelete = 0L },
        )
    }
}

@Composable
private fun RecurringRow(
    item: RecurringRuleWithDetails,
    isOverdue: Boolean,
    onClick: () -> Unit,
    onPause: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = FinanceTheme.colors
    val rule = item.rule

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(colorFromHex(item.categoryColor), size = 14.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.name + if (rule.isPaused) " (paused)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(frequencyLabel(rule.frequency, rule.interval))
                            append(" · ")
                            append(
                                if (isOverdue) {
                                    "was due ${DateUtils.formatShort(rule.nextDueDate)}"
                                } else {
                                    "next ${DateUtils.relativeDescription(rule.nextDueDate)
                                        .lowercase()}"
                                },
                            )
                            item.accountName?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isOverdue) {
                            colors.negative
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Money.format(rule.amountMinor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = when (rule.type) {
                            TransactionType.INCOME -> colors.income
                            TransactionType.EXPENSE -> colors.expense
                            TransactionType.TRANSFER -> colors.transfer
                        },
                    )
                    if (rule.isVariableAmount) {
                        Text(
                            text = "varies",
                            style = MaterialTheme.typography.labelSmall,
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
                    onClick = { onClick(); showMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(if (rule.isPaused) "Resume" else "Pause") },
                    onClick = { onPause(); showMenu = false },
                )
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
    }
}

/** "Every 3 months" reads better than "CUSTOM_MONTHS, interval 3". */
private fun frequencyLabel(frequency: Frequency, interval: Int): String = when (frequency) {
    Frequency.CUSTOM_DAYS -> if (interval == 1) "Daily" else "Every $interval days"
    Frequency.CUSTOM_MONTHS -> if (interval == 1) "Monthly" else "Every $interval months"
    else -> frequency.displayName
}

/** Add or edit one regular payment. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringEditScreen(
    onBack: () -> Unit,
    viewModel: RecurringEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.form.isSaved) { if (state.form.isSaved) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (state.isNew) "New regular payment" else "Edit regular payment")
                },
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

            SegmentedChoice(
                options = TransactionType.entries,
                selected = state.form.type,
                onSelect = viewModel::setType,
                optionLabel = { it.displayName },
            )

            LabelledTextField(
                label = "Name",
                value = state.form.name,
                onValueChange = { text -> viewModel.update { it.copy(name = text) } },
                placeholder = "Council tax",
            )

            AmountField(
                label = "Amount",
                value = state.form.amountText,
                onValueChange = { text -> viewModel.update { it.copy(amountText = text) } },
            )

            DropdownField(
                label = "How often",
                options = Frequency.entries,
                selected = state.form.frequency,
                onSelect = { frequency -> viewModel.update { it.copy(frequency = frequency) } },
                optionLabel = { it.displayName },
            )

            if (state.form.frequency.isCustom) {
                LabelledTextField(
                    label = if (state.form.frequency == Frequency.CUSTOM_DAYS) {
                        "Repeat every this many days"
                    } else {
                        "Repeat every this many months"
                    },
                    value = state.form.intervalText,
                    onValueChange = { text ->
                        viewModel.update { it.copy(intervalText = text.filter { c -> c.isDigit() }) }
                    },
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            }

            DateField(
                label = "First due on",
                date = state.form.startDate,
                onDateChange = { date -> viewModel.update { it.copy(startDate = date) } },
            )

            DateField(
                label = "Stops after (optional)",
                date = state.form.endDate,
                onDateChange = { date -> viewModel.update { it.copy(endDate = date) } },
                allowClear = true,
                onClear = { viewModel.update { it.copy(endDate = null) } },
            )

            if (state.upcomingDates.isNotEmpty()) {
                SectionCard(
                    title = "Next few dates",
                    subtitle = "Check this looks right before saving",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.upcomingDates.take(4).forEach { date ->
                            AssistChip(
                                onClick = {},
                                label = { Text(DateUtils.formatShort(date)) },
                            )
                        }
                    }
                }
            }

            DropdownField(
                label = if (state.form.type == TransactionType.TRANSFER) {
                    "Comes out of"
                } else {
                    "Account"
                },
                options = state.accounts,
                selected = state.accounts.firstOrNull { it.id == state.form.accountId },
                onSelect = { account -> viewModel.update { it.copy(accountId = account.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
            )

            if (state.form.type == TransactionType.TRANSFER) {
                DropdownField(
                    label = "Goes into",
                    options = state.accounts.filter { it.id != state.form.accountId },
                    selected = state.accounts.firstOrNull {
                        it.id == state.form.transferAccountId
                    },
                    onSelect = { account ->
                        viewModel.update { it.copy(transferAccountId = account.id) }
                    },
                    optionLabel = { it.name },
                    optionColor = { colorFromHex(it.colorHex) },
                )
            }

            DropdownField(
                label = "Category",
                options = state.categories,
                selected = state.categories.firstOrNull { it.id == state.form.categoryId },
                onSelect = { category -> viewModel.update { it.copy(categoryId = category.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "Not categorised",
            )

            DropdownField(
                label = "Who is responsible",
                options = state.people,
                selected = state.people.firstOrNull { it.id == state.form.personId },
                onSelect = { person -> viewModel.update { it.copy(personId = person.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "The account's owner",
            )

            DropdownField(
                label = "When it falls due",
                options = RecurrenceMode.entries,
                selected = state.form.mode,
                onSelect = { mode -> viewModel.update { it.copy(mode = mode) } },
                optionLabel = { it.displayName },
            )

            LabelledTextField(
                label = "Remind me this many days before",
                value = state.form.reminderDaysText,
                onValueChange = { text ->
                    viewModel.update { it.copy(reminderDaysText = text.filter { c -> c.isDigit() }) }
                },
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                supportingText = "Leave empty for no reminder",
            )

            SwitchRow(
                label = "The amount changes each time",
                checked = state.form.isVariableAmount,
                onCheckedChange = { checked ->
                    viewModel.update { it.copy(isVariableAmount = checked) }
                },
            )

            SwitchRow(
                label = "Paused",
                checked = state.form.isPaused,
                onCheckedChange = { checked -> viewModel.update { it.copy(isPaused = checked) } },
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
                Text(if (state.isNew) "Add regular payment" else "Save changes")
            }
        }
    }
}
