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
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.ImportBatchWithRemaining
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.ImportHistoryRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.loan.LoanMaths
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.LineSeries
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.TrendLineChart
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
    /** For a loan or mortgage: what goes into it each month, from its regular payment or lately. */
    val monthlyPaymentMinor: Long? = null,
    val message: String? = null,
) {
    /** True for borrowing that is paid off month by month. */
    val isLoan: Boolean get() = account?.account?.type in LOAN_TYPES
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
    private val transactionDao: TransactionDao,
    private val importHistory: ImportHistoryRepository,
    private val ledgerRequests: LedgerRequests,
    private val recurringRuleDao: RecurringRuleDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val accountId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val message = MutableStateFlow<String?>(null)

    private val account = accountRepository.observeAllWithBalances()
        .map { list -> list.firstOrNull { it.account.id == accountId } }

    /** The last twelve months' closing balances, worked out whenever the balance moves. */
    private val monthEnds = account.mapLatest { current ->
        if (current == null) return@mapLatest emptyList<MonthEnd>()
        val thisMonth = DateUtils.currentYearMonth()
        (1..MONTH_ENDS).map { back ->
            val month = thisMonth.minusMonths(back.toLong())
            MonthEnd(month, accountRepository.balanceAsOf(accountId, month.atEndOfMonth()))
        }
    }

    /**
     * For a loan: the regular payment set up into it, or else the average of
     * what went into it over the last three months.
     */
    private val loanPayment = account.mapLatest { current ->
        if (current == null || current.account.type !in LOAN_TYPES) return@mapLatest null
        val planned = recurringRuleDao.getAllActive()
            .filter { it.transferAccountId == accountId }
            .sumOf { it.amountMinor }
        if (planned > 0L) return@mapLatest planned
        val thisMonth = DateUtils.currentYearMonth()
        val paid = (1..3).map { back ->
            val month = thisMonth.minusMonths(back.toLong())
            transactionDao.getTransfersIn(accountId, month.atDay(1), month.atEndOfMonth())
        }
        (paid.sum() / 3).takeIf { it > 0L }
    }

    val state: StateFlow<AccountDetailState> = combine(
        account,
        transactionRepository.search(TransactionFilter(accountIds = setOf(accountId), limit = RECENT)),
        importHistory.observeForAccount(accountId),
        combine(transactionDao.observeStatementMonths(accountId), monthEnds, loanPayment) { months, ends, payment ->
            Triple(months, ends, payment)
        },
        message,
    ) { current, recent, imports, monthsAndEnds, text ->
        val (months, ends, payment) = monthsAndEnds
        AccountDetailState(
            isLoading = false,
            account = current,
            recent = recent,
            imports = imports,
            coverage = coverageOf(months.mapNotNull { DateUtils.parseYearMonthKey(it) }),
            monthEnds = ends,
            monthlyPaymentMinor = payment,
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
        private const val MONTH_ENDS = 12
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

            if (state.isLoan) {
                item {
                    PayingItOffCard(
                        account = account,
                        monthlyMinor = state.monthlyPaymentMinor,
                        onEdit = { onEdit(account.account.id) },
                    )
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
                text = com.rhys.financetracker.ui.components.animatedMoney(item.balanceMinor),
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
        val oldestFirst = ends.sortedBy { it.month }
        TrendLineChart(
            labels = oldestFirst.map { DateUtils.monthNameShort(it.month.monthValue) },
            series = listOf(
                LineSeries(
                    name = "Balance",
                    values = oldestFirst.map { it.balanceMinor },
                    color = MaterialTheme.colorScheme.primary,
                ),
            ),
            height = 180.dp,
        )
        Spacer(Modifier.height(8.dp))
        ends.take(MONTH_ENDS_LISTED).forEach { end ->
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

/** The graph covers the year; the list under it, the latest few. */
private const val MONTH_ENDS_LISTED = 6

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
                text = listOfNotNull(DateUtils.formatShort(entry.date), item.regularKind?.shortName, item.categoryName).joinToString(" · "),
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

/** Loans and mortgages: borrowing paid off month by month. */
private val LOAN_TYPES = setOf(
    AccountType.LOAN,
    AccountType.MORTGAGE,
)

/**
 * A loan or mortgage being paid off: when it will be clear, what the
 * interest still comes to, a graph of the balance coming down, and what
 * paying a little more each month would save.
 */
@Composable
private fun PayingItOffCard(account: AccountWithBalance, monthlyMinor: Long?, onEdit: () -> Unit) {
    val owed = (-account.balanceMinor).coerceAtLeast(0L)
    val rate = account.account.interestRatePercent
    SectionCard(title = "Paying it off") {
        if (owed == 0L) {
            Text("Nothing left to pay.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            LoanFigure("Left to pay", Money.format(owed), Modifier.weight(1f))
            LoanFigure("Interest rate", rate?.let { "${trimRate(it)}% a year" } ?: "Not set", Modifier.weight(1f))
            LoanFigure("Each month", monthlyMinor?.let { Money.format(it) } ?: "Not set", Modifier.weight(1f))
        }
        if (rate == null || monthlyMinor == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    rate == null && monthlyMinor == null ->
                        "Add the interest rate, and set up the monthly payment under Regular payments, to see when it will be clear."
                    rate == null -> "Add the interest rate to count interest in the dates below."
                    else -> "Set up the monthly payment under Regular payments to see when it will be clear."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rate == null) TextButton(onClick = onEdit) { Text("Add the interest rate") }
        }
        val monthly = monthlyMinor ?: return@SectionCard
        val r = rate ?: 0.0
        val months = LoanMaths.monthsToClear(owed, r, monthly)
        Spacer(Modifier.height(10.dp))
        if (months == null) {
            Text(
                text = "${Money.format(monthly)} a month doesn't cover the interest " +
                    "(${Money.format(LoanMaths.interestThisMonth(owed, r))}) — " +
                    "at this rate it is never paid off.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            return@SectionCard
        }
        val clear = DateUtils.currentYearMonth().plusMonths(months.toLong())
        Text(
            text = "Clear by ${DateUtils.formatMonth(clear)}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${months / 12} years ${months % 12} months left" +
                if (r > 0.0) {
                    " · ${Money.format(LoanMaths.interestToPay(owed, r, monthly) ?: 0L)} interest still to pay"
                } else {
                    ""
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r > 0.0) {
            val interest = LoanMaths.interestThisMonth(owed, r)
            Text(
                text = "Of this month's ${Money.format(monthly)}, ${Money.format(interest)} is interest and " +
                    "${Money.format((monthly - interest).coerceAtLeast(0L))} comes off what's owed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The balance coming down: by month for a short loan, by year for a long one.
        val plan = LoanMaths.schedule(owed, r, monthly)
        if (plan.size >= 2) {
            val step = if (plan.size > 36) 12 else 1
            val points = listOf(owed) + plan.filterIndexed { i, _ -> (i + 1) % step == 0 || i == plan.lastIndex }.map { it.owedAfterMinor }
            val labels = points.indices.map { i ->
                val month = DateUtils.currentYearMonth().plusMonths((i * step).toLong().coerceAtMost(months.toLong()))
                if (step == 12) month.year.toString().takeLast(2).let { "’$it" } else DateUtils.monthNameShort(month.monthValue)
            }
            Spacer(Modifier.height(10.dp))
            TrendLineChart(
                labels = labels,
                series = listOf(
                    LineSeries(
                        "Left to pay",
                        points,
                        FinanceTheme.colors.chartOut,
                    ),
                ),
                height = 170.dp,
            )
        }

        // What paying a bit more each month would do.
        Spacer(Modifier.height(10.dp))
        Text("Pay more each month?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        var extra by rememberSaveable { mutableStateOf(0L) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OVERPAYMENTS.forEach { amount ->
                FilterChip(
                    selected = extra == amount,
                    onClick = { extra = if (extra == amount) 0L else amount },
                    label = { Text("+${Money.formatCompact(amount)}") },
                )
            }
        }
        if (extra > 0L) {
            val result = LoanMaths.overpay(owed, r, monthly, extra)
            if (result != null) {
                val sooner = DateUtils.currentYearMonth().plusMonths((months - result.monthsSooner).toLong())
                Text(
                    text = "Paying ${Money.format(monthly + extra)} a month clears it by ${DateUtils.formatMonth(sooner)} — " +
                        "${result.monthsSooner} months sooner" +
                        (if (result.interestSavedMinor > 0L) ", saving ${Money.format(result.interestSavedMinor)} in interest." else "."),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FinanceTheme.colors.positive,
                )
            }
        }
    }
}

@Composable
private fun LoanFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** 4.5 rather than 4.50 or 4.500000001. */
private fun trimRate(rate: Double): String =
    java.math.BigDecimal.valueOf(rate).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Overpayments offered, in pence. */
private val OVERPAYMENTS = listOf(2_500L, 5_000L, 10_000L, 25_000L)
