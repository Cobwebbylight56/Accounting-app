package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.SplitPart
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.receipts.SplitRepository
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.navigation.Routes
import com.rhys.financetracker.ui.receipts.PaymentReceipts
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class TransactionViewViewModel @Inject constructor(
    transactionDao: TransactionDao,
    splits: SplitRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val transactionId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    val payment: StateFlow<TransactionWithDetails?> = transactionDao.observeDetailsById(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val parts: StateFlow<List<SplitPart>> = splits.observeParts(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/**
 * A payment as it is, before changing anything: what, how much, where it
 * came from, its receipt and how it is split. Edit and Split are a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionViewScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onSplit: (Long) -> Unit,
    viewModel: TransactionViewViewModel = hiltViewModel(),
) {
    val item by viewModel.payment.collectAsStateWithLifecycle()
    val parts by viewModel.parts.collectAsStateWithLifecycle()
    val colors = FinanceTheme.colors
    // Deleted from the editor: nothing left to show, so close.
    var seen by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(item) {
        if (item != null) seen = true else if (seen) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Payment") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { onEdit(viewModel.transactionId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                    }
                },
            )
        },
    ) { padding ->
        val details = item ?: return@Scaffold
        val entry = details.transaction
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(entry.description, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val sign = when (entry.type) {
                    TransactionType.INCOME -> "+"
                    TransactionType.EXPENSE -> "−"
                    TransactionType.TRANSFER -> ""
                }
                Text(
                    sign + Money.format(entry.amountMinor),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (entry.type) {
                        TransactionType.INCOME -> colors.income
                        TransactionType.EXPENSE -> colors.expense
                        TransactionType.TRANSFER -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    DateUtils.format(entry.date),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Fact("Category") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(colorFromHex(details.categoryColor), size = 10.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(details.categoryName ?: "Not sorted yet", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Fact("Account", listOfNotNull(details.accountName, details.transferAccountName).joinToString(" → "))
                    details.personName?.let { Fact("For", it) }
                    Fact("From", sourceOf(entry.source))
                    if (!entry.isCleared) Fact("Status", "Not gone through yet")
                    if (!entry.isConfirmed) Fact("Status", "To check — the amount may differ")
                    entry.notes?.let { Fact("Notes", it) }
                    entry.tags?.let { Fact("Tags", it) }
                }
            }

            if (entry.type == TransactionType.EXPENSE) {
                if (parts.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Split by category", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            parts.forEach { part ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ColorDot(colorFromHex(part.categoryColor ?: details.categoryColor), size = 8.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(part.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(
                                        (part.categoryName ?: details.categoryName ?: "") + "  " + Money.format(part.amountMinor),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedButton(onClick = { onSplit(entry.id) }) { Text("Change the split") }
                        }
                    }
                } else {
                    OutlinedButton(onClick = { onSplit(entry.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Split across categories")
                    }
                }
            }

            PaymentReceipts(transactionId = entry.id)

            Spacer(Modifier.height(8.dp))
            Button(onClick = { onEdit(entry.id) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Edit")
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) = Fact(label) {
    Text(value, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Fact(label: String, value: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
    }
    HorizontalDivider()
}

private fun sourceOf(source: RecordSource): String = when (source) {
    RecordSource.STATEMENT -> "A bank statement"
    RecordSource.LIVE -> "A bank alert — the statement will replace it when it comes in"
    RecordSource.SPREADSHEET -> "Your spreadsheet"
    RecordSource.MANUAL -> "Typed in"
    RecordSource.UNKNOWN -> "Added before the app kept track"
}
