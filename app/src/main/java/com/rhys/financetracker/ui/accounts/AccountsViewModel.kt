package com.rhys.financetracker.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The accounts list, grouped by person with running balances. */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val peopleRepository: PeopleRepository,
) : ViewModel() {

    private val showArchived = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AccountsState> = combine(
        accountRepository.observeAllWithBalances(),
        // Every person, not only the active ones. Grouping skipped anybody who
        // was not in this list, and their accounts went with them — archiving a
        // person silently hid accounts that still held money.
        peopleRepository.observeAll(),
        showArchived,
        message,
    ) { accounts, people, archived, text ->
        val visible = accounts.filter { archived || !it.account.isArchived }
        AccountsState(
            isLoading = false,
            groups = buildGroups(visible, people),
            // Offered as chips beside an account nobody owns, so saying whose
            // it is takes one tap from the list rather than a trip into the
            // account and back.
            people = people.filterNot { it.isArchived },
            showArchived = archived,
            totalAssetsMinor = visible.filterNot { it.isLiability }.sumOf { it.balanceMinor },
            totalLiabilitiesMinor = visible.filter { it.isLiability }.sumOf { it.balanceMinor },
            netWorthMinor = visible.sumOf { it.netWorthContributionMinor },
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsState())

    /**
     * Groups accounts under their owner, with shared accounts in their own
     * group at the end — the way a household actually thinks about them.
     */
    private fun buildGroups(
        accounts: List<AccountWithBalance>,
        people: List<PersonEntity>,
    ): List<AccountGroup> {
        val byPerson = accounts.groupBy { it.account.personId }
        val groups = people.mapNotNull { person ->
            byPerson[person.id]?.let { AccountGroup(person.name, person.colorHex, it) }
        }
        val unassigned = byPerson[null].orEmpty()
        return if (unassigned.isEmpty()) {
            groups
        } else {
            groups + AccountGroup("Not assigned", "#455A64", unassigned, isUnassigned = true)
        }
    }

    /** Puts an account under a person's name, from the list itself. */
    fun assign(accountId: Long, person: PersonEntity) {
        viewModelScope.launch {
            val name = accountRepository.get(accountId)?.name
            message.value = accountRepository.assignTo(accountId, person.id).errorMessageOrNull()
                ?: name?.let { "\"$it\" is now ${person.name}'s" }
        }
    }

    fun setShowArchived(show: Boolean) {
        showArchived.value = show
    }

    fun archive(id: Long, archived: Boolean) = act {
        accountRepository.setArchived(id, archived)
    }

    fun duplicate(id: Long) = act { accountRepository.duplicate(id) }

    fun delete(id: Long) {
        viewModelScope.launch {
            val account = accountRepository.get(id) ?: return@launch
            message.value = accountRepository.delete(account).errorMessageOrNull()
                ?: "\"${account.name}\" was deleted"
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

data class AccountGroup(
    val personName: String,
    val personColor: String,
    val accounts: List<AccountWithBalance>,
    /** True for the group of accounts nobody owns, which offers to fix that. */
    val isUnassigned: Boolean = false,
) {
    val totalMinor: Long get() = accounts.sumOf { it.balanceMinor }
}

data class AccountsState(
    val isLoading: Boolean = true,
    val groups: List<AccountGroup> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val showArchived: Boolean = false,
    val totalAssetsMinor: Long = 0L,
    val totalLiabilitiesMinor: Long = 0L,
    val netWorthMinor: Long = 0L,
    val message: String? = null,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/** Add or edit one account. */
@HiltViewModel
class AccountEditViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val peopleRepository: PeopleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val accountId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val form = MutableStateFlow(AccountForm())
    private val saved = MutableStateFlow(false)

    val state: StateFlow<AccountEditState> = combine(
        form,
        peopleRepository.observeActive(),
        saved,
    ) { currentForm, people, isSaved ->
        AccountEditState(
            isNew = accountId == Routes.NEW_ID,
            form = currentForm,
            people = people,
            isSaved = isSaved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountEditState())

    init {
        viewModelScope.launch {
            if (accountId == Routes.NEW_ID) {
                form.value = AccountForm(colorHex = DefaultData.PALETTE.random())
                return@launch
            }
            accountRepository.get(accountId)?.let { account ->
                form.value = AccountForm(
                    name = account.name,
                    type = account.type,
                    personId = account.personId,
                    openingBalanceText = Money.formatPlain(account.openingBalanceMinor),
                    openingBalanceDate = account.openingBalanceDate,
                    overdraftText = Money.formatPlain(account.overdraftLimitMinor),
                    lowBalanceText = account.lowBalanceThresholdMinor
                        ?.let { Money.formatPlain(it) }.orEmpty(),
                    creditLimitText = account.creditLimitMinor
                        ?.let { Money.formatPlain(it) }.orEmpty(),
                    interestRateText = account.interestRatePercent?.toString().orEmpty(),
                    colorHex = account.colorHex,
                    includeInNetWorth = account.includeInNetWorth,
                    countsAsSavings = account.countsAsSavings ?: account.type.isSavings,
                    isShared = account.isShared,
                    notes = account.notes.orEmpty(),
                )
            }
        }
    }

    fun update(transform: (AccountForm) -> AccountForm) {
        form.value = transform(form.value)
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val entity = AccountEntity(
                id = if (accountId == Routes.NEW_ID) 0L else accountId,
                name = current.name.trim(),
                type = current.type,
                personId = current.personId,
                openingBalanceMinor = Money.parseOrNull(current.openingBalanceText) ?: 0L,
                openingBalanceDate = current.openingBalanceDate,
                overdraftLimitMinor = Money.parseOrNull(current.overdraftText) ?: 0L,
                lowBalanceThresholdMinor = Money.parseOrNull(current.lowBalanceText),
                creditLimitMinor = Money.parseOrNull(current.creditLimitText),
                interestRatePercent = current.interestRateText.toDoubleOrNull(),
                colorHex = current.colorHex,
                includeInNetWorth = current.includeInNetWorth,
                // Only stored when it actually disagrees with the type, so
                // changing the type later still moves the account with it
                // rather than being quietly outvoted by a stale override.
                countsAsSavings = current.countsAsSavings
                    .takeIf { it != current.type.isSavings },
                isShared = current.isShared,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = accountRepository.save(entity)) {
                is AppResult.Success -> saved.value = true
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun clearError() {
        form.value = form.value.copy(errorSummary = null)
    }
}

data class AccountForm(
    val name: String = "",
    val type: AccountType = AccountType.CURRENT,
    val personId: Long? = null,
    val openingBalanceText: String = "",
    val openingBalanceDate: LocalDate = DateUtils.today(),
    val overdraftText: String = "",
    val lowBalanceText: String = "",
    val creditLimitText: String = "",
    val interestRateText: String = "",
    val colorHex: String = DefaultData.PALETTE.first(),
    val includeInNetWorth: Boolean = true,
    val countsAsSavings: Boolean = false,
    val isShared: Boolean = false,
    val notes: String = "",
    val errorSummary: String? = null,
)

data class AccountEditState(
    val isNew: Boolean = true,
    val form: AccountForm = AccountForm(),
    val people: List<PersonEntity> = emptyList(),
    val isSaved: Boolean = false,
)
