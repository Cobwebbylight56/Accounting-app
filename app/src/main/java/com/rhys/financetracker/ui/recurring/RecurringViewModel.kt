package com.rhys.financetracker.ui.recurring

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import com.rhys.financetracker.domain.recurrence.RecurringTransactionGenerator
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The regular income and bills that the app posts by itself.
 *
 * This screen is the heart of the "as little manual input as possible"
 * requirement: everything set up here happens without being asked again.
 */
@HiltViewModel
class RecurringViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
    private val generator: RecurringTransactionGenerator,
) : ViewModel() {

    private val typeFilter = MutableStateFlow<TransactionType?>(null)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<RecurringState> = combine(
        recurringRepository.observeAll(),
        recurringRepository.observeOverdue(),
        typeFilter,
        message,
    ) { rules, overdue, filter, text ->
        val visible = rules.filter { filter == null || it.rule.type == filter }
        RecurringState(
            isLoading = false,
            rules = visible,
            overdueIds = overdue.map { it.rule.id }.toSet(),
            typeFilter = filter,
            monthlyIncomeMinor = rules
                .filter { it.rule.type == TransactionType.INCOME && !it.rule.isPaused }
                .sumOf { monthlyEquivalent(it.rule) },
            monthlyExpenseMinor = rules
                .filter { it.rule.type == TransactionType.EXPENSE && !it.rule.isPaused }
                .sumOf { monthlyEquivalent(it.rule) },
            monthlySavingsMinor = rules
                .filter { it.rule.type == TransactionType.TRANSFER && !it.rule.isPaused }
                .sumOf { monthlyEquivalent(it.rule) },
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringState())

    private fun monthlyEquivalent(rule: RecurringRuleEntity): Long =
        RecurrenceCalculator.monthlyEquivalentMinor(
            rule.amountMinor,
            rule.frequency,
            rule.interval,
        )

    fun setTypeFilter(type: TransactionType?) {
        typeFilter.value = type
    }

    fun setPaused(id: Long, paused: Boolean) = act {
        recurringRepository.setPaused(id, paused)
    }

    fun duplicate(id: Long) = act { recurringRepository.duplicate(id) }

    fun archive(id: Long, archived: Boolean) = act {
        recurringRepository.setArchived(id, archived)
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val rule = recurringRepository.get(id) ?: return@launch
            message.value = recurringRepository.delete(rule).errorMessageOrNull()
        }
    }

    /** Posts anything that is due right now, rather than waiting for the daily job. */
    fun catchUpNow() {
        viewModelScope.launch {
            when (val result = generator.generateDue()) {
                is AppResult.Success -> {
                    val created = result.data.transactionsCreated
                    message.value = if (created == 0) {
                        "Everything is already up to date"
                    } else {
                        "$created ${if (created == 1) "entry" else "entries"} added"
                    }
                }
                is AppResult.Failure -> message.value = result.message
            }
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

data class RecurringState(
    val isLoading: Boolean = true,
    val rules: List<RecurringRuleWithDetails> = emptyList(),
    val overdueIds: Set<Long> = emptySet(),
    val typeFilter: TransactionType? = null,
    val monthlyIncomeMinor: Long = 0L,
    val monthlyExpenseMinor: Long = 0L,
    val monthlySavingsMinor: Long = 0L,
    val message: String? = null,
) {
    val monthlyLeftOverMinor: Long
        get() = monthlyIncomeMinor - monthlyExpenseMinor - monthlySavingsMinor
}

/** Add or edit one recurring rule. */
@HiltViewModel
class RecurringEditViewModel @Inject constructor(
    private val recurringRepository: RecurringRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val peopleRepository: PeopleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val ruleId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val form = MutableStateFlow(RecurringForm())

    val state: StateFlow<RecurringEditState> = combine(
        form,
        accountRepository.observeActive(),
        categoryRepository.observeAll(),
        peopleRepository.observeActive(),
    ) { currentForm, accounts, categories, people ->
        RecurringEditState(
            isNew = ruleId == Routes.NEW_ID,
            form = currentForm,
            accounts = accounts,
            categories = categories.filterNot { it.isArchived }.filter { category ->
                when (currentForm.type) {
                    TransactionType.INCOME -> category.kind == CategoryKind.INCOME
                    TransactionType.EXPENSE -> category.kind == CategoryKind.EXPENSE
                    TransactionType.TRANSFER ->
                        category.kind == CategoryKind.SAVING ||
                            category.kind == CategoryKind.TRANSFER
                }
            },
            people = people,
            // A live preview of the next few dates, so the schedule can be
            // checked before it is saved.
            upcomingDates = previewDates(currentForm),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringEditState())

    init {
        viewModelScope.launch {
            if (ruleId == Routes.NEW_ID) {
                form.value = RecurringForm(startDate = DateUtils.today())
                return@launch
            }
            recurringRepository.get(ruleId)?.let { rule ->
                form.value = RecurringForm(
                    name = rule.name,
                    amountText = Money.formatPlain(rule.amountMinor),
                    type = rule.type,
                    frequency = rule.frequency,
                    intervalText = rule.interval.toString(),
                    startDate = rule.startDate,
                    endDate = rule.endDate,
                    accountId = rule.accountId,
                    transferAccountId = rule.transferAccountId,
                    categoryId = rule.categoryId,
                    personId = rule.personId,
                    mode = rule.mode,
                    reminderDaysText = rule.reminderDaysBefore?.toString().orEmpty(),
                    isVariableAmount = rule.isVariableAmount,
                    isPaused = rule.isPaused,
                    notes = rule.notes.orEmpty(),
                    nextDueDate = rule.nextDueDate,
                    occurrencesGenerated = rule.occurrencesGenerated,
                )
            }
        }
    }

    /**
     * The next few dates this rule would fall on.  Built from a throwaway
     * entity so the preview costs nothing and touches no storage.
     */
    private fun previewDates(current: RecurringForm): List<LocalDate> {
        val probe = RecurringRuleEntity(
            name = current.name.ifBlank { "Preview" },
            amountMinor = 1L,
            type = current.type,
            frequency = current.frequency,
            interval = current.intervalText.toIntOrNull() ?: 1,
            startDate = current.startDate,
            endDate = current.endDate,
            nextDueDate = current.startDate,
            accountId = current.accountId ?: 0L,
        )
        return RecurrenceCalculator.upcomingOccurrences(
            rule = probe,
            from = current.startDate,
            to = current.startDate.plusYears(2),
            limit = 5,
        )
    }

    fun update(transform: (RecurringForm) -> RecurringForm) {
        form.value = transform(form.value)
    }

    fun setType(type: TransactionType) {
        form.value = form.value.copy(
            type = type,
            categoryId = null,
            transferAccountId = if (type == TransactionType.TRANSFER) {
                form.value.transferAccountId
            } else {
                null
            },
        )
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val entity = RecurringRuleEntity(
                id = if (ruleId == Routes.NEW_ID) 0L else ruleId,
                name = current.name.trim(),
                amountMinor = Money.parseOrNull(current.amountText) ?: 0L,
                type = current.type,
                frequency = current.frequency,
                interval = current.intervalText.toIntOrNull() ?: 1,
                startDate = current.startDate,
                endDate = current.endDate,
                nextDueDate = current.nextDueDate ?: current.startDate,
                occurrencesGenerated = current.occurrencesGenerated,
                accountId = current.accountId ?: 0L,
                transferAccountId = current.transferAccountId,
                categoryId = current.categoryId,
                personId = current.personId,
                mode = current.mode,
                reminderDaysBefore = current.reminderDaysText.toIntOrNull(),
                isVariableAmount = current.isVariableAmount,
                isPaused = current.isPaused,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
            )

            if (entity.accountId == 0L) {
                form.value = current.copy(errorSummary = "Choose an account")
                return@launch
            }

            when (val result = recurringRepository.save(entity)) {
                is AppResult.Success -> form.value = current.copy(isSaved = true)
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun clearError() {
        form.value = form.value.copy(errorSummary = null)
    }
}

data class RecurringForm(
    val name: String = "",
    val amountText: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val frequency: Frequency = Frequency.MONTHLY,
    val intervalText: String = "1",
    val startDate: LocalDate = DateUtils.today(),
    val endDate: LocalDate? = null,
    val accountId: Long? = null,
    val transferAccountId: Long? = null,
    val categoryId: Long? = null,
    val personId: Long? = null,
    val mode: RecurrenceMode = RecurrenceMode.AUTO_POST,
    val reminderDaysText: String = "3",
    val isVariableAmount: Boolean = false,
    val isPaused: Boolean = false,
    val notes: String = "",
    val nextDueDate: LocalDate? = null,
    val occurrencesGenerated: Int = 0,
    val isSaved: Boolean = false,
    val errorSummary: String? = null,
)

data class RecurringEditState(
    val isNew: Boolean = true,
    val form: RecurringForm = RecurringForm(),
    val accounts: List<AccountEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val upcomingDates: List<LocalDate> = emptyList(),
)
