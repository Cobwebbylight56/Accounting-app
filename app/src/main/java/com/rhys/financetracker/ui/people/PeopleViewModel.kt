package com.rhys.financetracker.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.income.IncomeStats
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val peopleRepository: PeopleRepository,
    accountRepository: AccountRepository,
    transactionRepository: TransactionRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    private val month = DateUtils.monthRange(DateUtils.currentYearMonth())

    val state: StateFlow<PeopleState> = combine(
        peopleRepository.observeAll(),
        accountRepository.observeActive(),
        transactionRepository.observePersonTotals(month.start, month.endInclusive),
        transactionRepository.observePotFlowByPerson(
            CategoryKind.SAVING, month.start, month.endInclusive,
        ),
        message,
    ) { people, accounts, totals, savings, text ->
        val spentBy = totals.associate { it.personId to it.expenseMinor }
        val savedBy = savings.associate { it.personId to it.netMinor }
        val summaries = people.map { person ->
            PersonSummary(
                person = person,
                accountCount = accounts.count { it.personId == person.id },
                income = IncomeStats(person.grossYearlyIncomeMinor, person.netYearlyIncomeMinor),
                spentThisMonthMinor = spentBy[person.id] ?: 0L,
                savedThisMonthMinor = savedBy[person.id] ?: 0L,
            )
        }
        PeopleState(
            isLoading = false,
            people = summaries,
            household = summaries.filterNot { it.person.isArchived }
                .fold(IncomeStats.NONE) { total, item -> total + item.income },
            householdSpentMinor = totals.sumOf { it.expenseMinor },
            householdSavedMinor = savings.sumOf { it.netMinor },
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeopleState())

    fun archive(id: Long, archived: Boolean) = act { peopleRepository.setArchived(id, archived) }

    fun duplicate(id: Long) = act { peopleRepository.duplicate(id) }

    fun delete(id: Long) {
        viewModelScope.launch {
            val person = peopleRepository.get(id) ?: return@launch
            message.value = peopleRepository.delete(person).errorMessageOrNull()
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

data class PersonSummary(
    val person: PersonEntity,
    val accountCount: Int,
    val income: IncomeStats = IncomeStats.NONE,
    /** Spending this month, savings left out. */
    val spentThisMonthMinor: Long = 0L,
    /** Put aside this month from their spending accounts, less what came back. */
    val savedThisMonthMinor: Long = 0L,
)

data class PeopleState(
    val isLoading: Boolean = true,
    val people: List<PersonSummary> = emptyList(),
    /** Everybody's yearly pay added together. */
    val household: IncomeStats = IncomeStats.NONE,
    val householdSpentMinor: Long = 0L,
    val householdSavedMinor: Long = 0L,
    val message: String? = null,
)

@HiltViewModel
class PersonEditViewModel @Inject constructor(
    private val peopleRepository: PeopleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val _state = MutableStateFlow(
        PersonEditState(
            isNew = personId == Routes.NEW_ID,
            colorHex = DefaultData.PERSON_COLORS.random(),
        ),
    )
    val state: StateFlow<PersonEditState> = _state

    init {
        if (personId != Routes.NEW_ID) {
            viewModelScope.launch {
                peopleRepository.get(personId)?.let { person ->
                    _state.value = _state.value.copy(
                        name = person.name,
                        colorHex = person.colorHex,
                        notes = person.notes.orEmpty(),
                        isShared = person.isShared,
                        sortOrder = person.sortOrder,
                        grossYearlyText = person.grossYearlyIncomeMinor
                            ?.let { Money.formatPlain(it) }.orEmpty(),
                        netYearlyText = person.netYearlyIncomeMinor
                            ?.let { Money.formatPlain(it) }.orEmpty(),
                    )
                }
            }
        }
    }

    fun setName(value: String) {
        _state.value = _state.value.copy(name = value)
    }

    fun setColor(value: String) {
        _state.value = _state.value.copy(colorHex = value)
    }

    fun setNotes(value: String) {
        _state.value = _state.value.copy(notes = value)
    }

    fun setGrossYearly(value: String) {
        _state.value = _state.value.copy(grossYearlyText = value)
    }

    fun setNetYearly(value: String) {
        _state.value = _state.value.copy(netYearlyText = value)
    }

    fun clearError() {
        _state.value = _state.value.copy(errorSummary = null)
    }

    fun save() {
        viewModelScope.launch {
            val current = _state.value
            val gross = Money.parseOrNull(current.grossYearlyText)
            val net = Money.parseOrNull(current.netYearlyText)
            if (gross != null && net != null && net > gross) {
                _state.value = current.copy(
                    errorSummary = "Take-home can't be more than pay before tax — are the " +
                        "two the right way round?",
                )
                return@launch
            }
            // Starting from what is stored keeps everything this form does
            // not show — the names learned from statements, when they were
            // added — rather than quietly wiping it on every save.
            val stored = if (personId == Routes.NEW_ID) null else peopleRepository.get(personId)
            val entity = (stored ?: PersonEntity(name = "", colorHex = current.colorHex)).copy(
                id = if (personId == Routes.NEW_ID) 0L else personId,
                name = current.name.trim(),
                colorHex = current.colorHex,
                isShared = current.isShared,
                sortOrder = current.sortOrder,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
                grossYearlyIncomeMinor = gross?.takeIf { it > 0L },
                netYearlyIncomeMinor = net?.takeIf { it > 0L },
            )
            when (val result = peopleRepository.save(entity)) {
                is AppResult.Success -> _state.value = current.copy(isSaved = true)
                is AppResult.Failure ->
                    _state.value = current.copy(errorSummary = result.message)
            }
        }
    }
}

data class PersonEditState(
    val isNew: Boolean = true,
    val name: String = "",
    val colorHex: String = "#1565C0",
    val notes: String = "",
    val isShared: Boolean = false,
    val sortOrder: Int = 0,
    /** Yearly pay before tax, as typed. */
    val grossYearlyText: String = "",
    /** Yearly take-home after tax, as typed. */
    val netYearlyText: String = "",
    val isSaved: Boolean = false,
    val errorSummary: String? = null,
)
