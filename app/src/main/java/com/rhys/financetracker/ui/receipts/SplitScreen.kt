package com.rhys.financetracker.ui.receipts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
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
import com.rhys.financetracker.data.receipts.ItemCategoriser
import com.rhys.financetracker.data.receipts.SplitRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.CategoryPicker
import com.rhys.financetracker.ui.components.ColorDot
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

/** The parts in one category, with where each sits in the list being edited. */
private data class RowGroup(val id: Long?, val category: CategoryEntity?, val rows: List<IndexedValue<PartRow>>) {
    val totalMinor: Long get() = rows.sumOf { it.value.amountMinor }
}

/**
 * The parts gathered by category in breakdown order — groceries, food,
 * drinks, clothes, household… — with those left in the payment's own
 * category last.
 */
private fun groupRows(rows: List<PartRow>, categories: List<CategoryEntity>): List<RowGroup> =
    rows.withIndex()
        .groupBy { it.value.categoryId }
        .map { (id, list) -> RowGroup(id, categories.firstOrNull { it.id == id }, list) }
        .sortedWith(
            compareBy<RowGroup> { if (it.category == null) Int.MAX_VALUE else ItemCategoriser.orderOf(it.category.name) }
                .thenByDescending { it.totalMinor },
        )

/**
 * Breaking one payment down by what was bought: groceries, food, drinks,
 * clothes, household and the rest, each with its items under it, filled in
 * from the receipt where there is one. An item moves to another group in two
 * taps; anything in no group stays in the payment's own category.
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
    var editing by remember { mutableStateOf<Int?>(null) }

    val payment = state.payment
    val own = categories.firstOrNull { it.id == payment?.categoryId }?.name ?: "its own category"
    val groups = groupRows(state.rows, categories)
    // The groups to offer when moving an item, as they are in this app's categories.
    val groupCategories = ItemCategoriser.GROUPS.mapNotNull { group ->
        categories.firstOrNull { it.name.equals(group.category, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Break down by category") },
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
                ) { Text(if (state.rows.isEmpty()) "Don't break it down" else "Save the breakdown") }
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
            if (state.rows.isNotEmpty()) item { Summary(state, groups, own) }
            if (state.fromReceipt) {
                item {
                    Text(
                        "Sorted from the receipt. Tap ⇄ by anything in the wrong group to move it — " +
                            "the app remembers it next time. Anything under Other stays in $own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            groups.forEach { group ->
                item(key = "group-${group.id ?: "own"}") {
                    GroupCard(
                        group = group,
                        own = own,
                        moveTo = groupCategories,
                        onMove = { index, id -> viewModel.setCategory(index, id) },
                        onEdit = { editing = it },
                        onRemove = viewModel::remove,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.add()
                        editing = state.rows.size
                    }) { Text("Add a part") }
                    if (state.receiptItemCount > 0) {
                        OutlinedButton(onClick = viewModel::fillFromReceipt) {
                            Text("Sort the receipt again (${state.receiptItemCount})")
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
            item { TextButton(onClick = viewModel::unsplit) { Text("Don't break this payment down") } }
        }
    }

    val editIndex = editing?.takeIf { it in state.rows.indices }
    if (editIndex != null) {
        EditPartDialog(
            row = state.rows[editIndex],
            categories = categories,
            own = own,
            onLabel = { viewModel.setLabel(editIndex, it) },
            onAmount = { viewModel.setAmount(editIndex, it) },
            onCategory = { viewModel.setCategory(editIndex, it) },
            onRemove = {
                editing = null
                viewModel.remove(editIndex)
            },
            onDone = { editing = null },
        )
    }
}

/** What went on each group: a bar in the groups' colours, then each group's total. */
@Composable
private fun Summary(state: SplitState, groups: List<RowGroup>, own: String) {
    val named = groups.filter { it.category != null && it.totalMinor > 0L }
    val otherMinor = groups.filter { it.category == null }.sumOf { it.totalMinor } + state.restMinor.coerceAtLeast(0L)
    val otherColour = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val total = (named.sumOf { it.totalMinor } + otherMinor).coerceAtLeast(1L)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp)),
            ) {
                named.forEach { group ->
                    Box(
                        Modifier
                            .weight(group.totalMinor.toFloat() / total)
                            .fillMaxHeight()
                            .background(colorFromHex(group.category?.colorHex)),
                    )
                }
                if (otherMinor > 0L) {
                    Box(Modifier.weight(otherMinor.toFloat() / total).fillMaxHeight().background(otherColour))
                }
            }
            named.forEach { group ->
                SummaryLine(
                    colour = colorFromHex(group.category?.colorHex),
                    name = group.category?.name.orEmpty(),
                    count = group.rows.size,
                    amountMinor = group.totalMinor,
                )
            }
            if (otherMinor > 0L) {
                SummaryLine(colour = otherColour, name = "Other — stays in $own", count = null, amountMinor = otherMinor)
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
private fun SummaryLine(colour: androidx.compose.ui.graphics.Color, name: String, count: Int?, amountMinor: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        if (count != null) {
            Text(
                "  · $count ${if (count == 1) "item" else "items"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(Money.format(amountMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** One group: its name, total and what is in it, each item one tap from another group. */
@Composable
private fun GroupCard(
    group: RowGroup,
    own: String,
    moveTo: List<CategoryEntity>,
    onMove: (index: Int, categoryId: Long?) -> Unit,
    onEdit: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
) {
    var open by rememberSaveable(group.category?.id) { mutableStateOf(true) }
    val about = group.category?.let { category ->
        ItemCategoriser.GROUPS.firstOrNull { it.category.equals(category.name, ignoreCase = true) }?.about
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(colorFromHex(group.category?.colorHex), size = 12.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        group.category?.name ?: "Other",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (group.category == null) "Stays in $own" else about ?: "${group.rows.size} items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(Money.format(group.totalMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Hide the items" else "Show the items",
                )
            }
            AnimatedVisibility(visible = open) {
                Column {
                    group.rows.forEach { (index, row) ->
                        ItemLine(
                            row = row,
                            current = group.category,
                            own = own,
                            moveTo = moveTo,
                            onMove = { id -> onMove(index, id) },
                            onEdit = { onEdit(index) },
                            onRemove = { onRemove(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemLine(
    row: PartRow,
    current: CategoryEntity?,
    own: String,
    moveTo: List<CategoryEntity>,
    onMove: (Long?) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(start = 36.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.label.ifBlank { "(no name)" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(Money.format(row.amountMinor), style = MaterialTheme.typography.bodyMedium)
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = "Move ${row.label} to another group")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Text(
                    "Move to",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                moveTo.filter { it.id != current?.id }.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        leadingIcon = { ColorDot(colorFromHex(category.colorHex), size = 10.dp) },
                        onClick = {
                            menu = false
                            onMove(category.id)
                        },
                    )
                }
                if (current != null) {
                    DropdownMenuItem(
                        text = { Text("Other (stays in $own)") },
                        onClick = {
                            menu = false
                            onMove(null)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Another category, or change it…") },
                    onClick = {
                        menu = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/** Changing one item: its name, its amount, or any category at all. */
@Composable
private fun EditPartDialog(
    row: PartRow,
    categories: List<CategoryEntity>,
    own: String,
    onLabel: (String) -> Unit,
    onAmount: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelledTextField(label = "What", value = row.label, onValueChange = onLabel)
                AmountField(label = "Amount", value = row.amountText, onValueChange = onAmount)
                CategoryPicker(
                    label = "Category",
                    categories = categories,
                    selectedId = row.categoryId,
                    onSelect = { onCategory(it?.id) },
                    placeholder = "Other — stays in $own",
                    noneLabel = "Other — stays in $own",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
        dismissButton = { TextButton(onClick = onRemove) { Text("Remove") } },
    )
}

// ----------------------------------------------------- in lists of payments

@HiltViewModel
class SplitPartsViewModel @Inject constructor(private val splits: SplitRepository) : ViewModel() {
    fun partsFor(transactionId: Long): Flow<List<SplitPart>> = splits.observeParts(transactionId)
}

/** A payment's parts in one category, for showing its breakdown. */
data class PartGroup(val name: String, val colorHex: String?, val parts: List<SplitPart>) {
    val totalMinor: Long get() = parts.sumOf { it.amountMinor }
}

/**
 * [parts] gathered by category in breakdown order — groceries, food,
 * drinks… — those in the payment's own category ([ownName]) together.
 */
fun groupParts(parts: List<SplitPart>, ownName: String?, ownColor: String?): List<PartGroup> =
    parts.groupBy { it.categoryName ?: ownName ?: "Other" }
        .map { (name, list) -> PartGroup(name, list.firstNotNullOfOrNull { it.categoryColor } ?: ownColor, list) }
        .sortedWith(compareBy<PartGroup> { ItemCategoriser.orderOf(it.name) }.thenByDescending { it.totalMinor })

/** Each group's total on a line; tap one to see what is in it. */
@Composable
fun PartGroupsList(groups: List<PartGroup>, small: Boolean = false) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(if (small) 2.dp else 4.dp)) {
        groups.forEach { group ->
            var open by rememberSaveable(group.name) { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .heightIn(min = if (small) 28.dp else 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ColorDot(colorFromHex(group.colorHex), size = if (small) 8.dp else 10.dp)
                Spacer(Modifier.width(8.dp))
                Text(group.name, style = style, fontWeight = FontWeight.SemiBold)
                Text(
                    "  · ${group.parts.size} ${if (group.parts.size == 1) "item" else "items"}",
                    style = style,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(Money.format(group.totalMinor), style = style)
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (open) "Hide what is in ${group.name}" else "Show what is in ${group.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.padding(start = 18.dp, bottom = 4.dp)) {
                    group.parts.forEach { part ->
                        Row {
                            Text(
                                part.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                Money.format(part.amountMinor),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "Breakdown · 12 items" line under a payment in a list; tap it to drop
 * down what went on each category.
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
                "Breakdown · $partCount ${if (partCount == 1) "item" else "items"}",
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
            Column(Modifier.padding(start = 4.dp, top = 2.dp)) {
                PartGroupsList(groupParts(parts, ownCategory, null), small = true)
            }
        }
    }
}
