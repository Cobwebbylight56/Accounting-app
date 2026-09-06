package com.rhys.financetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.DashboardWidgetDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.projection.AccountActivity
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.data.local.projection.CategoryTotal
import com.rhys.financetracker.data.local.projection.IncomeExpenseTotals
import com.rhys.financetracker.data.local.projection.PotFlow
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.data.remote.ExternalDataRepository
import com.rhys.financetracker.data.remote.ExternalDataSnapshot
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.InsightRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.insight.Insight
import com.rhys.financetracker.domain.insight.InsightReport
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.DashboardWidget
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.FinancialSummary
import com.rhys.financetracker.domain.report.MonthPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val insightRepository: InsightRepository,
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

    /**
     * Every transaction in the month on screen, newest first.
     *
     * The card below shows a few of them and opens to the rest. It used to be
     * the last eight of all time, which on a freshly imported statement is
     * eight rows out of two hundred and no way to see the ninth without
     * leaving the screen.
     */
    private val monthTransactions = monthFlow.flatMapLatest { (month, _) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeBetween(range.start, range.endInclusive)
    }

    /**
     * Money into and out of savings this month, whether or not a savings
     * account exists in the app to hold it.
     */
    private val savingsThisMonth = potFlow(CategoryKind.SAVING)

    /** The same for cash: out of a machine, and back in at a counter. */
    private val cashThisMonth = potFlow(CategoryKind.CASH)

    /**
     * Everything the app has ever seen move into savings, less what came back.
     *
     * This is the closest thing to a balance for a saver held at another bank,
     * which the app has no account for. It only knows about the movements it
     * has been shown, so it is never called a balance.
     */
    private val savingsEverMoved = scope.flatMapLatest { currentScope ->
        transactionRepository.observePotFlow(
            kind = CategoryKind.SAVING,
            start = FIRST_POSSIBLE_DATE,
            end = DateUtils.today(),
            accountId = currentScope.accountId,
            personId = currentScope.personId,
        )
    }

    private fun potFlow(kind: CategoryKind) = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observePotFlow(
            kind = kind,
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personId = currentScope.personId,
        )
    }

    /** Money in and out of each account for the month on screen. */
    private val accountActivity = monthFlow.flatMapLatest { (month, _) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeAccountActivity(range.start, range.endInclusive)
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
     * The single most pressing piece of advice, for the dashboard card.
     *
     * Built on the IO dispatcher because assembling the full report runs several
     * queries; the dashboard must not wait on it, so it flows in separately and
     * the card fills itself once it arrives.
     */
    private val insightReport: StateFlow<InsightReport> =
        // Accounts are in here as the change signal, not for their value: their
        // balances are computed from every transaction, so this re-runs when
        // anything the advice is about actually moves. Keyed only on the month
        // and scope, the card sat still while the figures beneath it changed.
        combine(visibleMonth, scope, accounts) { month, currentScope, _ ->
            month to currentScope
        }
            .flatMapLatest { (month, currentScope) ->
                flow {
                    emit(
                        insightRepository.buildReport(
                            month = month,
                            personId = currentScope.personId,
                            accountId = currentScope.accountId,
                        ),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightReport.EMPTY)

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
            monthTransactions,
            savingsRepository.observeWithProgress(),
            peopleRepository.observeActive(),
            widgetDao.observeAll(),
            externalDataRepository.observeGrouped(),
            visibleMonth,
            scope,
            committed,
            insightReport,
            accountActivity,
            savingsThisMonth,
            cashThisMonth,
            savingsEverMoved,
            // Only for saying where the money is when the month on screen is
            // empty. Opening on today's month and finding nothing looks like a
            // broken app when the entries are simply in an earlier month.
            transactionRepository.observeRecent(1),
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
        val insights = values[14] as InsightReport
        val activity = values[15] as List<AccountActivity>
        val savings = values[16] as PotFlow
        val cash = values[17] as PotFlow
        val savingsEver = values[18] as PotFlow
        val latest = (values[19] as List<TransactionWithDetails>)
            .firstOrNull()?.transaction?.date

        val inScope = accountList.filter { currentScope.matches(it) }
        val unassigned = accountList.count { it.account.personId == null }
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
                savingsInMinor = savings.intoPotMinor,
                savingsOutMinor = savings.outOfPotMinor,
                savingsEverMovedMinor = savingsEver.netMinor,
                cashOutMinor = cash.intoPotMinor,
                cashInMinor = cash.outOfPotMinor,
            ),
            spendingByCategory = categories,
            monthlyTrend = trend,
            upcomingBills = upcoming,
            overdueBills = overdue,
            monthTransactions = recent,
            savingsGoals = goals,
            externalData = external,
            topInsight = insights.topPriority,
            insightCount = insights.insights.size,
            accountActivity = activity,
            widgets = mergeWidgets(widgets),
            accountsInTotal = accountList.size,
            unassignedAccounts = unassigned,
            latestEntryDate = latest,
        )
    }

    /**
     * The stored layout, plus any card this version added.
     *
     * Only cards the user has a row for are stored, and an upgrade brings new
     * ones. Without this they would be invisible on every install that existed
     * before them — present in the code, absent from the screen — and only a
     * fresh install would show them.
     */
    private fun mergeWidgets(stored: List<DashboardWidgetEntity>): List<VisibleWidget> {
        val known = stored.mapNotNull { entity ->
            DashboardWidget.fromKey(entity.widgetKey)?.let { widget ->
                VisibleWidget(widget, entity.position, entity.isVisible)
            }
        }
        val seen = known.map { it.widget }.toSet()
        val added = DashboardWidget.entries
            .filterNot { it in seen }
            .map { widget ->
                // Positioned by where it sits in the enum, which is where its
                // author meant it to appear.
                VisibleWidget(widget, DashboardWidget.entries.indexOf(widget), widget.defaultVisible)
            }
        return (known + added).sortedBy { it.position }
    }

    // --------------------------------------------------------- drill-down

    private val selectedCategory = MutableStateFlow<CategorySelection?>(null)

    /**
     * The breakdown shown when a category slice is tapped: that category's
     * entries for the month on screen, plus the same figure a month earlier so
     * the two can be compared.
     *
     * It is a separate flow from [state] so that opening and closing the sheet
     * does not recompute the whole dashboard.
     */
    val categoryDetail: StateFlow<CategoryDetail?> =
        combine(selectedCategory, visibleMonth, scope) { selection, month, currentScope ->
            Triple(selection, month, currentScope)
        }.flatMapLatest { (selection, month, currentScope) ->
            if (selection == null) {
                flowOf(null)
            } else {
                val range = DateUtils.monthRange(month)
                val previous = DateUtils.monthRange(month.minusMonths(1))
                combine(
                    transactionRepository.search(
                        TransactionFilter(
                            categoryIds = selection.categoryId?.let { setOf(it) } ?: emptySet(),
                            onlyUncategorised = selection.categoryId == null,
                            dateFrom = range.start,
                            dateTo = range.endInclusive,
                            types = setOf(TransactionType.EXPENSE),
                            personIds = currentScope.personId?.let { setOf(it) } ?: emptySet(),
                            sort = TransactionSort.AMOUNT_DESC,
                        ),
                    ),
                    transactionRepository.observeCategoryTotals(
                        type = TransactionType.EXPENSE,
                        start = previous.start,
                        end = previous.endInclusive,
                        accountId = currentScope.accountId,
                        personId = currentScope.personId,
                    ),
                ) { entries, lastMonth ->
                    CategoryDetail(
                        categoryId = selection.categoryId,
                        name = selection.name,
                        colorHex = selection.colorHex,
                        month = month,
                        transactions = entries,
                        totalMinor = entries.sumOf { it.transaction.amountMinor },
                        previousMonthTotalMinor = lastMonth
                            .firstOrNull { it.categoryId == selection.categoryId }
                            ?.totalMinor ?: 0L,
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Opens the breakdown for a tapped slice or legend row. */
    fun showCategoryDetail(categoryId: Long?, name: String, colorHex: String?) {
        selectedCategory.value = CategorySelection(categoryId, name, colorHex)
    }

    fun clearCategoryDetail() {
        selectedCategory.value = null
    }

    /** Jumps to the month behind a tapped bar. */
    fun showMonth(month: YearMonth) {
        if (!month.isAfter(DateUtils.currentYearMonth())) visibleMonth.value = month
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
        /** Early enough to be "everything", without pretending to be a date. */
        val FIRST_POSSIBLE_DATE: LocalDate = LocalDate.of(1900, 1, 1)
        const val MONTHS_ON_CHART = 6
        const val UPCOMING_DAYS = 30L
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
    val accountActivity: List<AccountActivity> = emptyList(),
    val monthlyTrend: List<MonthPoint> = emptyList(),
    val upcomingBills: List<RecurringRuleWithDetails> = emptyList(),
    val overdueBills: List<RecurringRuleWithDetails> = emptyList(),
    val monthTransactions: List<TransactionWithDetails> = emptyList(),
    /** Every account, whoever it belongs to — the person filter narrows [accounts]. */
    val accountsInTotal: Int = 0,
    /** Accounts nobody owns, which no person filter can ever show. */
    val unassignedAccounts: Int = 0,
    /** The date of the newest entry anywhere, for pointing at a month with data in it. */
    val latestEntryDate: LocalDate? = null,
    val savingsGoals: List<SavingsGoalWithProgress> = emptyList(),
    val externalData: ExternalDataSnapshot = ExternalDataSnapshot(),
    val topInsight: Insight? = null,
    val insightCount: Int = 0,
    val widgets: List<VisibleWidget> = emptyList(),
) {
    val isCurrentMonth: Boolean get() = month == DateUtils.currentYearMonth()
    val hasAnyData: Boolean get() = accounts.isNotEmpty() || monthTransactions.isNotEmpty()

    /**
     * True when the household has accounts but none of them are this person's.
     *
     * A different situation entirely from having nothing set up, and it was
     * being shown as the same thing: picking a person whose accounts had never
     * been put under their name offered to add an account, which makes a second
     * copy of one the app already holds.
     */
    val scopeHasNothingButAppDoes: Boolean
        get() = accounts.isEmpty() && accountsInTotal > 0
    fun isVisible(widget: DashboardWidget): Boolean =
        widgets.firstOrNull { it.widget == widget }?.isVisible ?: widget.defaultVisible
}

/** What the user tapped, before the figures behind it have been loaded. */
private data class CategorySelection(
    val categoryId: Long?,
    val name: String,
    val colorHex: String?,
)

/** The breakdown behind one category slice, for the month on screen. */
data class CategoryDetail(
    val categoryId: Long?,
    val name: String,
    val colorHex: String?,
    val month: YearMonth,
    val transactions: List<TransactionWithDetails>,
    val totalMinor: Long,
    val previousMonthTotalMinor: Long,
) {
    /** Positive means more was spent than last month. */
    val changeMinor: Long get() = totalMinor - previousMonthTotalMinor

    val hasComparison: Boolean get() = previousMonthTotalMinor > 0L

    /** Change as a percentage of last month, or null when there is nothing to compare with. */
    val changePercent: Int?
        get() = if (previousMonthTotalMinor <= 0L) {
            null
        } else {
            ((changeMinor.toDouble() / previousMonthTotalMinor) * 100).toInt()
        }
}
