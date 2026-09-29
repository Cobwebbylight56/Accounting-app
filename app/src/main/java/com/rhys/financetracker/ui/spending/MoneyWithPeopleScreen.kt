package com.rhys.financetracker.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.projection.PayeeEntry
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PayeeRepository
import com.rhys.financetracker.data.repository.PersonLedger
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Money sent to people and money they sent back, one month at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SentToPeopleViewModel @Inject constructor(
    private val payeeRepository: PayeeRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    val month = MutableStateFlow(DateUtils.currentYearMonth())
    val message = MutableStateFlow<String?>(null)

    val ledgers: StateFlow<List<PersonLedger>?> = month.flatMapLatest { chosen ->
        val range = DateUtils.monthRange(chosen)
        payeeRepository.observeMoneyWithPeople(range.start, range.endInclusive)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val expenseCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeByKind(CategoryKind.EXPENSE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeByKind(CategoryKind.INCOME)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun file(name: String, entries: List<PayeeEntry>, category: CategoryEntity) {
        viewModelScope.launch {
            payeeRepository.file(entries.map { it.id }, category.id)
            message.value = "$name: ${entries.size} " +
                (if (entries.size == 1) "entry" else "entries") + " filed under ${category.name}."
        }
    }
}

/**
 * For each person, what was sent to them and what they sent back this
 * month, side by side, with who is up. Tap a person to see every payment.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentToPeopleScreen(onBack: () -> Unit, viewModel: SentToPeopleViewModel = hiltViewModel()) {
    val ledgers by viewModel.ledgers.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val expenseCategories by viewModel.expenseCategories.collectAsStateWithLifecycle()
    val incomeCategories by viewModel.incomeCategories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var filing by remember { mutableStateOf<Filing?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.message.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Money with people") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                MonthPicker(month = month, onPrevious = viewModel::previousMonth, onNext = viewModel::nextMonth)
            }
            val list = ledgers
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "Nothing with people",
                        message = "No money sent to or from people in ${DateUtils.formatMonth(month)}.",
                    )
                }
            }
            if (!list.isNullOrEmpty()) {
                item { Totals(list) }
                items(list, key = { it.name }) { ledger ->
                    LedgerCard(
                        ledger = ledger,
                        isOpen = open == ledger.name,
                        onToggle = { open = if (open == ledger.name) null else ledger.name },
                        onFileSent = { filing = Filing(ledger.name, ledger.sent, isMoneyIn = false) },
                        onFileReceived = { filing = Filing(ledger.name, ledger.received, isMoneyIn = true) },
                    )
                }
                item {
                    Text(
                        text = "Money in is counted as from a person when it reads like a transfer " +
                            "from someone, or comes from a name money was sent to. If one here is " +
                            "not a person — a wage, a refund — file it elsewhere and it drops off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    filing?.let { chosen ->
        val categories = if (chosen.isMoneyIn) incomeCategories else expenseCategories
        AlertDialog(
            onDismissRequest = { filing = null },
            title = {
                Text(if (chosen.isMoneyIn) "What is money from ${chosen.name}?" else "What is money to ${chosen.name}?")
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(categories, key = { it.id }) { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.file(chosen.name, chosen.entries, category)
                                    filing = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ColorDot(colorFromHex(category.colorHex))
                            Spacer(Modifier.width(10.dp))
                            Text(category.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { filing = null }) { Text("Cancel") } },
        )
    }
}

/** One side of one person's money, about to be filed elsewhere. */
private data class Filing(val name: String, val entries: List<PayeeEntry>, val isMoneyIn: Boolean)

@Composable
private fun MonthPicker(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
        }
        Text(
            text = DateUtils.formatMonth(month),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
        }
    }
}

@Composable
private fun Totals(list: List<PersonLedger>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Figure("You sent", list.sumOf { it.sentMinor }, FinanceTheme.colors.expense, Modifier.weight(1f))
            Figure("Sent to you", list.sumOf { it.receivedMinor }, FinanceTheme.colors.income, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(label: String, minor: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.format(minor), style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LedgerCard(
    ledger: PersonLedger,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onFileSent: () -> Unit,
    onFileReceived: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ledger.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = when {
                        ledger.netMinor > 0 -> "They sent ${Money.format(ledger.netMinor)} more"
                        ledger.netMinor < 0 -> "You sent ${Money.format(-ledger.netMinor)} more"
                        else -> "Even"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Side(
                    label = "You sent",
                    totalMinor = ledger.sentMinor,
                    entries = ledger.sent,
                    color = FinanceTheme.colors.expense,
                    isOpen = isOpen,
                    modifier = Modifier.weight(1f),
                )
                Side(
                    label = "They sent you",
                    totalMinor = ledger.receivedMinor,
                    entries = ledger.received,
                    color = FinanceTheme.colors.income,
                    isOpen = isOpen,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isOpen) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (ledger.sent.isNotEmpty()) {
                        TextButton(onClick = onFileSent, modifier = Modifier.weight(1f)) { Text("Not a person? File sent") }
                    }
                    if (ledger.received.isNotEmpty()) {
                        TextButton(onClick = onFileReceived, modifier = Modifier.weight(1f)) { Text("File received") }
                    }
                }
            }
        }
    }
}

/** One column: a total, and each payment when the card is open. */
@Composable
private fun Side(
    label: String,
    totalMinor: Long,
    entries: List<PayeeEntry>,
    color: Color,
    isOpen: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Money.format(totalMinor), style = MaterialTheme.typography.titleMedium, color = color)
        Text(
            text = "${entries.size} ${if (entries.size == 1) "payment" else "payments"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isOpen) {
            Spacer(Modifier.height(6.dp))
            entries.sortedBy { it.date }.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = DateUtils.formatShort(entry.date),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Money.format(entry.amountMinor), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
