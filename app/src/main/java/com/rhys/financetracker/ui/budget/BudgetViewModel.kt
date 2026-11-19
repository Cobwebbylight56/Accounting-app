package com.rhys.financetracker.ui.budget

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.repository.BudgetOverview
import com.rhys.financetracker.data.repository.BudgetRepository
import com.rhys.financetracker.data.repository.DraftLine
import com.rhys.financetracker.domain.budget.Suggestion
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Setting the budget up: lines offered, to keep, change or leave out. */
data class BudgetSetup(
    val drafts: List<DraftLine>,
    val fromSheet: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budget: BudgetRepository,
    categoryDao: CategoryDao,
    accountDao: AccountDao,
) : ViewModel() {

    private val thisMonth = YearMonth.from(DateUtils.today())
    private val _month = MutableStateFlow(thisMonth)
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _overview = MutableStateFlow<BudgetOverview?>(null)
    val overview: StateFlow<BudgetOverview?> = _overview.asStateFlow()

    private val _setup = MutableStateFlow<BudgetSetup?>(null)
    val setup: StateFlow<BudgetSetup?> = _setup.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** What a line can count: spending categories, and Savings. */
    val categories: StateFlow<List<CategoryEntity>> = categoryDao.observeAll()
        .mapLatest { all -> all.filter { !it.isArchived && (it.kind == CategoryKind.EXPENSE || it.kind == CategoryKind.SAVING) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Loans, cards and savers, for lines paid into one. */
    val accounts: StateFlow<List<AccountEntity>> = accountDao.observeActive()
        .mapLatest { all -> all.filter { it.holding != Holding.SPEND } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Worked out again whenever the month, a line or a month's change does.
        combine(_month, budget.lines, budget.changes) { month, _, _ -> month }
            .mapLatest { month -> runCatching { budget.overview(month) }.getOrNull() }
            .onEach { _overview.value = it }
            .launchIn(viewModelScope)
    }

    val canGoForward: Boolean get() = _month.value < thisMonth

    fun previousMonth() {
        _month.value = _month.value.minusMonths(1)
    }

    fun nextMonth() {
        if (_month.value < thisMonth) _month.value = _month.value.plusMonths(1)
    }

    fun showMonth(month: YearMonth) {
        _month.value = month.coerceAtMost(thisMonth)
    }

    /** Reads the household spreadsheet and offers its outgoings as the budget. */
    fun setUpFromSheet(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            when (val read = budget.readSpreadsheet(uri)) {
                is AppResult.Success -> _setup.value = BudgetSetup(read.data, fromSheet = true)
                is AppResult.Failure -> _message.value = read.message
            }
            _busy.value = false
        }
    }

    /** Offers a budget from what's been spent lately. */
    fun setUpFromSpending() {
        viewModelScope.launch {
            _busy.value = true
            val drafts = budget.fromSpending()
            if (drafts.isEmpty()) {
                _message.value = "There's not enough spending in the app yet to go on. Import a few months of statements first, or use your spreadsheet."
            } else {
                _setup.value = BudgetSetup(drafts, fromSheet = false)
            }
            _busy.value = false
        }
    }

    fun changeDraft(index: Int, draft: DraftLine) {
        val current = _setup.value ?: return
        _setup.value = current.copy(drafts = current.drafts.toMutableList().also { it[index] = draft })
    }

    fun saveSetup() {
        val current = _setup.value ?: return
        viewModelScope.launch {
            budget.saveSetup(current.drafts, current.fromSheet)
            _setup.value = null
            _month.value = thisMonth
        }
    }

    fun cancelSetup() {
        _setup.value = null
    }

    fun saveLine(line: BudgetLineEntity, thisMonthOnly: Long?, clearThisMonth: Boolean) {
        viewModelScope.launch {
            val id = budget.saveLine(line)
            when {
                thisMonthOnly != null -> budget.changeForMonth(id, _month.value, thisMonthOnly)
                clearThisMonth -> budget.changeForMonth(id, _month.value, null)
            }
        }
    }

    fun deleteLine(id: Long) {
        viewModelScope.launch { budget.deleteLine(id) }
    }

    fun accept(suggestion: Suggestion) {
        viewModelScope.launch { budget.accept(suggestion) }
    }

    fun dismiss(suggestion: Suggestion) {
        viewModelScope.launch {
            budget.dismiss(suggestion)
            // Turning one down changes nothing stored in the budget, so ask again.
            _overview.value = runCatching { budget.overview(_month.value) }.getOrNull()
        }
    }

    fun messageShown() {
        _message.value = null
    }
}
