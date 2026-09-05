package com.rhys.financetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.DashboardWidgetDao
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.remote.ExternalDataRepository
import com.rhys.financetracker.data.remote.ExternalDataSnapshot
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.model.DashboardWidget
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.FinancialSummary
import com.rhys.financetracker.domain.report.MonthPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Supplies the dashboard.
 *
 * Everything is derived from database flows, so the screen updates the instant
 * anything changes anywhere in the app — there is no refresh, and no state that
 * can go stale.
 *
 * The scope (whole household / one person / one account) is held here rather
 * than in the screen, so it survives rotation and navigation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val recurringRepository: RecurringRepository,
    private val savingsRepository: SavingsRepository,
    private val peopleRepository: PeopleRepository,
    private val externalDataRepository: ExternalDataRepository,
    private val widgetDao: DashboardWidgetDao,
) : ViewModel() {

    private val scope = MutableStateFlow(DashboardScope())

    /** The month the dashboard is showing; the user can step back through history. */
    private val visibleMonth = MutableStateFlow(DateUtils.currentYearMonth())

    private val accounts: StateFlow<List<AccountWithBalance>> =
        accountRepository.observeWithBalances()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val monthFlow = combine(visibleMonth, scope) { month, currentScope ->
        month to currentScope
    }

    private val totals = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeIncomeExpense(
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personId = currentScope.personId,
        )
    }

    private val spendingByCategory = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeCategoryTotals(
            type = TransactionType.EXPENSE,
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personId = currentScope.personId,
        )
    }

    private val monthlyTrend = monthFlow.flatMapLatest { (month, currentScope) ->
        val months = DateUtils.recentMonths(MONTHS_ON_CHART, month)
        transactionRepository.observeMonthlyTotals(
            start = months.first().atDay(1),
            end = months.last().atEndOfMonth(),
            accountId = currentScope.accountId,
            personId = currentScope.personId,
        ).map { rows ->
            // Fill in the months with no activity, so the chart has an even
            // spacing rather than silently skipping quiet months.
            val byKey = rows.associateBy { it.yearMonth }
            months.map { candidate ->
                val row = byKey[DateUtils.yearMonthKey(candidate)]
                MonthPoint(
                    yearMonth = candidate,
                    incomeMinor = row?.incomeMinor ?: 0L,
                    expenseMinor = row?.expenseMinor ?: 0L,
                )
            }
        }
    }

    /**
     * Money already promised to bills between today and the end of the month.
     * Recomputed whenever any recurring rule changes.
     */
    private val committed: Flow<Long> = recurringRepository.observeAll()
        .map { recurringRepository.remainingThisMonthMinor() }

    /**
     * The dashboard needs more sources than `combine`'s typed overloads take, so
     * the list form is used.  The element type is stated explicitly because the
     * flows have different value types and inference would otherwise settle on
     * a star projection that `combine` will not accept.
     */
    val state: StateFlow<DashboardState> = combine(
        listOf<Flow<Any?>>(
            accounts,
            totals,
            spendingByCategory,
            monthlyTrend,
            recurringRepository.observeUpcoming(days = UPCOMING_DAYS),
            recurringRepository.observeOverdue(),
            transactionRepository.observeRecent(RECENT_COUNT),
            savingsRepository.observeWithProgress(),
            peopleRepository.observeActive(),
            widgetDao.observeAll(),
            externalDataRepository.observeGrouped(),
            visibleMonth,
            scope,
            committed,
        ),
    ) { values -> buildState(values) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())

    @Suppress("UNCHECKED_CAST")
    private fun buildState(values: Array<*>): DashboardState {
        val accountList = values[0] as List<AccountWithBalance>
        val monthTotals =
            values[1] as com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
        val categories = values[2] as List<CategoryTotal>
        val trend = values[3] as List<MonthPoint>
        val upcoming = values[4] as List<RecurringRuleWithDetails>
        val overdue = values[5] as List<RecurringRuleWithDetails>
        val recent = values[6] as List<TransactionWithDetails>
        val goals = values[7] as List<SavingsGoalWithProgress>
        val people =
            values[8] as List<com.rhys.financetracker.data.local.entity.PersonEntity>
        val widgets = values[9] as List<DashboardWidgetEntity>
        val external = values[10] as ExternalDataSnapshot
        val month = values[11] as YearMonth
        val currentScope = values[12] as DashboardScope
        val committed = values[13] as Long

        val inScope = accountList.filter { currentScope.matches(it) }
        val savingsTotal = inScope.filter { it.isSavings }.sumOf { it.balanceMinor }
        val liabilities = inScope.filter { it.isLiability }.sumOf { it.balanceMinor }
        val spendable = inScope.filterNot { it.isSavings || it.isLiability }
            .sumOf { it.balanceMinor }

        return DashboardState(
            isLoading = false,
            month = month,
            scope = currentScope,
            accounts = inScope,
            people = people,
            summary = FinancialSummary(
                totalBalanceMinor = spendable,
                totalSavingsMinor = savingsTotal,
                totalLiabilitiesMinor = liabilities,
                netWorthMinor = inScope.sumOf { it.netWorthContributionMinor },
                monthIncomeMinor = monthTotals.incomeMinor,
                monthExpenseMinor = monthTotals.expenseMinor,
                committedRecurringMinor = committed,
            ),
            spendingByCategory = categories,
            monthlyTrend = trend,
            upcomingBills = upcoming,
            overdueBills = overdue,
            recentTransactions = recent,
            savingsGoals = goals,
            externalData = external,
            widgets = widgets
                .mapNotNull { entity ->
                    DashboardWidget.fromKey(entity.widgetKey)?.let { widget ->
                        VisibleWidget(widget, entity.position, entity.isVisible)
                    }
                }
                .sortedBy { it.position },
        )
    }

    fun setScope(newScope: DashboardScope) {
        scope.value = newScope
    }

    fun showPreviousMonth() {
        visibleMonth.value = visibleMonth.value.minusMonths(1)
    }

    fun showNextMonth() {
        val next = visibleMonth.value.plusMonths(1)
        // There is nothing useful beyond the current month, so do not go there.
        if (!next.isAfter(DateUtils.currentYearMonth())) visibleMonth.value = next
    }

    fun showCurrentMonth() {
        visibleMonth.value = DateUtils.currentYearMonth()
    }

    /** Reorders or hides a dashboard card. */
    fun setWidgetVisible(widget: DashboardWidget, visible: Boolean) {
        viewModelScope.launch { widgetDao.setVisible(widget.key, visible) }
    }

    fun moveWidget(widget: DashboardWidget, direction: Int) {
        viewModelScope.launch {
            val current = widgetDao.getAll().sortedBy { it.position }.toMutableList()
            val index = current.indexOfFirst { it.widgetKey == widget.key }
            val target = index + direction
            if (index < 0 || target !in current.indices) return@launch
            val moved = current.removeAt(index)
            current.add(target, moved)
            widgetDao.upsertAll(
                current.mapIndexed { position, entity -> entity.copy(position = position) },
            )
        }
    }

    private companion object {
        const val MONTHS_ON_CHART = 6
        const val UPCOMING_DAYS = 30L
        const val RECENT_COUNT = 8
    }
}

/** Which slice of the household the dashboard is showing. */
data class DashboardScope(
    val personId: Long? = null,
    val accountId: Long? = null,
    val label: String = "Whole household",
) {
    fun matches(account: AccountWithBalance): Boolean = when {
        accountId != null -> account.account.id == accountId
        personId != null -> account.account.personId == personId || account.account.isShared
        else -> true
    }
}

/** One dashboard card and where it sits. */
data class VisibleWidget(
    val widget: DashboardWidget,
    val position: Int,
    val isVisible: Boolean,
)

/** Everything the dashboard needs to draw itself. */
data class DashboardState(
    val isLoading: Boolean = true,
    val month: YearMonth = DateUtils.currentYearMonth(),
    val scope: DashboardScope = DashboardScope(),
    val accounts: List<AccountWithBalance> = emptyList(),
    val people: List<com.rhys.financetracker.data.local.entity.PersonEntity> = emptyList(),
    val summary: FinancialSummary = FinancialSummary.EMPTY,
    val spendingByCategory: List<CategoryTotal> = emptyList(),
    val monthlyTrend: List<MonthPoint> = emptyList(),
    val upcomingBills: List<RecurringRuleWithDetails> = emptyList(),
    val overdueBills: List<RecurringRuleWithDetails> = emptyList(),
    val recentTransactions: List<TransactionWithDetails> = emptyList(),
    val savingsGoals: List<SavingsGoalWithProgress> = emptyList(),
    val externalData: ExternalDataSnapshot = ExternalDataSnapshot(),
    val widgets: List<VisibleWidget> = emptyList(),
) {
    val isCurrentMonth: Boolean get() = month == DateUtils.currentYearMonth()
    val hasAnyData: Boolean get() = accounts.isNotEmpty() || recentTransactions.isNotEmpty()
    fun isVisible(widget: DashboardWidget): Boolean =
        widgets.firstOrNull { it.widget == widget }?.isVisible ?: widget.defaultVisible
}
