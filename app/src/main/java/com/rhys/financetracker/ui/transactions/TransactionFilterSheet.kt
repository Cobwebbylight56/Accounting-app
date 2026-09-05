package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.FilterChipRow
import com.rhys.financetracker.ui.components.SwitchRow

/**
 * The filter panel.
 *
 * Every field the specification asks to filter on is here — name, date,
 * category, person, account, amount and notes — with the text search covering
 * name and notes from the search bar above.
 */
@Composable
fun TransactionFilterSheet(
    state: TransactionListState,
    viewModel: TransactionListViewModel,
    onDone: () -> Unit,
) {
    var minAmount by remember {
        mutableStateOf(state.filter.minAmountMinor?.let { Money.formatPlain(it) }.orEmpty())
    }
    var maxAmount by remember {
        mutableStateOf(state.filter.maxAmountMinor?.let { Money.formatPlain(it) }.orEmpty())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Filters",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::clearFilters) { Text("Reset") }
        }

        FilterSection("Sort by") {
            DropdownField(
                label = "Order",
                options = TransactionSort.entries,
                selected = state.filter.sort,
                onSelect = viewModel::setSort,
                optionLabel = { it.displayName },
            )
        }

        FilterSection("Type") {
            FilterChipRow(
                options = TransactionType.entries,
                selected = state.filter.types,
                onToggle = viewModel::toggleType,
                optionLabel = { it.displayName },
            )
        }

        FilterSection("Dates") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateField(
                    label = "From",
                    date = state.filter.dateFrom,
                    onDateChange = { viewModel.setDateRange(it, state.filter.dateTo) },
                    allowClear = true,
                    onClear = { viewModel.setDateRange(null, state.filter.dateTo) },
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    label = "To",
                    date = state.filter.dateTo,
                    onDateChange = { viewModel.setDateRange(state.filter.dateFrom, it) },
                    allowClear = true,
                    onClear = { viewModel.setDateRange(state.filter.dateFrom, null) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        FilterSection("Amount") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AmountField(
                    label = "At least",
                    value = minAmount,
                    onValueChange = {
                        minAmount = it
                        viewModel.setAmountRange(minAmount, maxAmount)
                    },
                    modifier = Modifier.weight(1f),
                )
                AmountField(
                    label = "At most",
                    value = maxAmount,
                    onValueChange = {
                        maxAmount = it
                        viewModel.setAmountRange(minAmount, maxAmount)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.accounts.isNotEmpty()) {
            FilterSection("Accounts") {
                FilterChipRow(
                    options = state.accounts,
                    selected = state.accounts.filter { it.id in state.filter.accountIds }.toSet(),
                    onToggle = { viewModel.toggleAccount(it.id) },
                    optionLabel = { it.name },
                )
            }
        }

        if (state.categories.isNotEmpty()) {
            FilterSection("Categories") {
                FilterChipRow(
                    options = state.categories,
                    selected = state.categories.filter { it.id in state.filter.categoryIds }.toSet(),
                    onToggle = { viewModel.toggleCategory(it.id) },
                    optionLabel = { it.name },
                )
            }
        }

        if (state.people.isNotEmpty()) {
            FilterSection("People") {
                FilterChipRow(
                    options = state.people,
                    selected = state.people.filter { it.id in state.filter.personIds }.toSet(),
                    onToggle = { viewModel.togglePerson(it.id) },
                    optionLabel = { it.name },
                )
            }
        }

        HorizontalDivider()

        SwitchRow(
            label = "Only entries needing a check",
            checked = state.filter.onlyUnconfirmed,
            onCheckedChange = viewModel::setOnlyUnconfirmed,
        )
        SwitchRow(
            label = "Include archived entries",
            checked = state.filter.includeArchived,
            onCheckedChange = viewModel::setShowArchived,
        )

        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Show ${state.resultCount} results")
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}
