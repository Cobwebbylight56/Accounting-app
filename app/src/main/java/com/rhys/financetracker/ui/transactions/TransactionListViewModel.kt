package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.layout.size
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.export.ExportManager
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.CategoryRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.ExportFormat
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The transactions screen: search, filter, and the list itself.
 *
 * The typed query is debounced before it reaches the database, so typing does
 * not fire a query per keystroke — with tens of thousands of rows that is the
 * difference between a list that keeps up and one that stutters.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class TransactionListViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val peopleRepository: PeopleRepository,
    private val exportManager: ExportManager,
    ledgerRequests: LedgerRequests,
    savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val filter = MutableStateFlow(TransactionFilter())
    private val searchText = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)
    private val exported = MutableStateFlow<ExportedFile?>(null)

    /** The entry just deleted from the list, kept until its Undo has had its chance. */
    private val lastDeleted = MutableStateFlow<com.rhys.financetracker.data.local.entity.TransactionEntity?>(null)

    private val effectiveFilter: StateFlow<TransactionFilter> =
        combine(filter, searchText.debounce(SEARCH_DEBOUNCE_MS)) { current, text ->
            current.copy(text = text.takeIf { it.isNotBlank() })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionFilter())

    private val results: StateFlow<List<TransactionWithDetails>> =
        effectiveFilter.flatMapLatest { transactionRepository.search(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<TransactionListState> = combine(
        results,
        effectiveFilter,
        searchText,
        combine(
            accountRepository.observeActiveOptions(),
            categoryRepository.observeActive(),
            peopleRepository.observeActive(),
        ) { accounts, categories, people -> Triple(accounts, categories, people) },
        combine(message, exported, lastDeleted) { text, file, deleted -> Triple(text, file, deleted != null) },
    ) { items, currentFilter, text, options, messages ->
        val (accounts, categories, people) = options
        TransactionListState(
            isLoading = false,
            transactions = items,
            filter = currentFilter,
            searchText = text,
            accounts = accounts,
            categories = categories,
            people = people,
            totalIncomeMinor = items
                .filter { it.transaction.type == TransactionType.INCOME }
                .sumOf { it.transaction.amountMinor },
            totalExpenseMinor = items
                .filter { it.transaction.type == TransactionType.EXPENSE }
                .sumOf { it.transaction.amountMinor },
            message = messages.first,
            exportedFile = messages.second,
            canUndoDelete = messages.third,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionListState())

    init {
        // Opened from another page to show something in particular — a
        // category on the Spending tab, an account's payments. That page left
        // the filter; this one is its own page, so back goes back there. The
        // Money tab itself never takes it, or back from it went to Home.
        if (savedStateHandle.get<Boolean>(Routes.ARG_DRILL_DOWN) == true) {
            ledgerRequests.requests.value?.let { request ->
                // Any words go in the search box, where they can be seen and
                // cleared, rather than hidden in the filter.
                filter.value = request.copy(text = null)
                searchText.value = request.text.orEmpty()
                ledgerRequests.consumed()
            }
        }
    }

    fun setSearchText(text: String) {
        searchText.value = text
    }

    fun setSort(sort: TransactionSort) {
        filter.value = filter.value.copy(sort = sort)
    }

    fun toggleType(type: TransactionType) {
        val current = filter.value.types
        filter.value = filter.value.copy(
            types = if (type in current) current - type else current + type,
        )
    }

    fun toggleAccount(id: Long) {
        val current = filter.value.accountIds
        filter.value = filter.value.copy(
            accountIds = if (id in current) current - id else current + id,
        )
    }

    fun toggleCategory(id: Long) {
        val current = filter.value.categoryIds
        filter.value = filter.value.copy(
            categoryIds = if (id in current) current - id else current + id,
        )
    }

    fun togglePerson(id: Long) {
        val current = filter.value.personIds
        filter.value = filter.value.copy(
            personIds = if (id in current) current - id else current + id,
        )
    }

    fun setDateRange(from: LocalDate?, to: LocalDate?) {
        filter.value = filter.value.copy(dateFrom = from, dateTo = to)
    }

    fun setAmountRange(minText: String, maxText: String) {
        filter.value = filter.value.copy(
            minAmountMinor = Money.parseOrNull(minText),
            maxAmountMinor = Money.parseOrNull(maxText),
        )
    }

    fun setShowArchived(show: Boolean) {
        filter.value = filter.value.copy(includeArchived = show)
    }

    fun setOnlyUnconfirmed(only: Boolean) {
        filter.value = filter.value.copy(onlyUnconfirmed = only)
    }

    fun clearFilters() {
        filter.value = TransactionFilter()
        searchText.value = ""
    }

    fun archive(id: Long) = runAction { transactionRepository.setArchived(id, archived = true) }

    fun unarchive(id: Long) = runAction { transactionRepository.setArchived(id, archived = false) }

    fun duplicate(id: Long) = runAction { transactionRepository.duplicate(id) }

    fun confirm(id: Long) = runAction { transactionRepository.confirm(id) }

    /**
     * Deletes everything the list is currently showing.
     *
     * Filter to what you want gone, check the count in the confirmation, then
     * it goes. Driven by the same filter as the list so there is never a gap
     * between what is on screen and what is removed.
     */
    fun deleteShown() {
        viewModelScope.launch {
            when (val result = transactionRepository.deleteMatching(effectiveFilter.value)) {
                is com.rhys.financetracker.core.result.AppResult.Success ->
                    message.value = if (result.data == 1) {
                        "1 entry deleted"
                    } else {
                        "${result.data} entries deleted"
                    }
                is com.rhys.financetracker.core.result.AppResult.Failure ->
                    message.value = result.message
            }
        }
    }

    /**
     * Deletes one entry straight from the list, with Undo on the message.
     *
     * The menu's Delete sits right under Archive; one slip used to remove a
     * payment for good with nothing to bring it back.
     */
    fun delete(id: Long) {
        viewModelScope.launch {
            val entity = transactionRepository.get(id) ?: return@launch
            val error = transactionRepository.delete(entity).errorMessageOrNull()
            if (error == null) lastDeleted.value = entity
            message.value = error ?: "Deleted \"${entity.description}\""
        }
    }

    fun undoDelete() {
        val entity = lastDeleted.value ?: return
        lastDeleted.value = null
        viewModelScope.launch {
            message.value = transactionRepository.restore(entity).errorMessageOrNull()
        }
    }

    /** The Undo has had its chance; what was deleted stays deleted. */
    fun forgetDeleted() {
        lastDeleted.value = null
    }

    /** Exports whatever the current filter has produced, not the whole database. */
    fun exportResults(format: ExportFormat) {
        viewModelScope.launch {
            val items = state.value.transactions
            if (items.isEmpty()) {
                message.value = "There is nothing to export"
                return@launch
            }
            when (val result = exportManager.exportTransactionsToCache(items, format)) {
                is com.rhys.financetracker.core.result.AppResult.Success -> {
                    exported.value = result.data
                    message.value = "${items.size} transactions exported"
                }
                is com.rhys.financetracker.core.result.AppResult.Failure ->
                    message.value = result.message
            }
        }
    }

    fun consumeExportedFile() {
        exported.value = null
    }

    fun clearMessage() {
        message.value = null
    }

    private fun runAction(block: suspend () -> com.rhys.financetracker.core.result.AppResult<*>) {
        viewModelScope.launch {
            message.value = block().errorMessageOrNull()
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

/** Everything the transactions screen renders. */
data class TransactionListState(
    val isLoading: Boolean = true,
    val transactions: List<TransactionWithDetails> = emptyList(),
    val filter: TransactionFilter = TransactionFilter(),
    val searchText: String = "",
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val totalIncomeMinor: Long = 0L,
    val totalExpenseMinor: Long = 0L,
    val message: String? = null,
    val exportedFile: ExportedFile? = null,
    /** True while the message on show is about an entry that Undo can still bring back. */
    val canUndoDelete: Boolean = false,
) {
    val netMinor: Long get() = totalIncomeMinor - totalExpenseMinor
    val hasFilters: Boolean get() = !filter.isEmpty
    val resultCount: Int get() = transactions.size
}
