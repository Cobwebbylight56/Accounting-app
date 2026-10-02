package com.rhys.financetracker.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.SubscriptionTracker.Subscription
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.BillFinderRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.LineSeries
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.TrendLineChart
import com.rhys.financetracker.ui.components.animatedMoney
import com.rhys.financetracker.ui.subscriptions.paidFrom
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** One card, and what is going on with it. */
data class CardSummary(
    val card: AccountWithBalance,
    /** What is owed on it now; never below zero. */
    val owedMinor: Long,
    val limitMinor: Long?,
    val spentThisMonthMinor: Long,
    val spentLastMonthMinor: Long,
    /** What was owed at the end of each recent month, oldest first. */
    val owedAtMonthEnds: List<Pair<YearMonth, Long>>,
    /** Subscriptions paid on this card that are still going. */
    val subscriptions: List<Subscription>,
) {
    /** How much of the limit is used, 0 to 1, when there is a limit. */
    val used: Float? get() = limitMinor?.takeIf { it > 0 }?.let { (owedMinor.toFloat() / it).coerceIn(0f, 1f) }
}

data class CardsState(val isLoading: Boolean = true, val cards: List<CardSummary> = emptyList()) {
    val totalOwedMinor: Long get() = cards.sumOf { it.owedMinor }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CardsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val billFinder: BillFinderRepository,
) : ViewModel() {

    val state: StateFlow<CardsState> = accountRepository.observeWithBalances()
        .mapLatest { accounts ->
            val cards = accounts.filter { it.account.type in CARD_TYPES }
            val subscriptions = runCatching { billFinder.subscriptions() }.getOrDefault(emptyList())
            val thisMonth = DateUtils.currentYearMonth()
            CardsState(
                isLoading = false,
                cards = cards.map { card ->
                    val id = card.account.id
                    suspend fun spentIn(month: YearMonth): Long {
                        val range = DateUtils.monthRange(month)
                        return transactionRepository.observeIncomeExpense(range.start, range.endInclusive, accountId = id)
                            .first().expenseMinor
                    }
                    CardSummary(
                        card = card,
                        owedMinor = (-card.balanceMinor).coerceAtLeast(0L),
                        limitMinor = card.account.creditLimitMinor,
                        spentThisMonthMinor = spentIn(thisMonth),
                        spentLastMonthMinor = spentIn(thisMonth.minusMonths(1)),
                        owedAtMonthEnds = (MONTHS_SHOWN - 1 downTo 0).map { back ->
                            val month = thisMonth.minusMonths(back.toLong())
                            val balance = if (back == 0) {
                                card.balanceMinor
                            } else {
                                accountRepository.balanceAsOf(id, month.atEndOfMonth())
                            }
                            month to (-balance).coerceAtLeast(0L)
                        },
                        subscriptions = subscriptions.paidFrom(id),
                    )
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardsState())

    private companion object {
        val CARD_TYPES = setOf(AccountType.CREDIT_CARD, AccountType.PAY_LATER)
        const val MONTHS_SHOWN = 6
    }
}

/**
 * Every credit card and pay-later account on one page: what is owed and how
 * much of the limit that is, what went on it this month, how the balance has
 * moved, and the subscriptions it pays — with its statement a tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardsScreen(
    onBack: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onImportStatement: (Long) -> Unit,
    onAddCard: () -> Unit,
    viewModel: CardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credit cards") },
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
            if (state.cards.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.CreditCard,
                        title = "No cards yet",
                        message = "Add a credit card or pay-later account, then import its statement to see what goes on it.",
                    )
                }
                item { Button(onClick = onAddCard, modifier = Modifier.fillMaxWidth()) { Text("Add a card") } }
                return@LazyColumn
            }
            item {
                Column {
                    Text("Owed on cards", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = animatedMoney(state.totalOwedMinor),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = FinanceTheme.colors.negative,
                    )
                }
            }
            items(state.cards, key = { it.card.account.id }) { summary ->
                CardCard(
                    summary = summary,
                    onOpen = { onOpenAccount(summary.card.account.id) },
                    onImport = { onImportStatement(summary.card.account.id) },
                )
            }
            item { TextButton(onClick = onAddCard) { Text("Add another card") } }
        }
    }
}

@Composable
private fun CardCard(summary: CardSummary, onOpen: () -> Unit, onImport: () -> Unit) {
    val colors = FinanceTheme.colors
    SectionCard(
        title = summary.card.account.name,
        subtitle = listOfNotNull(summary.card.account.type.displayName, summary.card.personName).joinToString(" · "),
    ) {
        Text(
            text = animatedMoney(summary.owedMinor) + " owed",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        summary.limitMinor?.let { limit ->
            val used = summary.used ?: 0f
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { used },
                modifier = Modifier.fillMaxWidth(),
                color = if (used >= HIGH_USE) colors.warning else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${(used * 100).toInt()}% of the ${Money.format(limit)} limit · " +
                    "${Money.format((limit - summary.owedMinor).coerceAtLeast(0L))} left to use",
                style = MaterialTheme.typography.bodySmall,
                color = if (used >= HIGH_USE) colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Spent this month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(summary.spentThisMonthMinor), style = MaterialTheme.typography.titleMedium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Last month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(summary.spentLastMonthMinor), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (summary.owedAtMonthEnds.any { it.second > 0 }) {
            Spacer(Modifier.height(10.dp))
            Text("Owed at each month end", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            TrendLineChart(
                labels = summary.owedAtMonthEnds.map { DateUtils.monthNameShort(it.first.monthValue) },
                series = listOf(LineSeries("Owed", summary.owedAtMonthEnds.map { it.second }, colors.chartOut)),
                height = 150.dp,
            )
        }
        if (summary.subscriptions.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = "Subscriptions on this card · ${Money.format(summary.subscriptions.sumOf { it.monthlyMinor })} a month",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            summary.subscriptions.forEach { subscription ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(subscription.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Money.format(subscription.amountMinor), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("Import statement") }
            TextButton(onClick = onOpen) { Text("Payments") }
        }
    }
}

/** Above this share of the limit, the bar and its words turn amber. */
private const val HIGH_USE = 0.75f
