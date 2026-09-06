package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.ExportFormat
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.LoadingState
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme

/**
 * The ledger: every transaction, searchable and filterable.
 *
 * Search covers descriptions, notes, tags, and the names of the account,
 * category and person, because people remember "the thing I paid Hannah for"
 * rather than which field they typed it in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionListScreen(
    onOpenTransaction: (Long) -> Unit,
    onAddTransaction: () -> Unit,
    onOpenImport: () -> Unit,
    onShareFile: (com.rhys.financetracker.data.export.ExportedFile) -> Unit,
    viewModel: TransactionListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilters by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDeleteShown by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    if (confirmDeleteShown) {
        ConfirmDialog(
            title = "Delete these ${state.resultCount} entries?",
            message = "Everything the list is showing right now will be removed, and that " +
                "cannot be undone. Only what matches your filters goes — change them first " +
                "if you meant something narrower.",
            confirmLabel = "Delete",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteShown()
                confirmDeleteShown = false
            },
            onDismiss = { confirmDeleteShown = false },
        )
    }

    LaunchedEffect(state.exportedFile) {
        state.exportedFile?.let {
            onShareFile(it)
            viewModel.consumeExportedFile()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Money") },
                actions = {
                    BadgedBox(
                        badge = {
                            if (state.filter.activeFilterCount > 0) {
                                Badge { Text("${state.filter.activeFilterCount}") }
                            }
                        },
                    ) {
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Filters")
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            // Sits with the exports because this is the screen
                            // you are on when you think about transactions
                            // arriving, not Settings.
                            DropdownMenuItem(
                                text = { Text("Import a bank statement") },
                                onClick = {
                                    onOpenImport()
                                    showMenu = false
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Export as CSV") },
                                onClick = {
                                    viewModel.exportResults(ExportFormat.CSV)
                                    showMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Export as Excel") },
                                onClick = {
                                    viewModel.exportResults(ExportFormat.XLSX)
                                    showMenu = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddTransaction,
                text = { Text("Add") },
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SearchBar(
                value = state.searchText,
                onValueChange = viewModel::setSearchText,
                onClear = { viewModel.setSearchText("") },
            )

            if (state.hasFilters || state.transactions.isNotEmpty()) {
                ResultSummary(state = state, onClearFilters = viewModel::clearFilters)
            }

            when {
                state.isLoading -> LoadingState()

                state.transactions.isEmpty() && state.hasFilters -> EmptyState(
                    icon = Icons.Outlined.ReceiptLong,
                    title = "Nothing matched",
                    message = "Try a different search, or clear the filters to see everything.",
                    actionLabel = "Clear filters",
                    onAction = viewModel::clearFilters,
                )

                state.transactions.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.ReceiptLong,
                    title = "No transactions yet",
                    message = "Everything you spend and receive will appear here. Regular " +
                        "bills are added for you once you set them up.",
                    actionLabel = "Add a transaction",
                    onAction = onAddTransaction,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.transactions, key = { it.transaction.id }) { item ->
                        TransactionRow(
                            item = item,
                            onClick = { onOpenTransaction(item.transaction.id) },
                            onConfirm = { viewModel.confirm(item.transaction.id) },
                            onDuplicate = { viewModel.duplicate(item.transaction.id) },
                            onArchive = { viewModel.archive(item.transaction.id) },
                            onUnarchive = { viewModel.unarchive(item.transaction.id) },
                            onDelete = { viewModel.delete(item.transaction.id) },
                        )
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(
            onDismissRequest = { showFilters = false },
            sheetState = sheetState,
        ) {
            TransactionFilterSheet(
                state = state,
                viewModel = viewModel,
                onDone = { showFilters = false },
            )
        }
    }
}

@Composable
private fun SearchBar(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search name, note, category, person…") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
    )
}

/** The running totals for whatever is currently on screen. */
@Composable
private fun ResultSummary(state: TransactionListState, onClearFilters: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.resultCount} " +
                        if (state.resultCount == 1) "entry" else "entries",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "${Money.format(state.totalIncomeMinor)} in · " +
                        "${Money.format(state.totalExpenseMinor)} out · " +
                        "${Money.format(state.netMinor, showSign = true)} net",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.hasFilters) {
                TextButton(onClick = onClearFilters) { Text("Clear") }
            }
        }
    }
}

/** One row in the ledger, with its actions behind a long press. */
@Composable
private fun TransactionRow(
    item: TransactionWithDetails,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val colors = FinanceTheme.colors
    val entry = item.transaction

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorDot(colorFromHex(item.categoryColor), size = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.description,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!entry.isConfirmed) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = colors.warningContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "Check",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onWarningContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (entry.isArchived) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Archived",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = listOfNotNull(
                        DateUtils.formatShort(entry.date),
                        item.categoryName,
                        item.accountName,
                        item.personName,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when (entry.type) {
                        TransactionType.INCOME -> "+${Money.format(entry.amountMinor)}"
                        TransactionType.EXPENSE -> "−${Money.format(entry.amountMinor)}"
                        TransactionType.TRANSFER -> "→ ${Money.format(entry.amountMinor)}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = when (entry.type) {
                        TransactionType.INCOME -> colors.income
                        TransactionType.EXPENSE -> colors.expense
                        TransactionType.TRANSFER -> colors.transfer
                    },
                )
                if (entry.type == TransactionType.TRANSFER && item.transferAccountName != null) {
                    Text(
                        text = item.transferAccountName,
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
            if (!entry.isConfirmed) {
                DropdownMenuItem(
                    text = { Text("Confirm amount") },
                    onClick = { onConfirm(); showMenu = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Edit") },
                onClick = { onClick(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Duplicate") },
                onClick = { onDuplicate(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (entry.isArchived) "Restore" else "Archive") },
                onClick = {
                    if (entry.isArchived) onUnarchive() else onArchive()
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                },
                onClick = { onDelete(); showMenu = false },
            )
        }
    }
}
