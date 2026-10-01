package com.rhys.financetracker.ui.spending

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.text.style.TextAlign
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.BreakdownView
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.LineSeries
import com.rhys.financetracker.ui.components.MarkerShape
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.TrendLineChart
import com.rhys.financetracker.ui.components.ViewSwitchButton
import com.rhys.financetracker.ui.components.rememberCardView
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
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
import com.rhys.financetracker.data.repository.PeopleMoney
import com.rhys.financetracker.data.repository.PersonLedger
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Looking at one month, or a whole year. */
enum class PeoplePeriod { MONTH, YEAR }

/** Money sent to people and money they sent back, a month or a year at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SentToPeopleViewModel @Inject constructor(
    private val payeeRepository: PayeeRepository,
    private val categoryRepository: CategoryRepository,
    private val transactionRepository: com.rhys.financetracker.data.repository.TransactionRepository,
) : ViewModel() {

    /** The month shown, or in a year view any month of the year shown. */
    val month = MutableStateFlow(DateUtils.currentYearMonth())
    val period = MutableStateFlow(PeoplePeriod.MONTH)
    val message = MutableStateFlow<String?>(null)

    /** Whose accounts; null for everybody's. */
    private val personIds = MutableStateFlow<Set<Long>?>(null)

    val money: StateFlow<PeopleMoney?> = combine(month, period, personIds) { chosen, length, ids ->
        Triple(chosen, length, ids)
    }.flatMapLatest { (chosen, length, ids) ->
        val (from, to) = when (length) {
            PeoplePeriod.MONTH -> DateUtils.monthRange(chosen).let { it.start to it.endInclusive }
            PeoplePeriod.YEAR -> java.time.LocalDate.of(chosen.year, 1, 1) to java.time.LocalDate.of(chosen.year, 12, 31)
        }
        payeeRepository.observeMoneyWithPeople(from, to, ids)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The whole year the shown month is in, for the graph — shown in both views. */
    val yearMoney: StateFlow<PeopleMoney?> = combine(month, personIds) { chosen, ids -> chosen.year to ids }
        .flatMapLatest { (year, ids) ->
            payeeRepository.observeMoneyWithPeople(
                java.time.LocalDate.of(year, 1, 1),
                java.time.LocalDate.of(year, 12, 31),
                ids,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPeriod(length: PeoplePeriod) {
        period.value = length
    }

    fun showPerson(personId: Long?) {
        personIds.value = personId?.let { setOf(it) }
    }

    /** Takes [ledger] off the people list, or puts it back; remembered for every month. */
    fun setIsPerson(ledger: PersonLedger, isPerson: Boolean) {
        viewModelScope.launch {
            payeeRepository.setIsPerson(ledger.key, isPerson)
            message.value = if (isPerson) {
                "${ledger.name} will always show as a person."
            } else {
                "${ledger.name} is no longer shown as a person."
            }
        }
    }

    val expenseCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeByKind(CategoryKind.EXPENSE)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val incomeCategories: StateFlow<List<CategoryEntity>> =
        categoryRepository.observeByKind(CategoryKind.INCOME)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun previous() {
        month.value = if (period.value == PeoplePeriod.YEAR) month.value.minusYears(1) else month.value.minusMonths(1)
    }

    fun next() {
        month.value = if (period.value == PeoplePeriod.YEAR) month.value.plusYears(1) else month.value.plusMonths(1)
    }

    /**
     * Swaps sides for [toMoneyIn] (recorded as sent, really received) and
     * [toMoneyOut] (the other way). Balances follow, as every figure is
     * worked out from the payments.
     */
    fun turnRound(name: String, toMoneyIn: List<PayeeEntry>, toMoneyOut: List<PayeeEntry>) {
        viewModelScope.launch {
            var turned = 0
            if (toMoneyIn.isNotEmpty()) {
                val category = categoryRepository.findOrCreate(MONEY_FROM_PEOPLE, CategoryKind.INCOME, "#1B9A94")
                turned += transactionRepository.turnRound(toMoneyIn.map { it.id }, com.rhys.financetracker.domain.model.TransactionType.INCOME, category.id)
            }
            if (toMoneyOut.isNotEmpty()) {
                val category = categoryRepository.findOrCreate(MONEY_TO_PEOPLE, CategoryKind.EXPENSE, "#C8402A")
                turned += transactionRepository.turnRound(toMoneyOut.map { it.id }, com.rhys.financetracker.domain.model.TransactionType.EXPENSE, category.id)
            }
            message.value = "$name: $turned " + (if (turned == 1) "payment" else "payments") + " turned round."
        }
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
 * For each person, what was sent to them and what they sent back, for a
 * month or a whole year, side by side, with who is up. The year has a graph
 * of both sides month by month. Tap a person to see every payment, and a
 * payment to change it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentToPeopleScreen(
    onBack: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
    /** Only this person's accounts; null for everybody's. */
    personId: Long? = null,
    viewModel: SentToPeopleViewModel = hiltViewModel(),
) {
    LaunchedEffect(personId) { viewModel.showPerson(personId) }
    val money by viewModel.money.collectAsStateWithLifecycle()
    val month by viewModel.month.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val yearMoney by viewModel.yearMoney.collectAsStateWithLifecycle()
    /** Whose year the graph shows, by key; null for everyone together. */
    var graphed by rememberSaveable { mutableStateOf<String?>(null) }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val expenseCategories by viewModel.expenseCategories.collectAsStateWithLifecycle()
    val incomeCategories by viewModel.incomeCategories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var filing by remember { mutableStateOf<Filing?>(null) }
    var turning by remember { mutableStateOf<PersonLedger?>(null) }
    var showNotPeople by rememberSaveable { mutableStateOf(false) }

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
                PeriodSwitch(period = period, onPeriod = viewModel::setPeriod)
            }
            item {
                PeriodPicker(
                    label = if (period == PeoplePeriod.YEAR) month.year.toString() else DateUtils.formatMonth(month),
                    isYear = period == PeoplePeriod.YEAR,
                    onPrevious = viewModel::previous,
                    onNext = viewModel::next,
                )
            }
            val list = money?.people
            val periodName = if (period == PeoplePeriod.YEAR) month.year.toString() else DateUtils.formatMonth(month)
            if (list != null && list.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "Nothing with people",
                        message = "No money sent to or from people in $periodName.",
                    )
                }
            }
            if (!list.isNullOrEmpty()) {
                item { Totals(list) }
            }
            // The year's graph, in both views; in a month it picks that month out.
            val yearPeople = yearMoney?.people.orEmpty()
            if (yearPeople.isNotEmpty()) {
                item {
                    YearGraphCard(
                        year = month.year,
                        people = yearPeople,
                        graphed = graphed?.takeIf { key -> yearPeople.any { it.key == key } },
                        onGraph = { graphed = it },
                        highlightMonth = month.monthValue.takeIf { period == PeoplePeriod.MONTH },
                    )
                }
            }
            if (!list.isNullOrEmpty()) {
                items(list, key = { it.key }) { ledger ->
                    LedgerCard(
                        ledger = ledger,
                        isOpen = open == ledger.key,
                        onToggle = { open = if (open == ledger.key) null else ledger.key },
                        onOpenEntry = onOpenTransaction,
                        onFileSent = { filing = Filing(ledger.name, ledger.sent, isMoneyIn = false) },
                        onFileReceived = { filing = Filing(ledger.name, ledger.received, isMoneyIn = true) },
                        onNotAPerson = { viewModel.setIsPerson(ledger, isPerson = false) },
                        onWrongWayRound = { turning = ledger },
                    )
                }
            }
            val notPeople = money?.notPeople.orEmpty()
            if (notPeople.isNotEmpty()) {
                item {
                    TextButton(onClick = { showNotPeople = !showNotPeople }) {
                        Text(
                            (if (showNotPeople) "Hide" else "Show") +
                                " ${notPeople.size} left out as not people (PayPal, shops…)",
                        )
                    }
                }
                if (showNotPeople) {
                    items(notPeople, key = { "not-" + it.key }) { ledger ->
                        NotAPersonRow(ledger = ledger, onKeep = { viewModel.setIsPerson(ledger, isPerson = true) })
                    }
                }
            }
            item {
                Text(
                    text = "Only people's names are shown — PayPal, shops and services are left " +
                        "out. Tap a person to see each payment, and tap a payment to change it. " +
                        "\"Not a person\" leaves one out; keep one the app left out from the list " +
                        "above. Your choice is remembered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    turning?.let { ledger ->
        TurnRoundDialog(
            ledger = ledger,
            onDismiss = { turning = null },
            onConfirm = { toIn, toOut ->
                viewModel.turnRound(ledger.name, toIn, toOut)
                turning = null
            },
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodSwitch(period: PeoplePeriod, onPeriod: (PeoplePeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        PeoplePeriod.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == period,
                onClick = { onPeriod(option) },
                shape = SegmentedButtonDefaults.itemShape(index, PeoplePeriod.entries.size),
            ) {
                Text(if (option == PeoplePeriod.MONTH) "Month" else "Whole year")
            }
        }
    }
}

@Composable
private fun PeriodPicker(label: String, isYear: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    val unit = if (isYear) "year" else "month"
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous $unit")
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next $unit")
        }
    }
}

@Composable
private fun Totals(list: List<PersonLedger>) {
    val sent = list.sumOf { it.sentMinor }
    val received = list.sumOf { it.receivedMinor }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Figure("You sent", sent, FinanceTheme.colors.expense, Modifier.weight(1f))
                Figure("Sent to you", received, FinanceTheme.colors.income, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = whoIsUp(received - sent),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** "You sent £20.00 more", from what came back less what went out. */
private fun whoIsUp(netMinor: Long): String = when {
    netMinor > 0 -> "They sent ${Money.format(netMinor)} more"
    netMinor < 0 -> "You sent ${Money.format(-netMinor)} more"
    else -> "Even"
}

@Composable
private fun Figure(label: String, minor: Long, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            com.rhys.financetracker.ui.components.animatedMoney(minor),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LedgerCard(
    ledger: PersonLedger,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onFileSent: () -> Unit,
    onFileReceived: () -> Unit,
    onNotAPerson: () -> Unit,
    onWrongWayRound: () -> Unit,
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
                    text = whoIsUp(ledger.netMinor),
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
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier.weight(1f),
                )
                Side(
                    label = "They sent you",
                    totalMinor = ledger.receivedMinor,
                    entries = ledger.received,
                    color = FinanceTheme.colors.income,
                    isOpen = isOpen,
                    onOpenEntry = onOpenEntry,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isOpen) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    if (ledger.sent.isNotEmpty()) {
                        TextButton(onClick = onFileSent, modifier = Modifier.weight(1f)) { Text("File sent") }
                    }
                    if (ledger.received.isNotEmpty()) {
                        TextButton(onClick = onFileReceived, modifier = Modifier.weight(1f)) { Text("File received") }
                    }
                }
                TextButton(onClick = onWrongWayRound) { Text("Some of these the wrong way round?") }
                TextButton(onClick = onNotAPerson) { Text("Not a person — leave out") }
            }
        }
    }
}

/** One column: a total, and each payment when the card is open — tap one to change it. */
@Composable
private fun Side(
    label: String,
    totalMinor: Long,
    entries: List<PayeeEntry>,
    color: Color,
    isOpen: Boolean,
    onOpenEntry: (Long) -> Unit,
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clickable { onOpenEntry(entry.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = DateUtils.formatShort(entry.date),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(Money.format(entry.amountMinor), style = MaterialTheme.typography.bodySmall)
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Change this payment",
                        modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A payee left out as not a person, with a way to bring them back. */
@Composable
private fun NotAPersonRow(ledger: PersonLedger, onKeep: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ledger.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Sent ${Money.format(ledger.sentMinor)} · got ${Money.format(ledger.receivedMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onKeep) { Text("It's a person") }
    }
}

/**
 * The people money was sent to this month, side by side with what they
 * sent back — for Home and a person's page. Shows the biggest few.
 */
@Composable
fun PeopleMoneyCard(
    money: PeopleMoney?,
    monthLabel: String,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** The whole year, for a small graph and the year's totals under the month. */
    year: PeopleMoney? = null,
    yearNumber: Int? = null,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("People", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Sent and received in $monthLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSeeAll) { Text("See all") }
            }
            val people = money?.people.orEmpty()
            if (people.isEmpty()) {
                Text(
                    text = "Nothing sent to or from people in $monthLabel yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (year != null && yearNumber != null) PeopleYearStrip(year, yearNumber)
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                Text(
                    "You sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    "They sent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(88.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            people.take(HOME_PEOPLE).forEach { ledger ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = ledger.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = Money.format(ledger.sentMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.expense,
                        modifier = Modifier.width(88.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                    Text(
                        text = Money.format(ledger.receivedMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FinanceTheme.colors.income,
                        modifier = Modifier.width(88.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
            if (people.size > HOME_PEOPLE) {
                Text(
                    text = "and ${people.size - HOME_PEOPLE} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text("Total", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    Money.format(money?.sentMinor ?: 0L),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(88.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
                Text(
                    Money.format(money?.receivedMinor ?: 0L),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(88.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            if (year != null && yearNumber != null) PeopleYearStrip(year, yearNumber)
        }
    }
}

/**
 * The year so far under the month: a small graph of what you sent and what
 * came back, month by month, and who is up over the year.
 */
@Composable
private fun PeopleYearStrip(year: PeopleMoney, yearNumber: Int) {
    if (year.people.isEmpty()) return
    val now = DateUtils.currentYearMonth()
    val months = (if (yearNumber == now.year) now.monthValue else 12).coerceAtLeast(2)
    val sent = LongArray(months)
    val received = LongArray(months)
    year.people.forEach { ledger ->
        ledger.sent.forEach { if (it.date.year == yearNumber && it.date.monthValue <= months) sent[it.date.monthValue - 1] += it.amountMinor }
        ledger.received.forEach { if (it.date.year == yearNumber && it.date.monthValue <= months) received[it.date.monthValue - 1] += it.amountMinor }
    }
    val colors = FinanceTheme.colors
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Text("So far in $yearNumber", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    TrendLineChart(
        labels = (1..months).map { DateUtils.monthNameShort(it) },
        series = listOf(
            LineSeries("You sent", sent.toList(), colors.chartOut),
            LineSeries("They sent you", received.toList(), colors.chartIn, dashed = true, marker = MarkerShape.SQUARE),
        ),
        height = 150.dp,
        surface = androidx.compose.material3.CardDefaults.cardColors().containerColor,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "You sent ${Money.format(year.sentMinor)} · they sent ${Money.format(year.receivedMinor)} — " +
            whoIsUp(year.receivedMinor - year.sentMinor).replaceFirstChar { it.lowercase() },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** How many people the Home card lists before "See all". */
private const val HOME_PEOPLE = 5

/** The ways the year graph can be drawn. */
private val YEAR_VIEWS = listOf(BreakdownView.LINE, BreakdownView.RUNNING, BreakdownView.BARS, BreakdownView.LIST)

/**
 * The year month by month: what you sent and what came back, for everyone
 * together or one person. Four ways to see it, from the corner button: two
 * lines, the running balance between you, bars, or a plain list.
 */
@Composable
private fun YearGraphCard(
    year: Int,
    people: List<PersonLedger>,
    graphed: String?,
    onGraph: (String?) -> Unit,
    /** The month being looked at, 1 to 12, to pick out on the graph. */
    highlightMonth: Int? = null,
) {
    val (view, setView) = rememberCardView("people_year", BreakdownView.LINE)
    val picked = highlightMonth?.let { it - 1 }
    val now = DateUtils.currentYearMonth()
    // The current year stops at this month, so the lines do not fall to
    // nothing across months that have not happened yet.
    val months = (if (year == now.year) now.monthValue else 12).coerceAtLeast(2)
    val shown = people.filter { graphed == null || it.key == graphed }
    val sent = LongArray(months)
    val received = LongArray(months)
    shown.forEach { ledger ->
        ledger.sent.forEach { if (it.date.year == year && it.date.monthValue <= months) sent[it.date.monthValue - 1] += it.amountMinor }
        ledger.received.forEach { if (it.date.year == year && it.date.monthValue <= months) received[it.date.monthValue - 1] += it.amountMinor }
    }
    val labels = (1..months).map { DateUtils.monthNameShort(it) }
    val colors = FinanceTheme.colors
    val who = shown.singleOrNull()?.name ?: "everyone"

    SectionCard(
        title = "Through $year",
        subtitle = "Sent and received, $who",
        action = { ViewSwitchButton(view, YEAR_VIEWS, setView) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = graphed == null, onClick = { onGraph(null) }, label = { Text("Everyone") })
            people.forEach { ledger ->
                FilterChip(
                    selected = graphed == ledger.key,
                    onClick = { onGraph(ledger.key) },
                    label = { Text(ledger.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (view) {
            BreakdownView.RUNNING -> {
                var running = 0L
                val balance = labels.indices.map { i ->
                    running += received[i] - sent[i]
                    running
                }
                TrendLineChart(
                    labels = labels,
                    series = listOf(LineSeries("Who's up", balance, MaterialTheme.colorScheme.primary)),
                    selectedIndex = picked,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The running balance between you over the year. Above the zero line " +
                        "they have sent you more; below it, you have sent them more.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BreakdownView.BARS -> {
                GroupedBarChart(
                    groups = labels.indices.map { i ->
                        BarGroup(
                            label = labels[i],
                            bars = listOf(
                                ChartEntry("You sent", sent[i].toFloat(), colors.chartOut, Money.format(sent[i])),
                                ChartEntry("They sent you", received[i].toFloat(), colors.chartIn, Money.format(received[i])),
                            ),
                        )
                    },
                    selectedIndex = picked?.takeIf { it < labels.size },
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(colors.chartOut)
                        Spacer(Modifier.width(6.dp))
                        Text("You sent", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(colors.chartIn)
                        Spacer(Modifier.width(6.dp))
                        Text("They sent you", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            BreakdownView.LIST -> {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Text("You sent", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
                    Text("They sent", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
                }
                labels.indices.reversed().forEach { i ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(labels[i], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(Money.format(sent[i]), style = MaterialTheme.typography.bodyMedium, color = colors.expense, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
                        Text(Money.format(received[i]), style = MaterialTheme.typography.bodyMedium, color = colors.income, modifier = Modifier.width(88.dp), textAlign = TextAlign.End)
                    }
                }
            }
            else -> TrendLineChart(
                labels = labels,
                series = listOf(
                    LineSeries("You sent", sent.toList(), colors.chartOut),
                    LineSeries("They sent you", received.toList(), colors.chartIn, dashed = true, marker = MarkerShape.SQUARE),
                ),
                selectedIndex = picked,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Over $year: " + whoIsUp(received.sum() - sent.sum()).replaceFirstChar { it.lowercase() },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** The category money from people goes under when it is turned round. */
private const val MONEY_FROM_PEOPLE = "Money from people"
private const val MONEY_TO_PEOPLE = "People & services"

/**
 * Every payment with one person, each ticked to swap sides: sent becomes
 * "they sent you", and the other way. Ones recorded as sent that read like
 * money in start ticked.
 */
@Composable
private fun TurnRoundDialog(
    ledger: PersonLedger,
    onDismiss: () -> Unit,
    onConfirm: (toMoneyIn: List<PayeeEntry>, toMoneyOut: List<PayeeEntry>) -> Unit,
) {
    var ticked by remember(ledger.key) {
        mutableStateOf(ledger.sent.filter { com.rhys.financetracker.data.importer.PayeeNames.readsLikeMoneyIn(it.description) }.map { it.id }.toSet())
    }
    val rows = ledger.sent.map { it to true } + ledger.received.map { it to false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which way did the money go?") },
        text = {
            Column {
                Text(
                    text = "Tick any that are on the wrong side and they swap: \"you sent\" becomes " +
                        "\"they sent you\", and the other way. Ones that read like money in are ticked already.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(rows.sortedByDescending { it.first.date }, key = { it.first.id }) { (entry, isSent) ->
                        val on = entry.id in ticked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { ticked = if (on) ticked - entry.id else ticked + entry.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = on,
                                onCheckedChange = { ticked = if (on) ticked - entry.id else ticked + entry.id },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val side = if (isSent != on) "You sent" else "They sent you"
                                Text(
                                    text = "${DateUtils.formatShort(entry.date)} · ${Money.format(entry.amountMinor)} · " +
                                        (if (on) "will be: $side" else side),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ticked.isNotEmpty(),
                onClick = {
                    onConfirm(
                        ledger.sent.filter { it.id in ticked },
                        ledger.received.filter { it.id in ticked },
                    )
                },
            ) { Text(if (ticked.isEmpty()) "Swap" else "Swap ${ticked.size}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
