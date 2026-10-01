package com.rhys.financetracker.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PayeeGroup
import com.rhys.financetracker.data.repository.PayeeRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.colorFromHex
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How far back to add up. */
enum class Period(val label: String) {
    THIS_MONTH("This month"),
    THREE_MONTHS("3 months"),
    THIS_YEAR("This year"),
    ALL("All time"),
    ;

    fun range(today: LocalDate = DateUtils.today()): Pair<LocalDate, LocalDate> = when (this) {
        THIS_MONTH -> today.withDayOfMonth(1) to today
        THREE_MONTHS -> today.minusMonths(3) to today
        THIS_YEAR -> today.withDayOfYear(1) to today
        ALL -> LocalDate.of(1900, 1, 1) to today.plusYears(1)
    }
}

/**
 * Shared by both pages: payments grouped by payee over a period, with a way
 * to file a whole payee under a category in one go.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PayeeListViewModel(
    private val payees: PayeeRepository,
    categoryRepository: CategoryRepository,
    start: Period,
) : ViewModel() {

    val period = MutableStateFlow(start)
    val message = MutableStateFlow<String?>(null)

    val categories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeByKind(CategoryKind.EXPENSE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<PayeeGroup>?> = period.flatMapLatest { chosen ->
        val (from, to) = chosen.range()
        observe(from, to)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    protected abstract fun observe(from: LocalDate, to: LocalDate): kotlinx.coroutines.flow.Flow<List<PayeeGroup>>

    fun file(group: PayeeGroup, category: CategoryEntity) {
        viewModelScope.launch {
            payees.file(group.ids, category.id)
            message.value = "${group.name}: ${group.count} " +
                (if (group.count == 1) "payment" else "payments") +
                " filed under ${category.name}. New statements will do the same."
        }
    }
}

@HiltViewModel
class SortSpendingViewModel @Inject constructor(
    private val payeeRepository: PayeeRepository,
    categoryRepository: CategoryRepository,
) : PayeeListViewModel(payeeRepository, categoryRepository, Period.THREE_MONTHS) {
    override fun observe(from: LocalDate, to: LocalDate) = payeeRepository.observeUnsorted(from, to)
}

/**
 * Spending that is not sorted yet, grouped by who it went to. Tap a payee
 * and pick a category: every payment to them is filed, and every future
 * statement files them the same way.
 */
@Composable
fun SortSpendingScreen(onBack: () -> Unit, viewModel: SortSpendingViewModel = hiltViewModel()) {
    PayeeListScreen(
        title = "Sort spending",
        intro = "Payments with no category, or only \"Card spending\", grouped by who they went " +
            "to. Tap one to say what it is — shops, websites, outings, bills — and every payment " +
            "to them is sorted at once. New statements will sort them the same way.",
        emptyTitle = "Everything is sorted",
        emptyText = "No unsorted spending in this period.",
        viewModel = viewModel,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayeeListScreen(
    title: String,
    intro: String,
    emptyTitle: String,
    emptyText: String,
    viewModel: PayeeListViewModel,
    onBack: () -> Unit,
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var filing by remember { mutableStateOf<PayeeGroup?>(null) }

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
                title = { Text(title) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Period.entries.forEach { option ->
                        FilterChip(
                            selected = period == option,
                            onClick = { viewModel.period.value = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            val list = groups
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = androidx.compose.material.icons.Icons.Outlined.CheckCircle,
                        title = emptyTitle,
                        message = emptyText,
                    )
                }
            }
            if (!list.isNullOrEmpty()) {
                item {
                    Text(
                        text = "${list.size} ${if (list.size == 1) "payee" else "payees"} · " +
                            "${Money.format(list.sumOf { it.totalMinor })} in all",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(list, key = { it.name }) { group ->
                    PayeeRow(
                        group = group,
                        isOpen = open == group.name,
                        onToggle = { open = if (open == group.name) null else group.name },
                        onFile = { filing = group },
                    )
                }
            }
        }
    }

    filing?.let { group ->
        AlertDialog(
            onDismissRequest = { filing = null },
            title = { Text("What is ${group.name}?") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(categories, key = { it.id }) { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.file(group, category)
                                    filing = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ColorDot(colorFromHex(category.colorHex))
                            Spacer(Modifier.padding(start = 10.dp))
                            Text(category.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { filing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PayeeRow(
    group: PayeeGroup,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onFile: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${group.count} ${if (group.count == 1) "payment" else "payments"}" +
                        (group.lastDate?.let { " · last ${DateUtils.formatShort(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Money.format(group.totalMinor), style = MaterialTheme.typography.titleMedium)
        }
        if (isOpen) {
            Spacer(Modifier.height(6.dp))
            group.entries.forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        text = "${DateUtils.formatShort(entry.date)}  ${entry.description}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(Money.format(entry.amountMinor), style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = onFile) { Text("File all ${group.count} under a category") }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}
