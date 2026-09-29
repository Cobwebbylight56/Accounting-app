package com.rhys.financetracker.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.IncomeChangeEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.HouseholdSetupRepository
import com.rhys.financetracker.data.repository.IncomeRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.domain.income.IncomeStats
import com.rhys.financetracker.domain.income.PayRise
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.ui.components.ConfirmDialog
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.navigation.Routes
import com.rhys.financetracker.ui.theme.FinanceTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PersonHubState(
    val person: PersonEntity? = null,
    val theirs: List<AccountWithBalance> = emptyList(),
    val history: List<IncomeChangeEntity> = emptyList(),
    val account: AccountDraft = AccountDraft(),
    val loan: LoanDraft = LoanDraft(),
    val rise: PayRiseDraft = PayRiseDraft(),
    val isBusy: Boolean = false,
) {
    val income: IncomeStats
        get() = IncomeStats(person?.grossYearlyIncomeMinor, person?.netYearlyIncomeMinor)
    val loans: List<AccountWithBalance>
        get() = theirs.filter { it.account.type == AccountType.LOAN || it.account.type == AccountType.MORTGAGE }
    val accounts: List<AccountWithBalance> get() = theirs - loans.toSet()
}

/**
 * One person's page: their pay and how it has changed, the accounts that are
 * theirs, and what they are paying off. The place a person is looked after.
 */
@HiltViewModel
class PersonHubViewModel @Inject constructor(
    peopleRepository: PeopleRepository,
    accountRepository: AccountRepository,
    private val incomeRepository: IncomeRepository,
    private val setup: HouseholdSetupRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val drafts = MutableStateFlow(PersonHubState())
    private val message = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = message

    val state: StateFlow<PersonHubState> = combine(
        drafts,
        peopleRepository.observe(personId),
        accountRepository.observeWithBalances(),
        incomeRepository.observeHistory(personId),
    ) { current, person, accounts, history ->
        current.copy(
            person = person,
            theirs = accounts.filter { it.account.personId == personId },
            history = history,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonHubState())

    fun update(change: (PersonHubState) -> PersonHubState) {
        drafts.value = change(drafts.value)
    }

    fun clearMessage() {
        message.value = null
    }

    fun addPayRise(): Boolean {
        val current = state.value
        val draft = current.rise
        val given = draft.given.takeIf { current.income.grossYearlyMinor != null || it == PayRise.Given.NEW_PAY }
            ?: PayRise.Given.NEW_PAY
        val value = draft.valueText.replace(",", "").toDoubleOrNull()
        val newGross = value?.let { PayRise.newGross(current.income.grossYearlyMinor, given, it) }
        if (newGross == null) {
            message.value = "Enter the rise"
            return false
        }
        viewModelScope.launch {
            val result = incomeRepository.addChange(
                personId = personId,
                effectiveDate = draft.effectiveDate,
                newGrossMinor = newGross,
                newNetMinor = Money.parseOrNull(draft.newNetText),
                reason = draft.reason,
            )
            message.value = when (result) {
                is AppResult.Success -> {
                    drafts.value = drafts.value.copy(rise = PayRiseDraft())
                    val change = result.data
                    if (change.isApplied) {
                        "Pay is now ${Money.format(newGross)} a year"
                    } else {
                        "Saved — pay moves to ${Money.format(newGross)} on " +
                            DateUtils.format(change.effectiveDate)
                    }
                }
                is AppResult.Failure -> result.message
            }
        }
        return true
    }

    fun deletePayChange(change: IncomeChangeEntity) {
        viewModelScope.launch {
            message.value = incomeRepository.delete(change).errorMessageOrNull() ?: "Pay change removed"
        }
    }

    fun addAccount() {
        val draft = drafts.value.account
        viewModelScope.launch {
            val result = setup.addAccount(
                personId = personId,
                type = draft.type,
                name = draft.name.ifBlank { draft.type.displayName },
                balanceMinor = Money.parseOrNull(draft.balanceText) ?: 0L,
            )
            if (result is AppResult.Success) drafts.value = drafts.value.copy(account = AccountDraft())
            message.value = result.errorMessageOrNull() ?: "Account added"
        }
    }

    fun addLoan() {
        val draft = drafts.value.loan
        viewModelScope.launch {
            val result = setup.addLoan(
                personId = personId,
                name = draft.name,
                owedMinor = Money.parseOrNull(draft.owedText) ?: 0L,
                originalMinor = Money.parseOrNull(draft.originalText),
                monthlyPaymentMinor = Money.parseOrNull(draft.monthlyText),
                paymentDay = draft.dayText.toIntOrNull()?.coerceIn(1, 31) ?: 1,
                payFromAccountId = draft.payFromAccountId
                    ?: state.value.accounts.firstOrNull { it.account.holding == Holding.SPEND }?.account?.id,
            )
            if (result is AppResult.Success) drafts.value = drafts.value.copy(loan = LoanDraft())
            message.value = result.errorMessageOrNull() ?: "Loan added"
        }
    }

    fun clearBy(loan: AccountWithBalance, monthlyMinor: Long?) =
        setup.clearBy((-loan.balanceMinor).coerceAtLeast(0L), monthlyMinor)
}

private enum class Adding { NONE, RISE, ACCOUNT, LOAN }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonHubScreen(
    onBack: () -> Unit,
    onEditDetails: (Long) -> Unit,
    onOpenAccount: (Long) -> Unit,
    onImportStatement: (Long) -> Unit,
    viewModel: PersonHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(Adding.NONE) }
    var removing by remember { mutableStateOf<IncomeChangeEntity?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val person = state.person
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(person?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    person?.let {
                        IconButton(onClick = { onEditDetails(it.id) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit name and colour")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---------------------------------------------------------- pay
            item {
                SectionCard(title = "Pay", subtitle = "A year, and how it has changed") {
                    val income = state.income
                    if (!income.hasAny) {
                        Text(
                            text = "No pay recorded yet. Add it as a pay change below, or " +
                                "with the pencil at the top.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    income.grossYearlyMinor?.let { PayLine("Before tax", Money.format(it)) }
                    income.netYearlyMinor?.let { PayLine("Take-home", Money.format(it)) }
                    income.netMonthlyMinor?.let { PayLine("Take-home a month", Money.format(it)) }
                    income.deductionPercent?.let { PayLine("Tax and deductions", "$it%") }

                    if (state.history.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text("History", style = MaterialTheme.typography.titleSmall)
                        state.history.forEach { change ->
                            PayChangeRow(change, onRemove = { removing = change })
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    if (adding == Adding.RISE) {
                        PayRiseForm(
                            draft = state.rise,
                            currentGrossMinor = income.grossYearlyMinor,
                            onChange = { draft -> viewModel.update { it.copy(rise = draft) } },
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { adding = Adding.NONE }) { Text("Cancel") }
                            Button(
                                onClick = { if (viewModel.addPayRise()) adding = Adding.NONE },
                                modifier = Modifier.weight(1f),
                            ) { Text("Save pay change") }
                        }
                    } else {
                        Button(
                            onClick = { adding = Adding.RISE },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (income.hasAny) "Add a pay rise" else "Add their pay") }
                    }
                }
            }

            // --------------------------------------------------- statements
            item {
                SectionCard(title = "Statements", subtitle = "Checked against their name") {
                    Text(
                        text = "The name on the statement is checked against " +
                            "${person?.name ?: "theirs"}. If it's someone else's, you're " +
                            "told and can add it to theirs, or start a new person.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    person?.knownStatementNames?.takeIf { it.isNotEmpty() }?.let { names ->
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Also recognised as: ${names.joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { person?.let { onImportStatement(it.id) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Import a statement") }
                }
            }

            // ----------------------------------------------------- accounts
            item {
                SectionCard(title = "Accounts", subtitle = "What's theirs") {
                    if (state.accounts.isEmpty()) {
                        Text(
                            text = "No accounts yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.accounts.forEach { account ->
                        BalanceRow(
                            name = account.account.name,
                            detail = account.account.type.displayName + " · " +
                                account.account.holding.displayName,
                            amountMinor = account.balanceMinor,
                            colorHex = account.account.colorHex,
                            onClick = { onOpenAccount(account.account.id) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (adding == Adding.ACCOUNT) {
                        AccountDraftForm(
                            draft = state.account,
                            onChange = { draft -> viewModel.update { it.copy(account = draft) } },
                            onAdd = { viewModel.addAccount(); adding = Adding.NONE },
                        )
                        TextButton(onClick = { adding = Adding.NONE }) { Text("Cancel") }
                    } else {
                        OutlinedButton(
                            onClick = { adding = Adding.ACCOUNT },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add an account") }
                    }
                }
            }

            // -------------------------------------------------------- loans
            item {
                SectionCard(title = "Loans to pay", subtitle = "Each goes once it's paid off") {
                    if (state.loans.isEmpty()) {
                        Text(
                            text = "Nothing being paid off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.loans.forEach { loan ->
                        BalanceRow(
                            name = loan.account.name,
                            detail = "${Money.format((-loan.balanceMinor).coerceAtLeast(0L))} left",
                            amountMinor = loan.balanceMinor,
                            colorHex = loan.account.colorHex,
                            onClick = { onOpenAccount(loan.account.id) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (adding == Adding.LOAN) {
                        LoanDraftForm(
                            draft = state.loan,
                            payFrom = state.accounts.filter { it.account.holding == Holding.SPEND },
                            onChange = { draft -> viewModel.update { it.copy(loan = draft) } },
                            onAdd = { viewModel.addLoan(); adding = Adding.NONE },
                        )
                        TextButton(onClick = { adding = Adding.NONE }) { Text("Cancel") }
                    } else {
                        OutlinedButton(
                            onClick = { adding = Adding.LOAN },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Add a loan") }
                    }
                }
            }
        }
    }

    removing?.let { change ->
        ConfirmDialog(
            title = "Remove this pay change?",
            message = "If it's their current pay, their pay goes back to what it was before it.",
            confirmLabel = "Remove",
            isDestructive = true,
            onConfirm = { viewModel.deletePayChange(change) },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun PayLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** "Yearly review · 1 Apr 2026 — £27,455.76 → £29,000.00 (+5.6%)". */
@Composable
private fun PayChangeRow(change: IncomeChangeEntity, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = listOfNotNull(change.reason, DateUtils.format(change.effectiveDate))
                    .joinToString(" · ") + if (change.isApplied) "" else " (to come)",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = listOfNotNull(
                    change.previousGrossMinor?.let { Money.format(it) },
                    change.newGrossMinor?.let { Money.format(it) },
                ).joinToString(" → ") +
                    (change.grossChangePercent?.let { " (${if (it >= 0) "+" else ""}$it%)" } ?: "") +
                    (if (change.netIsEstimate) " · take-home estimated" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove this pay change")
        }
    }
}

@Composable
private fun BalanceRow(
    name: String,
    detail: String,
    amountMinor: Long,
    colorHex: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.rhys.financetracker.ui.components.ColorDot(colorFromHex(colorHex), size = 14.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = Money.format(amountMinor),
            style = MaterialTheme.typography.bodyLarge,
            color = if (amountMinor < 0L) FinanceTheme.colors.negative else MaterialTheme.colorScheme.onSurface,
        )
    }
}
