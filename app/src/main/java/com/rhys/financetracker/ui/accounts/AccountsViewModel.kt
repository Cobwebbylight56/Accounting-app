package com.rhys.financetracker.ui.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.AccountNaming
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
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
            availableMinor = visible.filterNot { it.isLiability || it.isSavings }.sumOf { it.balanceMinor },
            savedMinor = visible.filter { it.isSavings }.sumOf { it.balanceMinor },
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

    /**
     * Says where an account's money counts, straight from the list.
     *
     * One tap, because the accounts that need it are the ones already here,
     * guessed wrong — and "my saver is showing as available" is exactly this
     * one setting.
     */
    fun setHolding(account: AccountWithBalance, holding: Holding) {
        viewModelScope.launch {
            message.value = accountRepository.setHolding(account.account.id, holding)
                .errorMessageOrNull()
                ?: "\"${account.account.name}\" now counts as ${holding.displayName.lowercase()}"
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
    /** The same split as Home's tiles, so the two pages use the same words. */
    val availableMinor: Long = 0L,
    val savedMinor: Long = 0L,
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

    /** The name the account had when opened, so an unchanged one is not re-checked. */
    private var originalName: String? = null

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
                form.value = AccountForm(
                    name = AccountType.CURRENT.displayName,
                    colorHex = DefaultData.PALETTE.random(),
                )
                return@launch
            }
            accountRepository.get(accountId)?.let { account ->
                originalName = account.name
                form.value = AccountForm(
                    name = account.name,
                    type = account.type,
                    holding = account.holding,
                    // An existing account's settings are its owner's, so
                    // nothing is changed on their behalf as they edit.
                    nameTouched = true,
                    typeTouched = true,
                    holdingTouched = true,
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
                    isShared = account.isShared,
                    notes = account.notes.orEmpty(),
                )
            }
        }
    }

    fun update(transform: (AccountForm) -> AccountForm) {
        form.value = transform(form.value)
    }

    /**
     * The name, and — until the type has been chosen by hand — the type and
     * where it counts, read from it. Typing "Start to Save" sets the account
     * aside there and then, which is the step that used to be forgotten.
     */
    fun setName(name: String) {
        form.value = form.value.let { current ->
            val type = if (current.typeTouched) current.type else AccountNaming.typeFor(name)
            current.copy(
                name = name,
                nameTouched = true,
                type = type,
                holding = if (current.holdingTouched) current.holding else type.defaultHolding,
            )
        }
    }

    /** The type, and with it a suggested name and where it counts, unless those were set. */
    fun setType(type: AccountType) {
        form.value = form.value.let { current ->
            current.copy(
                type = type,
                typeTouched = true,
                name = if (current.nameTouched) current.name else type.displayName,
                holding = if (current.holdingTouched) current.holding else type.defaultHolding,
            )
        }
    }

    fun setHolding(holding: Holding) {
        form.value = form.value.copy(holding = holding, holdingTouched = true)
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val people = peopleRepository.activePeople()
            val name = current.name.trim()
            // "Account name: Rhys Evans, Belongs to: Rhys Evans" says the same
            // thing twice and nothing about the account. The owner is its own
            // field; the name is what the bank calls it.
            val isAPersonsName = people.any { it.name.equals(name, ignoreCase = true) } ||
                (current.newPersonName.isNotBlank() &&
                    name.equals(current.newPersonName.trim(), ignoreCase = true))
            if (name.isNotEmpty() && name != originalName && isAPersonsName) {
                form.value = current.copy(
                    errorSummary = "\"$name\" is a person's name. Call the account what the " +
                        "bank calls it — e.g. \"Nationwide current\" or \"Start to Save\" — " +
                        "and pick whose it is under Belongs to.",
                )
                return@launch
            }
            // A name typed here becomes a person, and the account goes under
            // it. Without this the account is saved owned by nobody, which is
            // invisible to every per-person view in the app. With only one
            // person there is nobody else it could be.
            val ownerId = current.personId
                ?: people.singleOrNull()?.id?.takeIf { current.newPersonName.isBlank() }
                ?: current.newPersonName.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { name ->
                    when (val made = peopleRepository.save(
                        PersonEntity(name = name, colorHex = DefaultData.PALETTE.random()),
                    )) {
                        is AppResult.Success -> made.data
                        is AppResult.Failure -> {
                            form.value = current.copy(errorSummary = made.message)
                            return@launch
                        }
                    }
                }
            val entity = AccountEntity(
                id = if (accountId == Routes.NEW_ID) 0L else accountId,
                name = name,
                type = current.type,
                holding = current.holding,
                personId = ownerId,
                openingBalanceMinor = Money.parseOrNull(current.openingBalanceText) ?: 0L,
                openingBalanceDate = current.openingBalanceDate,
                overdraftLimitMinor = Money.parseOrNull(current.overdraftText) ?: 0L,
                lowBalanceThresholdMinor = Money.parseOrNull(current.lowBalanceText),
                creditLimitMinor = Money.parseOrNull(current.creditLimitText),
                interestRatePercent = current.interestRateText.toDoubleOrNull(),
                colorHex = current.colorHex,
                includeInNetWorth = current.includeInNetWorth,
                isShared = current.isShared,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = accountRepository.save(entity)) {
                is AppResult.Success -> {
                    // The savings account, if it was asked for. Its own
                    // failure is reported rather than swallowed, but the
                    // account that did save is not rolled back for it.
                    val second = if (current.alsoCreateSavings && accountId == Routes.NEW_ID) {
                        accountRepository.save(
                            AccountEntity(
                                name = SAVINGS_ACCOUNT_NAME,
                                type = AccountType.SAVINGS,
                                personId = ownerId,
                                openingBalanceDate = current.openingBalanceDate,
                                colorHex = DefaultData.PALETTE.random(),
                            ),
                        )
                    } else {
                        null
                    }
                    val failed = (second as? AppResult.Failure)?.message
                    if (failed == null) {
                        saved.value = true
                    } else {
                        form.value = current.copy(errorSummary = failed)
                    }
                }
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun clearError() {
        form.value = form.value.copy(errorSummary = null)
    }

    private companion object {
        /** What the savings account made alongside a current one is called. */
        const val SAVINGS_ACCOUNT_NAME = "Savings"
    }
}

data class AccountForm(
    val name: String = "",
    val type: AccountType = AccountType.CURRENT,
    /** Where the money counts; suggested by the type and name until set by hand. */
    val holding: Holding = AccountType.CURRENT.defaultHolding,
    val personId: Long? = null,
    /** Set once the field has been changed by hand, after which it is left alone. */
    val nameTouched: Boolean = false,
    val typeTouched: Boolean = false,
    val holdingTouched: Boolean = false,

    /**
     * A person to create along with the account.
     *
     * Setting up meant adding a person, then an account, then going back to
     * put the one under the other — three screens to record one thing. Typing
     * the name here does all of it at once.
     */
    val newPersonName: String = "",

    /**
     * Whether to create a savings account for the same person at the same
     * time. Almost nobody has a current account and nothing else, and adding
     * the second one was another trip through the same form.
     */
    val alsoCreateSavings: Boolean = false,
    val openingBalanceText: String = "",
    val openingBalanceDate: LocalDate = DateUtils.today(),
    val overdraftText: String = "",
    val lowBalanceText: String = "",
    val creditLimitText: String = "",
    val interestRateText: String = "",
    val colorHex: String = DefaultData.PALETTE.first(),
    val includeInNetWorth: Boolean = true,
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
