package com.rhys.financetracker.ui.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PayeeRepository
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
    private val payeeRepository: PayeeRepository,
    private val splitRepository: com.rhys.financetracker.data.receipts.SplitRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val transactionId: Long = savedStateHandle.get<String>(Routes.ARG_ID)?.toLongOrNull()
        ?: Routes.NEW_ID

    private val form = MutableStateFlow(TransactionForm())

    /** The parts this payment is split into, if it is split. */
    val splitParts: kotlinx.coroutines.flow.Flow<List<com.rhys.financetracker.data.local.dao.SplitPart>> =
        splitRepository.observeParts(transactionId)

    /** How many items its receipt lists, to offer splitting by them. */
    suspend fun receiptItemCount(): Int = splitRepository.receiptItems(transactionId).size
    private val saved = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    /** The category it had when opened, and how many other payments share its payee. */
    private val original = MutableStateFlow(Original())

    /** The entry as stored, so saving keeps what the form does not show. */
    private var loaded: TransactionEntity? = null

    val state: StateFlow<TransactionEditState> = combine(
        form,
        accountRepository.observeActiveOptions(),
        categoryRepository.observeAll(),
        peopleRepository.observeActive(),
        combine(savingsRepository.observeAll(), saved, message, original) { goals, isSaved, text, before ->
            Extras(goals, isSaved, text, before)
        },
    ) { currentForm, accounts, categories, people, extras ->
        val (goals, isSaved, text, before) = extras
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
            payee = before.payee,
            otherPayments = before.others,
            movesOthers = movesOthers(currentForm, before),
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
            message.value = "That payment no longer exists"
            return
        }
        loaded = existing
        // Money with a person is kept as money in or out — so balances move —
        // under a transfer category, so it is not spending. Here it shows as
        // what it is: a transfer, with a person.
        val withPerson = existing.type != TransactionType.TRANSFER &&
            existing.categoryId?.let { categoryRepository.get(it)?.kind } == CategoryKind.TRANSFER
        form.value = TransactionForm(
            description = existing.description,
            amountText = Money.formatPlain(existing.amountMinor),
            type = if (withPerson) TransactionType.TRANSFER else existing.type,
            withPerson = withPerson,
            received = existing.type == TransactionType.INCOME,
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
        original.value = Original(
            categoryId = existing.categoryId,
            payee = PayeeNames.of(existing.description),
            others = payeeRepository.samePayee(existing.description, existing.type, existing.id).size,
        )
    }

    /**
     * True when saving will move the payee's other payments too: the user
     * picked a different category for one they already had. What they choose
     * goes for every payment to that payee, past and future.
     */
    private fun movesOthers(current: TransactionForm, before: Original): Boolean =
        transactionId != Routes.NEW_ID &&
            before.payee.isNotBlank() &&
            current.categoryId != null &&
            current.categoryId != before.categoryId &&
            current.effectiveType != TransactionType.TRANSFER

    fun update(transform: (TransactionForm) -> TransactionForm) {
        form.value = transform(form.value)
    }

    fun setType(type: TransactionType) {
        val previous = form.value.categoryId
        val from = form.value.effectiveType
        // Turning money in or out into a transfer: when it names a person, it
        // is money with them, the way it went; otherwise a move between the
        // user's own accounts, as before.
        val withPerson = type == TransactionType.TRANSFER && from != TransactionType.TRANSFER &&
            (
                com.rhys.financetracker.data.importer.MerchantCategoriser.isMoneyWithAPerson(form.value.description, from) ||
                    PayeeNames.readsLikeMoneyIn(form.value.description)
                )
        form.value = form.value.copy(
            type = type,
            withPerson = withPerson,
            received = if (withPerson) from == TransactionType.INCOME else form.value.received,
            // A category from the other side of the books would be meaningless.
            categoryId = null,
            transferAccountId = if (type == TransactionType.TRANSFER) {
                form.value.transferAccountId
            } else {
                null
            },
        )
        // But where the same category exists on the new side, keep it: turning
        // "Bank credit H Payne" from Expense to Income keeps "Transfers &
        // payments" rather than leaving it uncategorised.
        val kind = when (type) {
            TransactionType.INCOME -> CategoryKind.INCOME
            TransactionType.EXPENSE -> CategoryKind.EXPENSE
            TransactionType.TRANSFER -> CategoryKind.TRANSFER
        }
        if (previous != null) {
            viewModelScope.launch {
                categoryRepository.sameNameAs(previous, kind)?.let { same ->
                    if (form.value.type == type && form.value.categoryId == null) {
                        form.value = form.value.copy(categoryId = same.id)
                    }
                }
            }
        }
        if (withPerson) fileWithPeople()
    }

    /** Between the user's own accounts, or money with a person. */
    fun setWithPerson(withPerson: Boolean) {
        form.value = form.value.copy(
            withPerson = withPerson,
            transferAccountId = if (withPerson) null else form.value.transferAccountId,
        )
        if (withPerson) fileWithPeople()
    }

    /** For money with a person: whether it was sent or received. */
    fun setReceived(received: Boolean) {
        form.value = form.value.copy(received = received)
    }

    /** Puts money with a person under "Transfers & payments" unless a transfer category is chosen. */
    private fun fileWithPeople() {
        viewModelScope.launch {
            val current = form.value.categoryId?.let { categoryRepository.get(it) }
            if (current?.kind != CategoryKind.TRANSFER || current.name == SYSTEM_TRANSFER) {
                val people = categoryRepository.peopleTransfers()
                form.value = form.value.copy(categoryId = people.id)
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            val current = form.value
            val error = validate(current)
            if (error != null) {
                form.value = current.copy(errorSummary = error)
                return@launch
            }

            val before = loaded
            val categoryChanged = current.categoryId != before?.categoryId
            val fresh = TransactionEntity(
                id = if (transactionId == Routes.NEW_ID) 0L else transactionId,
                amountMinor = Money.parseOrNull(current.amountText) ?: 0L,
                // Money with a person is stored as money in or out, so the
                // balance moves the right way; its transfer category keeps it
                // out of spending.
                type = current.effectiveType,
                date = current.date,
                description = current.description.trim(),
                accountId = current.accountId ?: 0L,
                transferAccountId = if (current.withPersonTransfer) null else current.transferAccountId,
                categoryId = current.categoryId,
                personId = current.personId,
                savingsGoalId = current.savingsGoalId,
                notes = current.notes.trim().takeIf { it.isNotEmpty() },
                tags = current.tags.trim().takeIf { it.isNotEmpty() },
                isCleared = current.isCleared,
                isConfirmed = true,
                // The hidden mark: the user picked this category, so the app
                // never re-sorts it. Saving without touching the category
                // keeps whatever mark it had.
                categoryByUser = current.categoryId != null &&
                    (before == null || categoryChanged || before.categoryByUser),
            )
            // An edit is still the same entry: where it came from, its
            // fingerprint and its bill stay, or a re-import would add the
            // statement row again beside it and the bill would lose it.
            val entity = before?.let { old ->
                fresh.copy(
                    recurringRuleId = old.recurringRuleId,
                    importHash = old.importHash,
                    source = old.source,
                    isArchived = old.isArchived,
                    createdAt = old.createdAt,
                )
            } ?: fresh

            when (val result = transactionRepository.save(entity)) {
                is AppResult.Success -> {
                    // A split payment's parts follow a new amount.
                    if (transactionId != Routes.NEW_ID) splitRepository.reconcile(transactionId)
                    val categoryId = current.categoryId
                    if (categoryId != null && movesOthers(current, original.value)) {
                        payeeRepository.fileEveryPaymentLike(entity.description, entity.type, categoryId, entity.id)
                    }
                    saved.value = true
                }
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
        val nameError = Validators.validateDescription(current.description).errorOrNull
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
            current.type == TransactionType.TRANSFER && !current.withPerson && current.transferAccountId == null ->
                "Choose the account the money is going to"
            current.type == TransactionType.TRANSFER && !current.withPerson &&
                current.transferAccountId == current.accountId ->
                "A transfer must be between two different accounts"
            else -> null
        }
    }
}

/** What the entry was when opened, to tell whether its category was changed. */
data class Original(
    val categoryId: Long? = null,
    val payee: String = "",
    val others: Int = 0,
)

private data class Extras(
    val goals: List<SavingsGoalEntity>,
    val isSaved: Boolean,
    val message: String?,
    val original: Original,
)

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
    /** On the Transfer tab: money with a person, rather than between the user's own accounts. */
    val withPerson: Boolean = false,
    /** For money with a person: they sent it, rather than the user. */
    val received: Boolean = false,
    val descriptionError: String? = null,
    val amountError: String? = null,
    val dateError: String? = null,
    val errorSummary: String? = null,
)

/** A transfer with a person rather than between the user's own accounts. */
val TransactionForm.withPersonTransfer: Boolean
    get() = type == TransactionType.TRANSFER && withPerson

/** How it is stored: money with a person is money in or out under a transfer category. */
val TransactionForm.effectiveType: TransactionType
    get() = when {
        !withPersonTransfer -> type
        received -> TransactionType.INCOME
        else -> TransactionType.EXPENSE
    }

/** The app's own category for moves between accounts, which is not for people. */
private const val SYSTEM_TRANSFER = "Transfer"

data class TransactionEditState(
    val isNew: Boolean = true,
    val form: TransactionForm = TransactionForm(),
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val savingsGoals: List<SavingsGoalEntity> = emptyList(),
    val isSaved: Boolean = false,
    val message: String? = null,
    /** Who it was paid to, tidied, or blank when the description names nobody. */
    val payee: String = "",
    /** Other payments to the same payee. */
    val otherPayments: Int = 0,
    /** Saving will move [otherPayments] to the chosen category too. */
    val movesOthers: Boolean = false,
)
