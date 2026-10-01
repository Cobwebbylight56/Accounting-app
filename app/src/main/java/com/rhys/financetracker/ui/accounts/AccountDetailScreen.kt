package com.rhys.financetracker.ui.accounts

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.ImportBatchWithRemaining
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.ImportHistoryRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.navigation.Routes
import com.rhys.financetracker.ui.theme.FinanceTheme
import com.rhys.financetracker.ui.transactions.LedgerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A month and whether a statement for it is in. */
data class MonthCover(val month: YearMonth, val isIn: Boolean)

/** A month's closing balance. */
data class MonthEnd(val month: YearMonth, val balanceMinor: Long)

data class AccountDetailState(
    val isLoading: Boolean = true,
    val account: AccountWithBalance? = null,
    val recent: List<TransactionWithDetails> = emptyList(),
    val imports: List<ImportBatchWithRemaining> = emptyList(),
    /** The last year of months, oldest first, from the first statement on. */
    val coverage: List<MonthCover> = emptyList(),
    val monthEnds: List<MonthEnd> = emptyList(),
    val message: String? = null,
) {
    val missingMonths: List<YearMonth> get() = coverage.filterNot { it.isIn }.map { it.month }
}

/**
 * One account: its balance, which months of statements are in, what each
 * import brought, how the balance has moved, and its latest payments.
 *
 * Tapping an account used to open its edit form, so there was nowhere to
 * see an account's own payments without filtering the whole Money list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
    transactionDao: TransactionDao,
    private val importHistory: ImportHistoryRepository,
    private val ledgerRequests: LedgerRequests,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val accountId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val message = MutableStateFlow<String?>(null)

    private val account = accountRepository.observeAllWithBalances()
        .map { list -> list.firstOrNull { it.account.id == accountId } }

    /** The last six months' closing balances, worked out whenever the balance moves. */
    private val monthEnds = account.mapLatest { current ->
        if (current == null) return@mapLatest emptyList<MonthEnd>()
        val thisMonth = DateUtils.currentYearMonth()
        (1..MONTH_ENDS).map { back ->
            val month = thisMonth.minusMonths(back.toLong())
            MonthEnd(month, accountRepository.balanceAsOf(accountId, month.atEndOfMonth()))
        }
    }

    val state: StateFlow<AccountDetailState> = combine(
        account,
        transactionRepository.search(TransactionFilter(accountIds = setOf(accountId), limit = RECENT)),
        importHistory.observeForAccount(accountId),
        combine(transactionDao.observeStatementMonths(accountId), monthEnds) { months, ends -> months to ends },
        message,
    ) { current, recent, imports, monthsAndEnds, text ->
        val (months, ends) = monthsAndEnds
        AccountDetailState(
            isLoading = false,
            account = current,
            recent = recent,
            imports = imports,
            coverage = coverageOf(months.mapNotNull { DateUtils.parseYearMonthKey(it) }),
            monthEnds = ends,
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    /** Leaves the Money tab a filter for this account. */
    fun openInLedger() {
        ledgerRequests.openAccount(accountId)
    }

    fun undo(batchId: Long) {
        viewModelScope.launch {
            message.value = when (val result = importHistory.undo(batchId)) {
                is AppResult.Success -> "Import undone: ${result.data.removed} " +
                    (if (result.data.removed == 1) "payment" else "payments") + " taken out" +
                    if (result.data.balanceRestored) " and the balance put back" else ""
                is AppResult.Failure -> result.message
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    companion object {
        private const val RECENT = 30
        private const val MONTH_ENDS = 6
        private const val COVERAGE_MONTHS = 12L

        /**
         * The last year of months, each marked in or missing — starting no
         * earlier than the first statement, so a new account is not a row of
         * gaps it never had. Empty when no statement is in at all.
         */
        fun coverageOf(months: List<YearMonth>, now: YearMonth = DateUtils.currentYearMonth()): List<MonthCover> {
            val first = months.minOrNull() ?: return emptyList()
            val start = maxOf(first, now.minusMonths(COVERAGE_MONTHS - 1))
            val have = months.toSet()
            return generateSequence(start) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(now) }
                .map { MonthCover(it, it in have) }
                .toList()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onImportStatement: (Long) -> Unit,
    onOpenTransaction: (Long) -> Unit,
    onOpenLedger: () -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var undoing by remember { mutableStateOf<ImportBatchWithRemaining?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val account = state.account
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(account?.account?.name ?: "Account", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(viewModel.accountId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit this account")
                    }
                },
            )
        },
    ) { padding ->
        if (account == null) {
            if (!state.isLoading) {
                Text(
                    text = "This account no longer exists.",
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { BalanceHeader(account) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onImportStatement(viewModel.accountId) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Import statements") }
                    OutlinedButton(
                        onClick = {
                            viewModel.openInLedger()
                            onOpenLedger()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("All payments") }
                }
            }

            item { StatementsCard(state = state, onUndo = { undoing = it }) }

            if (state.monthEnds.isNotEmpty()) {
                item { MonthEndsCard(state.monthEnds) }
            }

            item {
                SectionCard(
                    title = "Latest payments",
                    action = {
                        TextButton(onClick = {
                            viewModel.openInLedger()
                            onOpenLedger()
                        }) { Text("All") }
                    },
                ) {
                    if (state.recent.isEmpty()) {
                        Text(
                            text = "Nothing recorded on this account yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.recent.forEach { item ->
                        PaymentRow(item, accountId = viewModel.accountId) {
                            onOpenTransaction(item.transaction.id)
                        }
                    }
                }
            }
        }
    }

    undoing?.let { batch ->
        AlertDialog(
            onDismissRequest = { undoing = null },
            title = { Text("Undo this import?") },
            text = {
                Text(
                    "The ${batch.remainingRows} payments it added that are still here are taken " +
                        "out, and the balance goes back to what it was before, if nothing has set " +
                        "it since. Entries it updated keep the statement's version.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.undo(batch.batch.id)
                    undoing = null
                }) { Text("Undo import", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { undoing = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun BalanceHeader(item: AccountWithBalance) {
    val account = item.account
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = FinanceTheme.colors.tileBlue,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = listOfNotNull(item.personName, account.type.displayName, account.holding.displayName)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = FinanceTheme.colors.onTile,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = Money.format(item.balanceMinor),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = FinanceTheme.colors.onTile,
            )
            Text(
                text = "Counted from ${Money.format(account.openingBalanceMinor)} on " +
                    DateUtils.format(account.openingBalanceDate) + ", plus everything since",
                style = MaterialTheme.typography.bodySmall,
                color = FinanceTheme.colors.onTile.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun StatementsCard(state: AccountDetailState, onUndo: (ImportBatchWithRemaining) -> Unit) {
    SectionCard(
        title = "Statements",
        subtitle = when {
            state.coverage.isEmpty() -> "None imported yet"
            state.missingMonths.isEmpty() -> "Every month in the last year is in"
            else -> "${state.missingMonths.size} " +
                (if (state.missingMonths.size == 1) "month" else "months") + " missing"
        },
    ) {
        if (state.coverage.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.coverage.forEach { cover -> MonthChip(cover) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (state.imports.isEmpty()) {
            Text(
                text = "Statements imported from now on are listed here, each with a way to take it back out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.imports.forEach { batch -> ImportRow(batch, onUndo = { onUndo(batch) }) }
    }
}

@Composable
private fun MonthChip(cover: MonthCover) {
    val colors = FinanceTheme.colors
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (cover.isIn) colors.tileSage else colors.tileBlush,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = DateUtils.monthNameShort(cover.month.monthValue),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onTile,
            )
            Text(
                text = if (cover.isIn) "✓" else "missing",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onTile,
            )
        }
    }
}

@Composable
private fun ImportRow(item: ImportBatchWithRemaining, onUndo: () -> Unit) {
    val batch = item.batch
    val when_ = Instant.ofEpochMilli(batch.importedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(batch.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    if (batch.firstDate != null && batch.lastDate != null) {
                        "${DateUtils.formatShort(batch.firstDate)} – ${DateUtils.formatShort(batch.lastDate)}"
                    } else {
                        null
                    },
                    "${batch.rowsAdded} added" + if (batch.rowsUpdated > 0) ", ${batch.rowsUpdated} updated" else "",
                    "imported ${DateUtils.formatShort(when_)}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUndo) { Text("Undo", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun MonthEndsCard(ends: List<MonthEnd>) {
    SectionCard(title = "Balance at each month end") {
        ends.forEach { end ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    text = DateUtils.formatMonth(end.month),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Money.format(end.balanceMinor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (end.balanceMinor < 0L) FinanceTheme.colors.negative else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PaymentRow(item: TransactionWithDetails, accountId: Long, onClick: () -> Unit) {
    val entry = item.transaction
    val colors = FinanceTheme.colors
    // A move into this account is money in here, whichever way it is stored.
    val isIn = entry.type == TransactionType.INCOME ||
        (entry.type == TransactionType.TRANSFER && entry.transferAccountId == accountId)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColorDot(colorFromHex(item.categoryColor), size = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.description, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(DateUtils.formatShort(entry.date), item.categoryName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = (if (isIn) "+" else "−") + Money.format(entry.amountMinor),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isIn) colors.income else colors.expense,
        )
    }
}
