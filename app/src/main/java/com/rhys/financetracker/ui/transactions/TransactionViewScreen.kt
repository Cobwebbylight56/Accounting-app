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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.SubscriptionKinds
import com.rhys.financetracker.data.local.dao.SplitPart
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.receipts.SplitRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurringTransactionGenerator
import com.rhys.financetracker.domain.recurrence.RegularSchedule
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.navigation.Routes
import com.rhys.financetracker.ui.receipts.PaymentReceipts
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TransactionViewViewModel @Inject constructor(
    transactionDao: TransactionDao,
    splits: SplitRepository,
    private val recurring: RecurringRepository,
    private val accounts: AccountRepository,
    private val generator: RecurringTransactionGenerator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val transactionId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    val payment: StateFlow<TransactionWithDetails?> = transactionDao.observeDetailsById(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val parts: StateFlow<List<SplitPart>> = splits.observeParts(transactionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The regular payment this is one of, if it is. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val regular: StateFlow<RecurringRuleEntity?> = payment
        .map { it?.transaction?.recurringRuleId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else recurring.observe(id).map { it?.rule } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What kind of regular payment it most likely is, to start the choice on. */
    val kindGuess: StateFlow<PaymentKind> = payment
        .map { details ->
            val entry = details?.transaction ?: return@map PaymentKind.DIRECT_DEBIT
            RegularSchedule.guessKind(
                type = entry.type,
                description = entry.description,
                toHolding = entry.transferAccountId?.let { accounts.get(it)?.holding },
                isSubscription = SubscriptionKinds.isSubscription(entry.description, details.categoryName),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaymentKind.DIRECT_DEBIT)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun makeRegular(kind: PaymentKind, frequency: Frequency, day: Int, addByItself: Boolean, amountChanges: Boolean) {
        val entry = payment.value?.transaction ?: return
        viewModelScope.launch {
            when (val result = recurring.makeRegular(entry, kind, frequency, day, addByItself, amountChanges)) {
                is AppResult.Success -> {
                    // Anything due since — a month missed — goes in now.
                    generator.generateDue()
                    _message.value = if (addByItself) {
                        "Saved — it'll be added by itself each time"
                    } else {
                        "Saved — you'll get a reminder each time"
                    }
                }
                is AppResult.Failure -> _message.value = result.message
            }
        }
    }

    fun stopRegular() {
        val rule = regular.value ?: return
        viewModelScope.launch {
            recurring.setArchived(rule.id, archived = true)
            _message.value = "Stopped — it won't be added any more"
        }
    }
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
    val regular by viewModel.regular.collectAsStateWithLifecycle()
    val kindGuess by viewModel.kindGuess.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val colors = FinanceTheme.colors
    var settingUp by remember { androidx.compose.runtime.mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    // Deleted from the editor: nothing left to show, so close.
    var seen by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(item) {
        if (item != null) seen = true else if (seen) onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
                    details.regularKind?.let { Fact("Type", it.displayName) }
                    Fact("From", sourceOf(entry.source, scheduled = entry.recurringRuleId != null))
                    if (!entry.isCleared) Fact("Status", "Not gone through yet")
                    if (!entry.isConfirmed) Fact("Status", "To check — the amount may differ")
                    entry.notes?.let { Fact("Notes", it) }
                    entry.tags?.let { Fact("Tags", it) }
                }
            }

            RegularPaymentCard(
                rule = regular,
                isTransfer = entry.type == TransactionType.TRANSFER,
                onSetUp = { settingUp = true },
                onStop = viewModel::stopRegular,
            )
            if (settingUp) {
                RegularPaymentSheet(
                    payment = entry,
                    rule = regular,
                    guess = kindGuess,
                    onDismiss = { settingUp = false },
                    onSave = { kind, frequency, day, addByItself, amountChanges ->
                        settingUp = false
                        viewModel.makeRegular(kind, frequency, day, addByItself, amountChanges)
                    },
                )
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

private fun sourceOf(source: RecordSource, scheduled: Boolean): String = when (source) {
    RecordSource.STATEMENT -> "A bank statement"
    RecordSource.LIVE -> "A bank alert — the statement will replace it when it comes in"
    RecordSource.SPREADSHEET -> "Your spreadsheet"
    RecordSource.MANUAL -> if (scheduled) "Added by itself on its day — the statement will confirm it" else "Typed in"
    RecordSource.UNKNOWN -> "Added before the app kept track"
}
