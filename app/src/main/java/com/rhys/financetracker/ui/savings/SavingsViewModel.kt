package com.rhys.financetracker.ui.savings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.SavingsProjection
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Savings goals and the savings accounts behind them. */
@HiltViewModel
class SavingsViewModel @Inject constructor(
    private val savingsRepository: SavingsRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SavingsState> = combine(
        savingsRepository.observeWithProgress(),
        accountRepository.observeWithBalances().map { accounts -> accounts.filter { it.isSavings } },
        message,
    ) { goals, savingsAccounts, text ->
        SavingsState(
            isLoading = false,
            goals = goals.map { goal ->
                GoalSummary(
                    goal = goal,
                    requiredMonthlyMinor = SavingsProjection.requiredMonthlyMinor(goal),
                    projectedCompletion = SavingsProjection.projectedCompletion(goal),
                    isBehind = SavingsProjection.isBehindSchedule(goal),
                )
            },
            totalSavedMinor = savingsAccounts.sumOf { it.balanceMinor },
            totalTargetMinor = goals.sumOf { it.goal.targetAmountMinor },
            totalInGoalsMinor = goals.sumOf { it.currentAmountMinor },
            monthlyContributionMinor = goals.sumOf { it.goal.monthlyContributionMinor },
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavingsState())

    fun archive(id: Long, archived: Boolean) = act { savingsRepository.setArchived(id, archived) }

    fun duplicate(id: Long) = act { savingsRepository.duplicate(id) }

    fun addToGoal(id: Long, amountText: String) {
        val amount = Money.parseOrNull(amountText)
        if (amount == null) {
            message.value = "Enter a valid amount"
            return
        }
        act { savingsRepository.adjustBalance(id, amount) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val goal = savingsRepository.get(id) ?: return@launch
            message.value = savingsRepository.delete(goal).errorMessageOrNull()
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private fun act(block: suspend () -> AppResult<*>) {
        viewModelScope.launch { message.value = block().errorMessageOrNull() }
    }
}

/** A goal with the arithmetic the screen needs already done. */
data class GoalSummary(
    val goal: SavingsGoalWithProgress,
    val requiredMonthlyMinor: Long?,
    val projectedCompletion: LocalDate?,
    val isBehind: Boolean,
)

data class SavingsState(
    val isLoading: Boolean = true,
    val goals: List<GoalSummary> = emptyList(),
    val totalSavedMinor: Long = 0L,
    val totalTargetMinor: Long = 0L,
    val totalInGoalsMinor: Long = 0L,
    val monthlyContributionMinor: Long = 0L,
    val message: String? = null,
) {
    val overallProgress: Float
        get() = if (totalTargetMinor <= 0L) {
            0f
        } else {
            (totalInGoalsMinor.toDouble() / totalTargetMinor).coerceIn(0.0, 1.0).toFloat()
        }
}

/** Add or edit one savings goal. */
@HiltViewModel
class SavingsEditViewModel @Inject constructor(
    private val savingsRepository: SavingsRepository,
    accountRepository: AccountRepository,
    peopleRepository: PeopleRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val goalId: Long =
        savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull() ?: Routes.NEW_ID

    private val form = MutableStateFlow(SavingsForm(colorHex = DefaultData.PALETTE.random()))

    val state: StateFlow<SavingsEditState> = combine(
        form,
        accountRepository.observeActiveOptions(),
        peopleRepository.observeActive(),
    ) { currentForm, accounts, people ->
        SavingsEditState(
            isNew = goalId == Routes.NEW_ID,
            form = currentForm,
            accounts = accounts,
            people = people,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavingsEditState())

    init {
        if (goalId != Routes.NEW_ID) {
            viewModelScope.launch {
                savingsRepository.get(goalId)?.let { goal ->
                    form.value = SavingsForm(
                        name = goal.name,
                        targetText = Money.formatPlain(goal.targetAmountMinor),
                        startingText = Money.formatPlain(goal.manualAdjustmentMinor),
                        monthlyText = Money.formatPlain(goal.monthlyContributionMinor),
                        targetDate = goal.targetDate,
                        startDate = goal.startDate,
                        accountId = goal.accountId,
                        personId = goal.personId,
                        colorHex = goal.colorHex,
                        notes = goal.notes.orEmpty(),
                        sortOrder = goal.sortOrder,
                    )
                }
            }
        }
    }

    fun update(transform: (SavingsForm) -> SavingsForm) {
        form.value = transform(form.value)
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val entity = SavingsGoalEntity(
                id = if (goalId == Routes.NEW_ID) 0L else goalId,
                name = current.name.trim(),
                targetAmountMinor = Money.parseOrNull(current.targetText) ?: 0L,
                manualAdjustmentMinor = Money.parseOrNull(current.startingText) ?: 0L,
                monthlyContributionMinor = Money.parseOrNull(current.monthlyText) ?: 0L,
                targetDate = current.targetDate,
                startDate = current.startDate,
                accountId = current.accountId,
                personId = current.personId,
                colorHex = current.colorHex,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
                sortOrder = current.sortOrder,
            )
            when (val result = savingsRepository.save(entity)) {
                is AppResult.Success -> form.value = current.copy(isSaved = true)
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun clearError() {
        form.value = form.value.copy(errorSummary = null)
    }
}

data class SavingsForm(
    val name: String = "",
    val targetText: String = "",
    val startingText: String = "",
    val monthlyText: String = "",
    val targetDate: LocalDate? = null,
    val startDate: LocalDate = DateUtils.today(),
    val accountId: Long? = null,
    val personId: Long? = null,
    val colorHex: String = "#1B5E4B",
    val notes: String = "",
    val sortOrder: Int = 0,
    val isSaved: Boolean = false,
    val errorSummary: String? = null,
)

data class SavingsEditState(
    val isNew: Boolean = true,
    val form: SavingsForm = SavingsForm(),
    val accounts: List<AccountOption> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
)
