package com.rhys.financetracker.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.SubscriptionPlan
import com.rhys.financetracker.data.importer.SubscriptionTracker
import com.rhys.financetracker.data.importer.SubscriptionTracker.Status
import com.rhys.financetracker.data.importer.SubscriptionTracker.Subscription
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.BillFinderRepository
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.ui.components.BarGroup
import com.rhys.financetracker.ui.components.ChartEntry
import com.rhys.financetracker.ui.components.ChartLegend
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.GroupedBarChart
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.animatedMoney
import com.rhys.financetracker.ui.components.chartColorAt
import com.rhys.financetracker.ui.theme.FinanceTheme
import com.rhys.financetracker.ui.transactions.LedgerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SubscriptionsState(
    val isLoading: Boolean = true,
    val subscriptions: List<Subscription> = emptyList(),
    val accountNames: Map<Long, String> = emptyMap(),
    /** Credit cards and other borrowing: what's paid from them is a card payment. */
    val cardAccounts: Set<Long> = emptySet(),
) {
    val active: List<Subscription> get() = subscriptions.filter { it.status == Status.ACTIVE }
    val monthlyMinor: Long get() = active.sumOf { it.monthlyMinor }

    /** Being paid, or missed a payment lately. */
    val going: List<Subscription> get() = subscriptions.filter { it.status.isGoing }

    /** Stopped, cancelled or run out: kept out of sight. */
    val ended: List<Subscription> get() = subscriptions.filterNot { it.status.isGoing }

    /** Still going, and nothing said about them yet: the ones to sort out. */
    val toSortOut: List<Subscription> get() = going.filter { it.plan?.isSorted != true }
}

/** How the subscriptions are listed. */
enum class SubscriptionOrder(val label: String) {
    COST("Cost"),
    NAME("Name"),
    NEXT("Next due"),
    ;

    fun sort(list: List<Subscription>): List<Subscription> = when (this) {
        COST -> list.sortedByDescending { it.monthlyMinor }
        NAME -> list.sortedBy { it.name.lowercase() }
        NEXT -> list.sortedBy { it.nextDue }
    }
}

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val billFinder: BillFinderRepository,
    private val accountRepository: AccountRepository,
    private val ledgerRequests: LedgerRequests,
) : ViewModel() {

    private val _state = MutableStateFlow(SubscriptionsState())
    val state: StateFlow<SubscriptionsState> = _state.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch {
            val found = runCatching { billFinder.subscriptions() }.getOrDefault(emptyList())
            val accounts = accountRepository.observeAllWithBalances().first()
            _state.value = SubscriptionsState(
                isLoading = false,
                subscriptions = found,
                accountNames = accounts.associate { it.account.id to it.account.name },
                cardAccounts = accounts.filter { it.isLiability }.map { it.account.id }.toSet(),
            )
        }
    }

    /** Leaves the Payments page a search for this one, over all time. */
    fun open(subscription: Subscription) {
        ledgerRequests.openSearchAll(subscription.name)
    }

    /** When it started and how long it runs: the app works out when it ends. */
    fun setLength(subscription: Subscription, startedOn: LocalDate?, months: Int?) {
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(startedOn = startedOn, months = months))
    }

    /** It was cancelled: it ends today, and goes out of sight. */
    fun cancelled(subscription: Subscription) {
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(cancelledOn = DateUtils.today()))
    }

    /** It wasn't cancelled after all, or it was started again. */
    fun stillGoing(subscription: Subscription) {
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(cancelledOn = null, months = null))
    }

    /** "Yes, I pay this": sorted out. */
    fun confirm(subscription: Subscription) {
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(confirmed = true))
    }

    /** Not a subscription or bill you have: hidden, and never offered as a bill. */
    fun notMine(subscription: Subscription) {
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(notMine = true))
    }

    /** The same thing as [other], under another name: counted as that one. */
    fun sameAs(subscription: Subscription, other: Subscription) {
        if (subscription.key == other.key) return
        save((subscription.plan ?: SubscriptionPlan(subscription.key)).copy(sameAs = other.key))
    }

    private fun save(plan: SubscriptionPlan) {
        viewModelScope.launch {
            billFinder.savePlan(plan)
            reload()
        }
    }
}

/**
 * Every subscription and regular payment the statements show, a dot for each
 * of the last twelve months it was paid in, and whether it is still going.
 *
 * Ones that have stopped or been cancelled are out of sight unless asked
 * for. Each asks when it started and how long it runs, so the app knows
 * when it ends.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpenLedger: () -> Unit,
    /** Shown as a tab of Bills & subscriptions, which has the title and back arrow. */
    embedded: Boolean = false,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSubscriptions by rememberSaveable { mutableStateOf(true) }
    var showEnded by rememberSaveable { mutableStateOf(false) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableStateOf(SubscriptionOrder.COST) }
    var sortingOut by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        contentWindowInsets = if (embedded) {
            androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
        } else {
            androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
        },
        topBar = {
            if (!embedded) TopAppBar(
                title = { Text("Subscriptions") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isLoading) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                return@LazyColumn
            }
            if (state.subscriptions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CheckCircle,
                        title = "No subscriptions found yet",
                        message = "Import a few months of statements — anything paid at a steady rhythm shows here.",
                    )
                }
                return@LazyColumn
            }
            // Subscriptions (Netflix, Audible, Claude) apart from bills for
            // services the household needs (insurance, phone, car, council tax).
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = showSubscriptions,
                        onClick = { showSubscriptions = true },
                        label = { Text("Subscriptions · ${state.going.count { it.isSubscription }}") },
                    )
                    FilterChip(
                        selected = !showSubscriptions,
                        onClick = { showSubscriptions = false },
                        label = { Text("Bills & services · ${state.going.count { !it.isSubscription }}") },
                    )
                }
            }
            val shown = state.copy(subscriptions = state.subscriptions.filter { it.isSubscription == showSubscriptions })
            val noun = if (showSubscriptions) "subscription" else "bill"
            // Found from the statements, so some may not be yours, or be one thing twice.
            if (state.toSortOut.isNotEmpty()) {
                item(key = "sort-out") {
                    SectionCard(
                        title = "Sort them out",
                        subtitle = "${state.toSortOut.size} found in your statements to check",
                    ) {
                        Text(
                            "Say which you pay, which aren't yours, which are one thing twice, and which you've " +
                                "cancelled. Ones that aren't yours are hidden and never offered as bills again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { sortingOut = true }, modifier = Modifier.fillMaxWidth()) { Text("Sort them out") }
                    }
                }
            }
            item(key = "order") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort by", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SubscriptionOrder.entries.forEach { option ->
                        FilterChip(selected = order == option, onClick = { order = option }, label = { Text(option.label) })
                    }
                }
            }
            item { Summary(shown, noun) }
            item { MonthlyGraph(shown.subscriptions, noun) }
            listOf(Status.ACTIVE, Status.MISSED).forEach { status ->
                val group = order.sort(shown.subscriptions.filter { it.status == status })
                if (group.isEmpty()) return@forEach
                item(key = "head-$status") { Heading("${status.label} · ${group.size}") }
                items(group, key = { "${it.name}-${it.accountId}-${it.amountMinor}-${it.lastDate}" }) { subscription ->
                    SubscriptionRow(
                        subscription = subscription,
                        accountName = state.accountNames[subscription.accountId],
                        onCard = subscription.accountId in state.cardAccounts,
                        onClick = { editingKey = subscription.key },
                    )
                }
            }
            val ended = shown.ended
            if (ended.isNotEmpty()) {
                item(key = "ended-toggle") {
                    TextButton(onClick = { showEnded = !showEnded }) {
                        Text(
                            if (showEnded) {
                                "Hide the ${ended.size} that have ended"
                            } else {
                                "${ended.size} stopped or cancelled — hidden. Show them"
                            },
                        )
                    }
                }
                if (showEnded) {
                    items(ended, key = { "ended-${it.name}-${it.accountId}-${it.amountMinor}-${it.lastDate}" }) { subscription ->
                        SubscriptionRow(
                            subscription = subscription,
                            accountName = state.accountNames[subscription.accountId],
                            onCard = subscription.accountId in state.cardAccounts,
                            onClick = { editingKey = subscription.key },
                        )
                    }
                }
            }
            item {
                Text(
                    text = "Found from the rhythm of your statements: paid every week, month or year " +
                        "at much the same amount. One that misses a payment may have been cancelled; " +
                        "missing two, it has stopped and is hidden. Tap one to say when it started " +
                        "and how long it runs, so the app knows when it ends — or that you cancelled it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (sortingOut) {
        SortOutDialog(
            toSortOut = state.toSortOut,
            everything = state.going,
            accountNames = state.accountNames,
            onConfirm = viewModel::confirm,
            onCancelled = viewModel::cancelled,
            onNotMine = viewModel::notMine,
            onSameAs = viewModel::sameAs,
            onClose = { sortingOut = false },
        )
    }

    val editing = state.subscriptions.firstOrNull { it.key == editingKey }
    if (editing != null) {
        SubscriptionSheet(
            subscription = editing,
            others = state.going.filter { it.key != editing.key },
            onNotMine = {
                viewModel.notMine(editing)
                editingKey = null
            },
            onSameAs = { other ->
                viewModel.sameAs(editing, other)
                editingKey = null
            },
            accountName = state.accountNames[editing.accountId],
            onDismiss = { editingKey = null },
            onSave = { start, months ->
                viewModel.setLength(editing, start, months)
                editingKey = null
            },
            onCancelled = {
                viewModel.cancelled(editing)
                editingKey = null
            },
            onStillGoing = {
                viewModel.stillGoing(editing)
                editingKey = null
            },
            onSeePayments = {
                editingKey = null
                viewModel.open(editing)
                onOpenLedger()
            },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Summary(state: SubscriptionsState, noun: String = "subscription") {
    SectionCard(title = "Still being paid") {
        Text(
            text = animatedMoney(state.monthlyMinor) + " a month",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${Money.format(state.monthlyMinor * 12)} a year across ${state.active.size} " +
                (if (state.active.size == 1) noun else "${noun}s") + ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val missed = state.subscriptions.count { it.status == Status.MISSED }
        if (missed > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$missed missed a payment lately — tap it to say if it was cancelled.",
                style = MaterialTheme.typography.bodyMedium,
                color = FinanceTheme.colors.warning,
            )
        }
    }
}

/**
 * What each one came to, month by month over the last year: a bar for the
 * month, made of a part for each — so a new one, or a price rise, shows.
 */
@Composable
private fun MonthlyGraph(subscriptions: List<Subscription>, noun: String) {
    val thisMonth = DateUtils.currentYearMonth()
    val months = (SubscriptionTracker.MONTHS_SHOWN - 1 downTo 0).map { thisMonth.minusMonths(it.toLong()) }
    fun Subscription.paidIn(month: YearMonth) = paidByMonth[month] ?: 0L
    val ranked = subscriptions
        .map { it to months.sumOf { month -> it.paidIn(month) } }
        .filter { it.second > 0L }
        .sortedByDescending { it.second }
    if (ranked.isEmpty()) return
    // The biggest few each have a colour; the rest share one.
    val named = ranked.take(MOST_NAMED)
    val rest = ranked.drop(MOST_NAMED).map { it.first }
    val colours = named.indices.map { chartColorAt(it) }
    val restColour = MaterialTheme.colorScheme.outline
    SectionCard(title = "Each month", subtitle = "What each $noun came to") {
        GroupedBarChart(
            groups = months.map { month ->
                val parts = named.mapIndexed { index, (subscription, _) ->
                    val paid = subscription.paidIn(month)
                    ChartEntry(subscription.name, paid.toFloat(), colours[index], Money.format(paid))
                } + rest.sumOf { it.paidIn(month) }.let { paid ->
                    listOf(ChartEntry("Others", paid.toFloat(), restColour, Money.format(paid)))
                }
                val total = named.sumOf { it.first.paidIn(month) } + rest.sumOf { it.paidIn(month) }
                BarGroup(
                    label = DateUtils.monthNameShort(month.monthValue),
                    bars = listOf(
                        ChartEntry(
                            label = "Total",
                            value = total.toFloat(),
                            color = colours.first(),
                            displayValue = Money.format(total),
                            segments = parts.filter { it.value > 0f },
                        ),
                    ),
                )
            },
            height = 170.dp,
        )
        Spacer(Modifier.height(8.dp))
        ChartLegend(
            entries = named.mapIndexed { index, (subscription, total) ->
                ChartEntry(subscription.name, total.toFloat(), colours[index], Money.format(total) + " in the year")
            } + rest.sumOf { months.sumOf { month -> it.paidIn(month) } }.let { total ->
                listOf(ChartEntry("Others", total.toFloat(), restColour, Money.format(total) + " in the year"))
            },
        )
    }
}

/** The ones given a colour of their own in the graph. */
private const val MOST_NAMED = 6

@Composable
private fun SubscriptionRow(subscription: Subscription, accountName: String?, onCard: Boolean, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val going = subscription.status.isGoing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Money.format(subscription.amountMinor),
                style = MaterialTheme.typography.bodyLarge,
                color = if (going) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = listOfNotNull(
                subscription.frequency.displayName,
                // A card can't pay a direct debit or standing order: on one, it's a card payment.
                if (onCard) PaymentKind.CARD.shortName else subscription.paidBy?.shortName,
                accountName,
                subscription.categoryName,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // A dot for each month: filled when it was paid that month.
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            subscription.monthsPaid.forEach { paid ->
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (paid) colors.chartIn else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                            shape = CircleShape,
                        ),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${DateUtils.monthNameShort(subscription.months.first().monthValue)}–" +
                    DateUtils.monthNameShort(subscription.months.last().monthValue),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        val ends = subscription.endsOn
        Text(
            text = when (subscription.status) {
                Status.ACTIVE -> "Last paid ${DateUtils.formatShort(subscription.lastDate)} · next about ${DateUtils.formatShort(subscription.nextDue)}"
                Status.MISSED -> "Due about ${DateUtils.formatShort(subscription.nextDue)} but not seen — cancelled?"
                Status.STOPPED -> "Not paid since ${DateUtils.formatShort(subscription.lastDate)}"
                Status.ENDED -> subscription.plan?.cancelledOn?.let { "Cancelled ${DateUtils.formatShort(it)}" }
                    ?: "Ended ${ends?.let { DateUtils.formatShort(it) }.orEmpty()}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (subscription.status) {
                Status.MISSED -> colors.warning
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (going) {
            if (ends != null) {
                val left = subscription.paymentsLeft
                Text(
                    text = "Ends ${DateUtils.format(ends)}" + when (left) {
                        null -> ""
                        0 -> " · no more payments"
                        1 -> " · 1 more payment"
                        else -> " · $left more payments"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (subscription.plan?.startedOn == null) {
                // Asked, so the app can work out when it ends.
                Text(
                    text = "When did it start, and how long is it for? Tap to say",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * One subscription: when it started and how long it runs — from which the
 * app works out when it ends — or that it has been cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionSheet(
    subscription: Subscription,
    others: List<Subscription>,
    onNotMine: () -> Unit,
    onSameAs: (Subscription) -> Unit,
    accountName: String?,
    onDismiss: () -> Unit,
    onSave: (LocalDate?, Int?) -> Unit,
    onCancelled: () -> Unit,
    onStillGoing: () -> Unit,
    onSeePayments: () -> Unit,
) {
    val plan = subscription.plan
    var started by rememberSaveable { mutableStateOf(plan?.startedOn ?: subscription.firstDate) }
    var months by rememberSaveable { mutableStateOf(plan?.months) }
    val ends = months?.let { started.plusMonths(it.toLong()) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(subscription.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(
                        "${Money.format(subscription.amountMinor)} ${subscription.frequency.displayName.lowercase()}",
                        accountName,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "First seen ${DateUtils.format(subscription.firstDate)}, last paid ${DateUtils.format(subscription.lastDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DateField(label = "When did it start?", date = started, onDateChange = { started = it })

            Text("How long is it for?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = months == null, onClick = { months = null }, label = { Text("Until I cancel") })
                SubscriptionPlan.LENGTHS.forEach { length ->
                    FilterChip(
                        selected = months == length,
                        onClick = { months = length },
                        label = { Text(if (length == 1) "1 month" else "$length months") },
                    )
                }
            }
            Text(
                text = if (ends == null) {
                    "It runs until you cancel it."
                } else if (ends.isAfter(DateUtils.today())) {
                    "It ends on ${DateUtils.format(ends)}."
                } else {
                    "That ended on ${DateUtils.format(ends)}, so it will be hidden."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Button(onClick = { onSave(started, months) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (subscription.status.isGoing) {
                OutlinedButton(onClick = onCancelled, modifier = Modifier.fillMaxWidth()) {
                    Text("I've cancelled it — hide it")
                }
            } else {
                OutlinedButton(onClick = onStillGoing, modifier = Modifier.fillMaxWidth()) {
                    Text("It's still going")
                }
            }
            OutlinedButton(onClick = onNotMine, modifier = Modifier.fillMaxWidth()) {
                Text("Not mine — hide it")
            }
            SameAsChooser(subscription = subscription, others = others, onChoose = onSameAs)
            TextButton(onClick = onSeePayments, modifier = Modifier.fillMaxWidth()) { Text("See its payments") }
        }
    }
}

/** "Same as another…": the others, the likeliest first, to say this one is one of them twice. */
@Composable
private fun SameAsChooser(subscription: Subscription, others: List<Subscription>, onChoose: (Subscription) -> Unit) {
    if (others.isEmpty()) return
    var open by rememberSaveable(subscription.key) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(if (open) "Hide the others" else "It's the same as another one…")
    }
    if (!open) return
    val first = subscription.name.lowercase().substringBefore(' ')
    val likeliest = others.sortedWith(
        compareByDescending<Subscription> { it.name.lowercase().startsWith(first) }
            .thenBy { kotlin.math.abs(it.amountMinor - subscription.amountMinor) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        likeliest.forEach { other ->
            OutlinedButton(onClick = { onChoose(other) }, modifier = Modifier.fillMaxWidth()) {
                Text("${other.name} · ${Money.format(other.amountMinor)}", maxLines = 1)
            }
        }
    }
}

/**
 * One at a time, the subscriptions found in the statements that nothing has
 * been said about: yours, cancelled, not yours, or the same as another.
 */
@Composable
private fun SortOutDialog(
    toSortOut: List<Subscription>,
    everything: List<Subscription>,
    accountNames: Map<Long, String>,
    onConfirm: (Subscription) -> Unit,
    onCancelled: (Subscription) -> Unit,
    onNotMine: (Subscription) -> Unit,
    onSameAs: (Subscription, Subscription) -> Unit,
    onClose: () -> Unit,
) {
    var skipped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val current = toSortOut.firstOrNull { it.key !in skipped }
    val left = toSortOut.count { it.key !in skipped }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (current == null) "All sorted" else current.name) },
        text = {
            if (current == null) {
                Text("Nothing left to sort out. Ones you skipped are still on the list to come back to.")
            } else Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    listOfNotNull(
                        "${Money.format(current.amountMinor)} ${current.frequency.displayName.lowercase()}",
                        accountNames[current.accountId],
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Paid ${current.timesPaid} times since ${DateUtils.format(current.firstDate)}, last on ${DateUtils.format(current.lastDate)}. " +
                        "$left to go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { onConfirm(current) }, modifier = Modifier.fillMaxWidth()) { Text("Yes, I pay this") }
                OutlinedButton(onClick = { onCancelled(current) }, modifier = Modifier.fillMaxWidth()) { Text("I've cancelled it") }
                OutlinedButton(onClick = { onNotMine(current) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Not mine — not a subscription")
                }
                SameAsChooser(
                    subscription = current,
                    others = everything.filter { it.key != current.key },
                    onChoose = { other -> onSameAs(current, other) },
                )
            }
        },
        confirmButton = {
            if (current == null) {
                TextButton(onClick = onClose) { Text("Done") }
            } else {
                TextButton(onClick = { skipped = skipped + current.key }) { Text("Skip") }
            }
        },
        dismissButton = { if (current != null) TextButton(onClick = onClose) { Text("Finish later") } },
    )
}

/** For a card or account page: the subscriptions paid from [accountId] that are still going. */
fun List<Subscription>.paidFrom(accountId: Long): List<Subscription> =
    filter { it.accountId == accountId && it.status.isGoing }
