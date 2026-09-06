package com.rhys.financetracker.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.TransactionType
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

/**
 * Add or edit one transaction.
 *
 * The form holds text, not parsed values, so a half-typed amount is never lost
 * to a failed parse.  Conversion and validation happen once, on save.
 */
@HiltViewModel
class TransactionEditViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val peopleRepository: PeopleRepository,
    private val savingsRepository: SavingsRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val transactionId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull()
        ?: Routes.NEW_ID

    private val form = MutableStateFlow(TransactionForm())
    private val saved = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<TransactionEditState> = combine(
        form,
        accountRepository.observeActiveOptions(),
        categoryRepository.observeAll(),
        peopleRepository.observeActive(),
        combine(savingsRepository.observeAll(), saved, message) { goals, isSaved, text ->
            Triple(goals, isSaved, text)
        },
    ) { currentForm, accounts, categories, people, extras ->
        val (goals, isSaved, text) = extras
        TransactionEditState(
            isNew = transactionId == Routes.NEW_ID,
            form = currentForm,
            accounts = accounts,
            // Only offer categories that make sense for the chosen direction.
                // Savings and cash are offered on every direction. Paying
                // into a saver is an expense on the account it leaves, taking
                // money back out is income to it, and neither could be picked
                // at all before — so a payment the importer had filed under
                // Savings could not be corrected to it by hand.
            categories = categories.filter { category ->
                !category.isArchived && when (currentForm.type) {
                    TransactionType.INCOME ->
                        category.kind == CategoryKind.INCOME || category.kind.isAPot
                    TransactionType.EXPENSE ->
                        category.kind == CategoryKind.EXPENSE || category.kind.isAPot
                    TransactionType.TRANSFER ->
                        category.kind == CategoryKind.TRANSFER || category.kind.isAPot
                }
            },
            people = people,
            savingsGoals = goals.filterNot { it.isArchived },
            isSaved = isSaved,
            message = text,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionEditState())

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val defaults = accountRepository.observeActiveOptions().first()
        if (transactionId == Routes.NEW_ID) {
            form.value = TransactionForm(
                date = DateUtils.today(),
                accountId = defaults.firstOrNull()?.id,
            )
            return
        }
        val existing = transactionRepository.get(transactionId) ?: run {
            message.value = "That transaction no longer exists"
            return
        }
        form.value = TransactionForm(
            description = existing.description,
            amountText = Money.formatPlain(existing.amountMinor),
            type = existing.type,
            date = existing.date,
            accountId = existing.accountId,
            transferAccountId = existing.transferAccountId,
            categoryId = existing.categoryId,
            personId = existing.personId,
            savingsGoalId = existing.savingsGoalId,
            notes = existing.notes.orEmpty(),
            tags = existing.tags.orEmpty(),
            isCleared = existing.isCleared,
            isConfirmed = existing.isConfirmed,
        )
    }

    fun update(transform: (TransactionForm) -> TransactionForm) {
        form.value = transform(form.value)
    }

    fun setType(type: TransactionType) {
        form.value = form.value.copy(
            type = type,
            // A category from the other side of the books would be meaningless.
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
            val error = validate(current)
            if (error != null) {
                form.value = current.copy(errorSummary = error)
                return@launch
            }

            val entity = TransactionEntity(
                id = if (transactionId == Routes.NEW_ID) 0L else transactionId,
                amountMinor = Money.parseOrNull(current.amountText) ?: 0L,
                type = current.type,
                date = current.date,
                description = current.description.trim(),
                accountId = current.accountId ?: 0L,
                transferAccountId = current.transferAccountId,
                categoryId = current.categoryId,
                personId = current.personId,
                savingsGoalId = current.savingsGoalId,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
                tags = current.tags.trim().takeIf { it.isNotEmpty() },
                isCleared = current.isCleared,
                isConfirmed = true,
            )

            when (val result = transactionRepository.save(entity)) {
                is AppResult.Success -> saved.value = true
                is AppResult.Failure -> form.value = current.copy(errorSummary = result.message)
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            val entity = transactionRepository.get(transactionId) ?: return@launch
            when (val result = transactionRepository.delete(entity)) {
                is AppResult.Success -> saved.value = true
                is AppResult.Failure -> message.value = result.message
            }
        }
    }

    fun clearMessage() {
        message.value = null
        form.value = form.value.copy(errorSummary = null)
    }

    /** Field-level checks, reported against the field they belong to. */
    private fun validate(current: TransactionForm): String? {
        val amountError = Validators.validateAmount(current.amountText).errorOrNull
        val nameError = Validators.validateName(current.description, "Description").errorOrNull
        val dateError = Validators.validateDate(current.date).errorOrNull

        form.value = current.copy(
            amountError = amountError,
            descriptionError = nameError,
            dateError = dateError,
        )

        return when {
            nameError != null -> nameError
            amountError != null -> amountError
            dateError != null -> dateError
            current.accountId == null -> "Choose an account"
            current.type == TransactionType.TRANSFER && current.transferAccountId == null ->
                "Choose the account the money is going to"
            current.type == TransactionType.TRANSFER &&
                current.transferAccountId == current.accountId ->
                "A transfer must be between two different accounts"
            else -> null
        }
    }
}

/** The editable state of the form. */
data class TransactionForm(
    val description: String = "",
    val amountText: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val date: LocalDate = DateUtils.today(),
    val accountId: Long? = null,
    val transferAccountId: Long? = null,
    val categoryId: Long? = null,
    val personId: Long? = null,
    val savingsGoalId: Long? = null,
    val notes: String = "",
    val tags: String = "",
    val isCleared: Boolean = true,
    val isConfirmed: Boolean = true,
    val descriptionError: String? = null,
    val amountError: String? = null,
    val dateError: String? = null,
    val errorSummary: String? = null,
)

data class TransactionEditState(
    val isNew: Boolean = true,
    val form: TransactionForm = TransactionForm(),
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val savingsGoals: List<SavingsGoalEntity> = emptyList(),
    val isSaved: Boolean = false,
    val message: String? = null,
)
