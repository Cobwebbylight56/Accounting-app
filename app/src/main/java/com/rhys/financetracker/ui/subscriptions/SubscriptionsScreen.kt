package com.rhys.financetracker.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.rhys.financetracker.data.importer.SubscriptionTracker.Status
import com.rhys.financetracker.data.importer.SubscriptionTracker.Subscription
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.BillFinderRepository
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.LineSeries
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.TrendLineChart
import com.rhys.financetracker.ui.components.animatedMoney
import com.rhys.financetracker.ui.theme.FinanceTheme
import com.rhys.financetracker.ui.transactions.LedgerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
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
) {
    val active: List<Subscription> get() = subscriptions.filter { it.status == Status.ACTIVE }
    val monthlyMinor: Long get() = active.sumOf { it.monthlyMinor }
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
        viewModelScope.launch {
            val found = runCatching { billFinder.subscriptions() }.getOrDefault(emptyList())
            val names = accountRepository.observeAllWithBalances().first().associate { it.account.id to it.account.name }
            _state.value = SubscriptionsState(isLoading = false, subscriptions = found, accountNames = names)
        }
    }

    /** Leaves the Payments page a search for this one, over all time. */
    fun open(subscription: Subscription) {
        ledgerRequests.openSearchAll(subscription.name)
    }
}

/**
 * Every subscription and regular payment the statements show, a dot for each
 * of the last twelve months it was paid in, and whether it is still going:
 * paid every month up to the latest statement, missed one, or stopped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpenLedger: () -> Unit,
    viewModel: SubscriptionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
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
            item { Summary(state) }
            item { MonthlyGraph(state.subscriptions) }
            Status.entries.forEach { status ->
                val group = state.subscriptions.filter { it.status == status }
                if (group.isEmpty()) return@forEach
                item(key = "head-$status") {
                    Text(
                        text = "${status.label} · ${group.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(group, key = { "${it.name}-${it.accountId}" }) { subscription ->
                    SubscriptionRow(
                        subscription = subscription,
                        accountName = state.accountNames[subscription.accountId],
                        onClick = {
                            viewModel.open(subscription)
                            onOpenLedger()
                        },
                    )
                }
            }
            item {
                Text(
                    text = "Found from the rhythm of your statements: paid every week, month or year " +
                        "at much the same amount. One that misses a payment may have been cancelled; " +
                        "missing two, it has stopped. Judged against the latest statement for each " +
                        "account, so a card not imported lately is not shown as stopped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Summary(state: SubscriptionsState) {
    SectionCard(title = "Still being paid") {
        Text(
            text = animatedMoney(state.monthlyMinor) + " a month",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${Money.format(state.monthlyMinor * 12)} a year across ${state.active.size} " +
                (if (state.active.size == 1) "subscription" else "subscriptions") + ".",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val missed = state.subscriptions.count { it.status == Status.MISSED }
        if (missed > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$missed missed a payment lately — worth checking they were cancelled.",
                style = MaterialTheme.typography.bodyMedium,
                color = FinanceTheme.colors.warning,
            )
        }
    }
}

/** What all of them cost each month over the year, to see the total creep up. */
@Composable
private fun MonthlyGraph(subscriptions: List<Subscription>) {
    val months = subscriptions.first().months
    val totals = months.indices.map { i ->
        subscriptions.filter { it.monthsPaid.getOrElse(i) { false } }.sumOf { it.amountMinor }
    }
    SectionCard(title = "Each month", subtitle = "What your subscriptions came to") {
        TrendLineChart(
            labels = months.map { DateUtils.monthNameShort(it.monthValue) },
            series = listOf(LineSeries("Subscriptions", totals, FinanceTheme.colors.chartOut)),
            height = 170.dp,
        )
    }
}

@Composable
private fun SubscriptionRow(subscription: Subscription, accountName: String?, onClick: () -> Unit) {
    val colors = FinanceTheme.colors
    val stopped = subscription.status != Status.ACTIVE
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
                color = if (stopped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = listOfNotNull(subscription.frequency.displayName, accountName, subscription.categoryName)
                .joinToString(" · "),
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
        Text(
            text = when (subscription.status) {
                Status.ACTIVE -> "Last paid ${DateUtils.formatShort(subscription.lastDate)} · next about ${DateUtils.formatShort(subscription.nextDue)}"
                Status.MISSED -> "Due about ${DateUtils.formatShort(subscription.nextDue)} but not seen — cancelled?"
                Status.STOPPED -> "Not paid since ${DateUtils.formatShort(subscription.lastDate)}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = when (subscription.status) {
                Status.ACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                Status.MISSED -> colors.warning
                Status.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** For a card or account page: the subscriptions paid from [accountId] that are still going. */
fun List<Subscription>.paidFrom(accountId: Long): List<Subscription> =
    filter { it.accountId == accountId && it.status != Status.STOPPED }
