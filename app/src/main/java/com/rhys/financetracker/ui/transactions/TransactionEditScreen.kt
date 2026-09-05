package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SegmentedChoice
import com.rhys.financetracker.ui.components.colorFromHex

/**
 * The add/edit transaction form.
 *
 * The order of the fields follows how people think about a payment: what it
 * was, how much, when, and only then the bookkeeping details.  Everything below
 * "Notes" is optional.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditScreen(
    onBack: () -> Unit,
    viewModel: TransactionEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "New transaction" else "Edit transaction") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
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
                ErrorBanner(message = it, onDismiss = viewModel::clearMessage)
            }

            SegmentedChoice(
                options = TransactionType.entries,
                selected = state.form.type,
                onSelect = viewModel::setType,
                optionLabel = { it.displayName },
            )

            LabelledTextField(
                label = "What was it?",
                value = state.form.description,
                onValueChange = { text -> viewModel.update { it.copy(description = text) } },
                placeholder = "Tesco, salary, car insurance…",
                error = state.form.descriptionError,
            )

            AmountField(
                label = "Amount",
                value = state.form.amountText,
                onValueChange = { text -> viewModel.update { it.copy(amountText = text) } },
                error = state.form.amountError,
            )

            DateField(
                label = "Date",
                date = state.form.date,
                onDateChange = { date -> viewModel.update { it.copy(date = date) } },
                error = state.form.dateError,
            )

            DropdownField(
                label = if (state.form.type == TransactionType.TRANSFER) "From account" else "Account",
                options = state.accounts,
                selected = state.accounts.firstOrNull { it.id == state.form.accountId },
                onSelect = { account -> viewModel.update { it.copy(accountId = account.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
            )

            if (state.form.type == TransactionType.TRANSFER) {
                DropdownField(
                    label = "To account",
                    options = state.accounts.filter { it.id != state.form.accountId },
                    selected = state.accounts.firstOrNull { it.id == state.form.transferAccountId },
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
                label = "Who is this for?",
                options = state.people,
                selected = state.people.firstOrNull { it.id == state.form.personId },
                onSelect = { person -> viewModel.update { it.copy(personId = person.id) } },
                optionLabel = { it.name },
                optionColor = { colorFromHex(it.colorHex) },
                placeholder = "The account's owner",
            )

            if (state.savingsGoals.isNotEmpty()) {
                DropdownField(
                    label = "Put towards a goal",
                    options = state.savingsGoals,
                    selected = state.savingsGoals.firstOrNull { it.id == state.form.savingsGoalId },
                    onSelect = { goal -> viewModel.update { it.copy(savingsGoalId = goal.id) } },
                    optionLabel = { it.name },
                    optionColor = { colorFromHex(it.colorHex) },
                    placeholder = "Not linked to a goal",
                )
            }

            LabelledTextField(
                label = "Notes",
                value = state.form.notes,
                onValueChange = { text -> viewModel.update { it.copy(notes = text) } },
                singleLine = false,
                supportingText = "Anything you might want to search for later",
            )

            LabelledTextField(
                label = "Tags",
                value = state.form.tags,
                onValueChange = { text -> viewModel.update { it.copy(tags = text) } },
                placeholder = "holiday, birthday",
                supportingText = "Separate tags with commas",
                keyboardType = KeyboardType.Text,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Money has left the account", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "Turn this off for a payment you have arranged but that has " +
                            "not gone through yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.form.isCleared,
                    onCheckedChange = { checked ->
                        viewModel.update { it.copy(isCleared = checked) }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (state.isNew) "Add transaction" else "Save changes")
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "Delete this transaction?",
            message = "It will be removed permanently and your totals will change. " +
                "Archiving keeps it in your history but hides it from lists.",
            confirmLabel = "Delete",
            isDestructive = true,
            alternativeLabel = null,
            onConfirm = viewModel::delete,
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
