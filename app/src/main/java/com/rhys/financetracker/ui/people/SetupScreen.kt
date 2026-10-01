package com.rhys.financetracker.ui.people

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.HouseholdSetupRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.ColorPicker
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The steps of setting a person up, in the order they are asked. */
enum class SetupStep(val title: String, val question: String) {
    NAME("Who is this?", "Start with a name. Everything else goes under it."),
    PAY("Their pay", "What they earn in a year, before and after tax. Skip it if you'd rather."),
    ACCOUNTS("Their accounts", "Current accounts, savings, cards — anything with money in it."),
    LOANS("Loans to pay", "Anything being paid off. Each one disappears when it's done."),
    DONE("All set", ""),
}

data class SetupState(
    val step: SetupStep = SetupStep.NAME,
    val personId: Long? = null,
    val name: String = "",
    val colorHex: String = DefaultData.PERSON_COLORS.first(),
    val grossText: String = "",
    val netText: String = "",
    val account: AccountDraft = AccountDraft(),
    val loan: LoanDraft = LoanDraft(),
    val theirs: List<AccountWithBalance> = emptyList(),
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val loans: List<AccountWithBalance>
        get() = theirs.filter { it.account.type == AccountType.LOAN || it.account.type == AccountType.MORTGAGE }
    val accounts: List<AccountWithBalance> get() = theirs - loans.toSet()
}

/**
 * Sets up one person from nothing: name, pay, accounts, loans.
 *
 * The person is made on the first step, so everything after goes straight
 * under their name — there is no moment where an account exists and belongs
 * to nobody.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SetupViewModel @Inject constructor(
    private val setup: HouseholdSetupRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val form = MutableStateFlow(SetupState())

    val state: StateFlow<SetupState> = combine(
        form,
        accountRepository.observeWithBalances(),
    ) { current, accounts ->
        current.copy(theirs = accounts.filter { it.account.personId == current.personId && current.personId != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SetupState())

    fun update(change: (SetupState) -> SetupState) {
        form.value = change(form.value).copy(error = null)
    }

    fun back() {
        val step = form.value.step
        if (step.ordinal > 0 && step != SetupStep.DONE) {
            form.value = form.value.copy(step = SetupStep.entries[step.ordinal - 1])
        }
    }

    fun next() {
        val current = form.value
        viewModelScope.launch {
            when (current.step) {
                SetupStep.NAME -> {
                    if (current.name.isBlank()) {
                        form.value = current.copy(error = "Enter a name")
                        return@launch
                    }
                    when (val made = setup.createPerson(current.name, current.colorHex)) {
                        is AppResult.Success -> form.value =
                            current.copy(personId = made.data, step = SetupStep.PAY)
                        is AppResult.Failure -> form.value = current.copy(error = made.message)
                    }
                }
                SetupStep.PAY -> {
                    val personId = current.personId ?: return@launch
                    val gross = Money.parseOrNull(current.grossText)
                    val net = Money.parseOrNull(current.netText)
                    if (gross != null && net != null && net > gross) {
                        form.value = current.copy(
                            error = "Take-home can't be more than pay before tax",
                        )
                        return@launch
                    }
                    if (gross != null || net != null) setup.setPay(personId, gross, net)
                    form.value = current.copy(step = SetupStep.ACCOUNTS)
                }
                SetupStep.ACCOUNTS -> form.value = current.copy(step = SetupStep.LOANS)
                SetupStep.LOANS -> form.value = current.copy(step = SetupStep.DONE)
                SetupStep.DONE -> Unit
            }
        }
    }

    fun addAccount() {
        val current = form.value
        val personId = current.personId ?: return
        val draft = current.account
        viewModelScope.launch {
            form.value = current.copy(isBusy = true)
            val result = setup.addAccount(
                personId = personId,
                type = draft.type,
                name = draft.name.ifBlank { draft.type.displayName },
                balanceMinor = Money.parseOrNull(draft.balanceText) ?: 0L,
            )
            form.value = form.value.copy(
                isBusy = false,
                account = if (result is AppResult.Success) AccountDraft() else draft,
                error = result.errorMessageOrNull(),
            )
        }
    }

    fun addLoan() {
        val current = form.value
        val personId = current.personId ?: return
        val draft = current.loan
        viewModelScope.launch {
            form.value = current.copy(isBusy = true)
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
            form.value = form.value.copy(
                isBusy = false,
                loan = if (result is AppResult.Success) LoanDraft() else draft,
                error = result.errorMessageOrNull(),
            )
        }
    }

    /** Starts again for the next person. */
    fun another() {
        form.value = SetupState(colorHex = DefaultData.PERSON_COLORS.random())
    }
}

/** Setting a person up, one question at a time. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onFinished: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The phone's back button does what the arrow does: one step back. It
    // used to leave setup altogether, halfway through and with the person
    // already made.
    androidx.activity.compose.BackHandler(
        enabled = state.step != SetupStep.NAME && state.step != SetupStep.DONE,
        onBack = viewModel::back,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.name.isBlank()) "Add a person" else state.name) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.step == SetupStep.NAME || state.step == SetupStep.DONE) {
                                onFinished()
                            } else {
                                viewModel.back()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinearProgressIndicator(
                progress = { (state.step.ordinal + 1f) / SetupStep.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = state.step.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.step.question.isNotEmpty()) {
                Text(
                    text = state.step.question,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let { ErrorBanner(message = it, onDismiss = { viewModel.update { s -> s } }) }

            when (state.step) {
                SetupStep.NAME -> {
                    LabelledTextField(
                        label = "Name",
                        value = state.name,
                        onValueChange = { name -> viewModel.update { it.copy(name = name) } },
                        placeholder = "e.g. Rhys Evans",
                    )
                    ColorPicker(
                        colors = DefaultData.PERSON_COLORS,
                        selected = state.colorHex,
                        onSelect = { hex -> viewModel.update { it.copy(colorHex = hex) } },
                    )
                    NextButton("Next", viewModel::next)
                }

                SetupStep.PAY -> {
                    AmountField(
                        label = "Yearly pay before tax",
                        value = state.grossText,
                        onValueChange = { text -> viewModel.update { it.copy(grossText = text) } },
                    )
                    AmountField(
                        label = "Yearly take-home after tax",
                        value = state.netText,
                        onValueChange = { text -> viewModel.update { it.copy(netText = text) } },
                    )
                    Text(
                        text = "Had a pay rise? Add it later on their page — it keeps a history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NextButton("Next", viewModel::next)
                }

                SetupStep.ACCOUNTS -> {
                    AddedList(state.accounts, emptyText = "No accounts yet.")
                    SectionCard(title = "Add an account") {
                        AccountDraftForm(
                            draft = state.account,
                            onChange = { draft -> viewModel.update { it.copy(account = draft) } },
                            onAdd = viewModel::addAccount,
                            enabled = !state.isBusy,
                        )
                    }
                    NextButton(if (state.accounts.isEmpty()) "Skip for now" else "Next", viewModel::next)
                }

                SetupStep.LOANS -> {
                    AddedList(state.loans, emptyText = "No loans. Lucky you.")
                    SectionCard(title = "Add a loan") {
                        LoanDraftForm(
                            draft = state.loan,
                            payFrom = state.accounts.filter { it.account.holding == Holding.SPEND },
                            onChange = { draft -> viewModel.update { it.copy(loan = draft) } },
                            onAdd = viewModel::addLoan,
                            enabled = !state.isBusy,
                        )
                    }
                    NextButton(if (state.loans.isEmpty()) "No loans — finish" else "Finish", viewModel::next)
                }

                SetupStep.DONE -> {
                    Text(
                        text = "${state.name} is set up with ${state.accounts.size} " +
                            (if (state.accounts.size == 1) "account" else "accounts") +
                            (if (state.loans.isEmpty()) "" else " and ${state.loans.size} to pay off") +
                            ". Their tab is on Home.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    NextButton("Go to Home", onFinished)
                    OutlinedButton(
                        onClick = viewModel::another,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Add another person") }
                }
            }
        }
    }
}

@Composable
private fun NextButton(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(label) }
}

@Composable
private fun AddedList(items: List<AccountWithBalance>, emptyText: String) {
    if (items.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item ->
            Row {
                Text(
                    text = "${item.account.name} · ${item.account.type.displayName}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(Money.format(item.balanceMinor), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
