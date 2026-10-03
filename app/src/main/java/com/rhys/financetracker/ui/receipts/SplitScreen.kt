package com.rhys.financetracker.ui.receipts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.receipts.SplitRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One part being edited. */
data class PartRow(val key: Int, val label: String, val amountText: String, val categoryId: Long?) {
    val amountMinor: Long get() = Money.parseOrNull(amountText)?.coerceAtLeast(0L) ?: 0L
}

data class SplitState(
    val isLoading: Boolean = true,
    val payment: TransactionEntity? = null,
    val rows: List<PartRow> = emptyList(),
    val receiptItemCount: Int = 0,
    /** True when the parts were just filled in from the receipt, so they need checking. */
    val fromReceipt: Boolean = false,
    val message: String? = null,
    val saved: Boolean = false,
) {
    val totalMinor: Long get() = payment?.amountMinor ?: 0L
    val partsMinor: Long get() = rows.sumOf { it.amountMinor }

    /** What is left for the payment's own category; below zero when the parts come to too much. */
    val restMinor: Long get() = totalMinor - partsMinor
}

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val splits: SplitRepository,
    private val transactions: TransactionRepository,
    categoryRepository: CategoryRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transactionId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val _state = MutableStateFlow(SplitState())
    val state: StateFlow<SplitState> = _state

    val categories: StateFlow<List<CategoryEntity>> = categoryRepository.observeByKind(CategoryKind.EXPENSE)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var nextKey = 0

    init {
        viewModelScope.launch {
            val payment = transactions.get(transactionId)
            val items = splits.receiptItems(transactionId)
            val existing = splits.drafts(transactionId)
            // Not split yet but there is a receipt: start from what is on it.
            val start = existing.ifEmpty { splits.fromReceipt(transactionId) }
            _state.value = SplitState(
                isLoading = false,
                payment = payment,
                rows = start.map { it.toRow() },
                receiptItemCount = items.size,
                fromReceipt = existing.isEmpty() && start.isNotEmpty(),
            )
        }
    }

    private fun SplitRepository.Draft.toRow() =
        PartRow(nextKey++, label, Money.formatPlain(amountMinor), categoryId)

    private fun edit(index: Int, change: (PartRow) -> PartRow) = _state.update { current ->
        current.copy(rows = current.rows.mapIndexed { i, row -> if (i == index) change(row) else row }, message = null)
    }

    fun setLabel(index: Int, text: String) = edit(index) { it.copy(label = text) }
    fun setAmount(index: Int, text: String) = edit(index) { it.copy(amountText = text) }
    fun setCategory(index: Int, id: Long?) = edit(index) { it.copy(categoryId = id) }

    fun add() = _state.update { it.copy(rows = it.rows + PartRow(nextKey++, "", "", null)) }

    fun remove(index: Int) = _state.update { current ->
        current.copy(rows = current.rows.filterIndexed { i, _ -> i != index })
    }

    fun fillFromReceipt() {
        viewModelScope.launch {
            val drafts = splits.fromReceipt(transactionId)
            _state.update { it.copy(rows = drafts.map { d -> d.toRow() }, fromReceipt = true) }
        }
    }

    fun save() {
        val current = _state.value
        viewModelScope.launch {
            val error = splits.save(
                transactionId,
                current.rows.map { SplitRepository.Draft(it.label, it.amountMinor, it.categoryId) },
                learn = true,
            )
            _state.update { if (error == null) it.copy(saved = true) else it.copy(message = error) }
        }
    }

    fun unsplit() {
        viewModelScope.launch {
            splits.clear(transactionId)
            _state.update { it.copy(saved = true) }
        }
    }
}

/**
 * Splitting one payment across categories: each item or part with its own
 * category and amount, filled in from the receipt where there is one, the
 * rest staying in the payment's own category.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(
    onBack: () -> Unit,
    viewModel: SplitViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }

    val payment = state.payment
    val own = categories.firstOrNull { it.id == payment?.categoryId }?.name ?: "its own category"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split by category") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.padding(16.dp)) {
                state.message?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isLoading && state.restMinor >= 0L,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(if (state.rows.isEmpty()) "Don't split" else "Save the split") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (payment != null) {
                item {
                    Column {
                        Text(payment.description, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${Money.format(payment.amountMinor)} · ${DateUtils.format(payment.date)} · $own",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { ByCategory(state, categories, own) }
            if (state.fromReceipt) {
                item {
                    Text(
                        "Filled in from the receipt — check each category. Anything left on \"same as the " +
                            "payment\" stays in $own. Your choices are remembered for next time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                PartCard(
                    row = row,
                    categories = categories,
                    own = own,
                    onLabel = { viewModel.setLabel(index, it) },
                    onAmount = { viewModel.setAmount(index, it) },
                    onCategory = { viewModel.setCategory(index, it) },
                    onRemove = { viewModel.remove(index) },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::add) { Text("Add a part") }
                    if (state.receiptItemCount > 0) {
                        OutlinedButton(onClick = viewModel::fillFromReceipt) {
                            Text("Fill from the receipt (${state.receiptItemCount})")
                        }
                    }
                }
            }
            if (state.rows.isEmpty() && state.receiptItemCount == 0) {
                item {
                    Text(
                        "Add a part for anything that belongs in another category — the T-shirt in a food " +
                            "shop, the snacks with the fuel. Add a receipt to the payment to fill this in for you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { TextButton(onClick = viewModel::unsplit) { Text("Don't split this payment") } }
        }
    }
}

/** What the split comes to in each category, the rest in the payment's own. */
@Composable
private fun ByCategory(state: SplitState, categories: List<CategoryEntity>, own: String) {
    val totals = state.rows.filter { it.categoryId != null && it.amountMinor > 0L }
        .groupBy { it.categoryId }
        .map { (id, rows) -> categories.firstOrNull { it.id == id } to rows.sumOf { it.amountMinor } }
        .sortedByDescending { it.second }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("By category", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            totals.forEach { (category, amount) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(colorFromHex(category?.colorHex), size = 10.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(category?.name ?: "Other", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Money.format(amount), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row {
                Text(
                    "$own (the rest)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Money.format(state.restMinor.coerceAtLeast(0L)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.restMinor < 0L) {
                Text(
                    "The parts come to ${Money.format(-state.restMinor)} more than the payment — take some off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PartCard(
    row: PartRow,
    categories: List<CategoryEntity>,
    own: String,
    onLabel: (String) -> Unit,
    onAmount: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelledTextField(label = "What", value = row.label, onValueChange = onLabel, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, contentDescription = "Remove this part") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(end = 8.dp)) {
                AmountField(label = "Amount", value = row.amountText, onValueChange = onAmount, modifier = Modifier.weight(0.4f))
                DropdownField(
                    label = "Category",
                    options = listOf<CategoryEntity?>(null) + categories,
                    selected = categories.firstOrNull { it.id == row.categoryId },
                    onSelect = { onCategory(it?.id) },
                    optionLabel = { it?.name ?: "Same as the payment ($own)" },
                    placeholder = "Same as the payment",
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }
}

// ----------------------------------------------------- in lists of payments

@HiltViewModel
class SplitPartsViewModel @Inject constructor(private val splits: SplitRepository) : ViewModel() {
    fun partsFor(transactionId: Long): Flow<List<SplitPart>> = splits.observeParts(transactionId)
}

/**
 * The "Split · 3 parts" line under a payment in a list; tap it to drop down
 * the breakdown.
 */
@Composable
fun SplitDropDown(
    transactionId: Long,
    partCount: Int,
    ownCategory: String?,
    viewModel: SplitPartsViewModel = hiltViewModel(),
) {
    var open by rememberSaveable(transactionId) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .clickable { open = !open }
                .heightIn(min = 40.dp)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Split · $partCount ${if (partCount == 1) "part" else "parts"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (open) "Hide the breakdown" else "Show the breakdown",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        AnimatedVisibility(visible = open) {
            val parts by remember(transactionId) { viewModel.partsFor(transactionId) }.collectAsState(initial = emptyList())
            Column(Modifier.padding(start = 4.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                parts.forEach { part ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(colorFromHex(part.categoryColor), size = 8.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            part.label,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (part.categoryName ?: ownCategory ?: "") + "  " + Money.format(part.amountMinor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
