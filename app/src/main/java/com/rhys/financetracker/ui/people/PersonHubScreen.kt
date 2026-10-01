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
import com.rhys.financetracker.data.repository.PayeeRepository
import com.rhys.financetracker.data.repository.PeopleMoney
import com.rhys.financetracker.ui.spending.PeopleMoneyCard
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.AmountField
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
    val wage: com.rhys.financetracker.data.local.entity.RecurringRuleEntity? = null,
    val isBusy: Boolean = false,
) {
    val income: IncomeStats
        get() = IncomeStats(person?.grossYearlyIncomeMinor, person?.netYearlyIncomeMinor)
    /** Everything owed, as Home groups it: cards, pay later, loans and mortgages. */
    val loans: List<AccountWithBalance>
        get() = theirs.filter { it.isLiability }
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
    payeeRepository: PayeeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    /** Money sent to and from people this month, from this person's accounts. */
    val peopleMoney: StateFlow<PeopleMoney?> = DateUtils.monthRange(DateUtils.currentYearMonth()).let { month ->
        payeeRepository.observeMoneyWithPeople(month.start, month.endInclusive, setOf(personId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The same over this year, for the card's graph. */
    val peopleYear: StateFlow<PeopleMoney?> = DateUtils.currentYearMonth().year.let { year ->
        payeeRepository.observeMoneyWithPeople(
            java.time.LocalDate.of(year, 1, 1),
            java.time.LocalDate.of(year, 12, 31),
            setOf(personId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val drafts = MutableStateFlow(PersonHubState())
    private val message = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = message

    val state: StateFlow<PersonHubState> = combine(
        drafts,
        peopleRepository.observe(personId),
        accountRepository.observeWithBalances(),
        incomeRepository.observeHistory(personId),
        incomeRepository.observeWage(personId),
    ) { current, person, accounts, history, wage ->
        current.copy(
            person = person,
            theirs = accounts.filter { it.account.personId == personId },
            history = history,
            wage = wage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonHubState())

    /** Pays their take-home into [accountId] on [day] every month. */
    fun setWage(accountId: Long?, amountText: String, day: Int) {
        if (accountId == null) {
            message.value = "Choose the account it's paid into"
            return
        }
        val amount = Money.parseOrNull(amountText) ?: state.value.income.netMonthlyMinor
        viewModelScope.launch {
            message.value = when (val result = incomeRepository.setWage(personId, accountId, amount ?: 0L, day)) {
                is AppResult.Success -> "Wage set: ${Money.format(amount ?: 0L)} every month"
                is AppResult.Failure -> result.message
            }
        }
    }

    fun stopWage() {
        viewModelScope.launch {
            message.value = incomeRepository.stopWage(personId).errorMessageOrNull() ?: "Wage stopped"
        }
    }

    /** Records overtime or extra pay into the wage account. */
    fun addOvertime(amountText: String, date: java.time.LocalDate, note: String) {
        val amount = Money.parseOrNull(amountText)
        val accountId = state.value.wage?.accountId
            ?: state.value.accounts.firstOrNull { it.account.holding == Holding.SPEND }?.account?.id
        if (amount == null || amount <= 0L || accountId == null) {
            message.value = if (accountId == null) "Add an account first" else "Enter the amount"
            return
        }
        viewModelScope.launch {
            message.value = incomeRepository.addOvertime(personId, accountId, amount, date, note)
                .errorMessageOrNull() ?: "${Money.format(amount)} overtime added"
        }
    }

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
    onOpenPeopleMoney: (Long) -> Unit = {},
    viewModel: PersonHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val peopleMoney by viewModel.peopleMoney.collectAsStateWithLifecycle()
    val peopleYear by viewModel.peopleYear.collectAsStateWithLifecycle()
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

            // ------------------------------------------------------- people
            item {
                PeopleMoneyCard(
                    money = peopleMoney,
                    monthLabel = DateUtils.formatMonth(DateUtils.currentYearMonth()),
                    onSeeAll = { person?.let { onOpenPeopleMoney(it.id) } },
                    year = peopleYear,
                    yearNumber = DateUtils.currentYearMonth().year,
                )
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

            // --------------------------------------------------------- wage
            item {
                WageCard(
                    state = state,
                    onSetWage = viewModel::setWage,
                    onStopWage = viewModel::stopWage,
                    onAddOvertime = viewModel::addOvertime,
                )
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
                SectionCard(title = "Cards, loans and pay later", subtitle = "Loans go once they're paid off") {
                    if (state.loans.isEmpty()) {
                        Text(
                            text = "Nothing owed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.loans.forEach { loan ->
                        BalanceRow(
                            name = loan.account.name,
                            detail = "${Money.format((-loan.balanceMinor).coerceAtLeast(0L))} " +
                                if (loan.account.type == AccountType.CREDIT_CARD) "owed" else "left",
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

/**
 * Their wage, paid in by itself each month, and overtime on top.
 *
 * The wage lands on its day so the month is right before any statement
 * arrives; the statement's wage then replaces it at the bank's figure, and
 * any overtime typed in for that month is folded into it.
 */
@Composable
private fun WageCard(
    state: PersonHubState,
    onSetWage: (Long?, String, Int) -> Unit,
    onStopWage: () -> Unit,
    onAddOvertime: (String, java.time.LocalDate, String) -> Unit,
) {
    val spendAccounts = state.accounts.filter { it.account.holding == Holding.SPEND }
    val wage = state.wage
    var editing by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable(wage?.accountId) {
        mutableStateOf(wage?.accountId ?: spendAccounts.firstOrNull()?.account?.id)
    }
    var amountText by rememberSaveable(wage?.amountMinor) {
        mutableStateOf(
            (wage?.amountMinor ?: state.income.netMonthlyMinor)?.let { Money.formatPlain(it) }.orEmpty(),
        )
    }
    var day by rememberSaveable(wage?.startDate) { mutableStateOf(wage?.startDate?.dayOfMonth ?: 31) }
    var addingOvertime by rememberSaveable { mutableStateOf(false) }
    var overtimeText by rememberSaveable { mutableStateOf("") }
    var overtimeNote by rememberSaveable { mutableStateOf("") }
    var overtimeDate by remember { mutableStateOf(DateUtils.today()) }

    SectionCard(title = "Wage", subtitle = "Paid in each month") {
        if (wage != null && !editing) {
            val into = spendAccounts.firstOrNull { it.account.id == wage.accountId }?.account?.name
            PayLine("Each month", Money.format(wage.amountMinor))
            PayLine("Into", into ?: "an account")
            PayLine("On", if ((wage.startDate.dayOfMonth) >= 31) "the last day of the month" else "the ${wage.startDate.dayOfMonth}${ordinal(wage.startDate.dayOfMonth)}")
            PayLine("Next", DateUtils.format(wage.nextDueDate))
            Text(
                text = "It's added by itself on the day. When the statement comes, its wage " +
                    "replaces this one at the bank's figure — overtime and all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = true }) { Text("Change") }
                TextButton(onClick = onStopWage) { Text("Stop") }
            }
        } else if (spendAccounts.isEmpty()) {
            Text(
                text = "Add their current account below, then their wage can be paid into it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DropdownField(
                label = "Paid into",
                options = spendAccounts,
                selected = spendAccounts.firstOrNull { it.account.id == accountId },
                onSelect = { accountId = it.account.id },
                optionLabel = { it.account.name },
            )
            Spacer(Modifier.height(8.dp))
            AmountField(label = "Take-home each month", value = amountText, onValueChange = { amountText = it })
            Spacer(Modifier.height(8.dp))
            Text("Paid on", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(31, 28, 25, 20, 15, 1).forEach { option ->
                    FilterChip(
                        selected = day == option,
                        onClick = { day = option },
                        label = { Text(if (option == 31) "Last day" else "$option${ordinal(option)}") },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSetWage(accountId, amountText, day); editing = false },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (wage == null) "Pay it in every month" else "Save") }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        if (addingOvertime) {
            AmountField(label = "Overtime or extra pay", value = overtimeText, onValueChange = { overtimeText = it })
            Spacer(Modifier.height(8.dp))
            DateField(label = "Paid on", date = overtimeDate, onDateChange = { overtimeDate = it })
            Spacer(Modifier.height(8.dp))
            LabelledTextField(
                label = "What for? (optional)",
                value = overtimeNote,
                onValueChange = { overtimeNote = it },
                placeholder = "Overtime, bonus, extra shift…",
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { addingOvertime = false }) { Text("Cancel") }
                Button(
                    onClick = {
                        onAddOvertime(overtimeText, overtimeDate, overtimeNote)
                        overtimeText = ""
                        overtimeNote = ""
                        addingOvertime = false
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Add") }
            }
            Text(
                text = "Counted straight away. When the statement's wage arrives with it included, " +
                    "this is folded into it rather than counted twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            OutlinedButton(onClick = { addingOvertime = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add overtime or extra pay")
            }
        }
    }
}

private fun ordinal(day: Int): String = when {
    day in 11..13 -> "th"
    day % 10 == 1 -> "st"
    day % 10 == 2 -> "nd"
    day % 10 == 3 -> "rd"
    else -> "th"
}
