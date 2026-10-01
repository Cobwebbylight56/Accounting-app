package com.rhys.financetracker.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.repository.PayeeRepository
import com.rhys.financetracker.data.repository.PeopleMoney
import com.rhys.financetracker.data.local.dao.DashboardWidgetDao
import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
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
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.remote.ExternalDataRepository
import com.rhys.financetracker.data.remote.ExternalDataSnapshot
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.CashPotRepository
import com.rhys.financetracker.data.repository.IncomeRepository
import com.rhys.financetracker.data.repository.InsightRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.data.repository.TransactionRepository
import com.rhys.financetracker.domain.income.IncomeStats
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
import kotlinx.coroutines.flow.mapLatest
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
    private val cashPotRepository: CashPotRepository,
    private val incomeRepository: IncomeRepository,
    private val settingsRepository: SettingsRepository,
    private val payeeRepository: PayeeRepository,
    private val tidyUp: com.rhys.financetracker.data.repository.TidyUpRepository,
) : ViewModel() {

    /** Which tab is picked; null until somebody picks, meaning the first person. */
    private val choice = MutableStateFlow<ScopeChoice?>(null)

    private val people = peopleRepository.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * True when reminders are switched on but Android has never been asked
     * whether the app may show them. They start switched on, and on Android
     * 13 and later nothing appears until that question has been answered.
     */
    val askForNotifications: StateFlow<Boolean> = settingsRepository.settings
        .map { s ->
            !s.notificationsAsked &&
                (s.notifyBills || s.notifyOverdue || s.notifyLowBalance || s.notifyGoals)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * True when there is money in the app, no backup in the last fortnight,
     * no automatic backup, and the reminder has not been put off. Everything
     * lives only on this phone; a lost phone or a reinstall loses the lot.
     */
    val showBackupNudge: StateFlow<Boolean> = combine(
        settingsRepository.settings,
        // Not [accounts]: that is declared further down and is not set yet here.
        accountRepository.observeWithBalances(),
    ) { s, list ->
        val now = System.currentTimeMillis()
        list.isNotEmpty() && !s.autoBackupEnabled &&
            (s.lastBackupAt == null || now - s.lastBackupAt > BACKUP_NUDGE_AFTER_MS) &&
            (s.backupNudgeSnoozedUntil == null || now > s.backupNudgeSnoozedUntil)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** What the app re-sorted by itself after an update, until it is seen. */
    val resortNote: StateFlow<com.rhys.financetracker.data.repository.ResortNote?> = settingsRepository.settings
        .map { com.rhys.financetracker.data.repository.ResortNote.decode(it.lastResortSummary) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismissResortNote() {
        viewModelScope.launch { settingsRepository.clearResortNote() }
    }

    /** Puts every payment the re-sort moved back where it was. */
    fun undoResort() {
        viewModelScope.launch { tidyUp.undoLastResort() }
    }

    /** Puts the backup reminder off for a week. */
    fun snoozeBackupNudge() {
        viewModelScope.launch {
            settingsRepository.snoozeBackupNudge(System.currentTimeMillis() + BACKUP_NUDGE_SNOOZE_MS)
        }
    }

    fun markNotificationsAsked() {
        viewModelScope.launch { settingsRepository.setNotificationsAsked() }
    }

    /** Who the Shared tab covers, as chosen; null means everybody. */
    val sharedPeopleIds: StateFlow<Set<Long>?> = settingsRepository.settings
        .map { it.sharedPeopleIds }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What Home is showing: one person's tab, or the Shared tab with the
     * people chosen for it.
     *
     * Everything is built around a person. The Shared tab is not "everyone"
     * by definition — it is the people picked for it, plus whatever is held
     * jointly — so a lodger or a grown-up child can have their own tab
     * without being folded into the household's figures.
     */
    private val scope: StateFlow<DashboardScope> =
        combine(choice, people, sharedPeopleIds) { picked, everyone, shared ->
            scopeFor(picked, everyone, shared)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardScope())

    init {
        viewModelScope.launch {
            // Pay rises dated for today or earlier take effect now.
            incomeRepository.applyDue()
        }
        viewModelScope.launch {
            // Home used to show the same figures two or three times over.
            // Once per install, the cards that repeat what is already at the
            // top are switched off; any can be switched back on.
            if (!settingsRepository.settings.first().homeTrimmed) {
                DashboardWidget.entries.filter { it.repeatsHome }.forEach { widgetDao.setVisible(it.key, false) }
                settingsRepository.setHomeTrimmed()
            }
        }
        viewModelScope.launch {
            // Loans are for paying off, and once they are they go.
            accountRepository.observeWithBalances().collect { accounts ->
                val cleared = accountRepository.archivePaidOffLoans(accounts)
                if (cleared.isNotEmpty()) {
                    message.value = cleared.joinToString(" and ") + " paid off — well done. " +
                        (if (cleared.size == 1) "It has" else "They have") +
                        " been put away with their history."
                }
            }
        }
    }

    /** Feedback from the cash pot buttons, shown once and cleared. */
    private val message = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = message

    /** The month the dashboard is showing; the user can step back through history. */
    private val visibleMonth = MutableStateFlow(DateUtils.currentYearMonth())

    private val accounts: StateFlow<List<AccountWithBalance>> =
        accountRepository.observeWithBalances()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The accounts as they stood at the end of the month on screen.
     *
     * Looking back at August used to show August's money in and out beside
     * today's balances, which never matched. For the current month the
     * balances are today's; for an earlier one, each is worked out as it was
     * at that month's close.
     */
    private val accountsForMonth: Flow<List<AccountWithBalance>> =
        combine(accounts, visibleMonth) { list, month -> list to month }
            .mapLatest { (list, month) ->
                if (!month.isBefore(DateUtils.currentYearMonth())) {
                    list
                } else {
                    val end = month.atEndOfMonth()
                    list.map { it.copy(balanceMinor = accountRepository.balanceAsOf(it.account.id, end)) }
                }
            }

    private val monthFlow = combine(visibleMonth, scope) { month, currentScope ->
        month to currentScope
    }

    /** Payments still waiting to be sorted, for the count beside Sort spending. */
    val unsortedCount: StateFlow<Int> = payeeRepository.observeUnsortedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Money sent to and from people in the month and tab on screen. */
    val peopleMoney: StateFlow<PeopleMoney?> = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        payeeRepository.observeMoneyWithPeople(range.start, range.endInclusive, currentScope.personIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The same for the whole year the month is in, for the People card's graph and totals. */
    val peopleYear: StateFlow<PeopleMoney?> = monthFlow.flatMapLatest { (month, currentScope) ->
        payeeRepository.observeMoneyWithPeople(
            java.time.LocalDate.of(month.year, 1, 1),
            java.time.LocalDate.of(month.year, 12, 31),
            currentScope.personIds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val totals = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeIncomeExpense(
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personIds = currentScope.personIds,
        )
    }

    private val spendingByCategory = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeCategoryTotals(
            type = TransactionType.EXPENSE,
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personIds = currentScope.personIds,
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

    /** Money paid off loans this month. */
    private val loanPaymentsThisMonth = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observeLoanPayments(
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personIds = currentScope.personIds,
        )
    }

    private fun potFlow(kind: CategoryKind) = monthFlow.flatMapLatest { (month, currentScope) ->
        val range = DateUtils.monthRange(month)
        transactionRepository.observePotFlow(
            kind = kind,
            start = range.start,
            end = range.endInclusive,
            accountId = currentScope.accountId,
            personIds = currentScope.personIds,
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
            personIds = currentScope.personIds,
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
            accountsForMonth,
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
            cashPotRepository.observeTotal(),
            cashPotRepository.observeRecent(CASH_LOG_LENGTH),
            // Only for saying where the money is when the month on screen is
            // empty. Opening on today's month and finding nothing looks like a
            // broken app when the entries are simply in an earlier month.
            transactionRepository.observeRecent(1),
            loanPaymentsThisMonth,
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
        val cashPot = values[18] as Long
        val cashLog = values[19] as List<CashPotEntryEntity>
        val latest = (values[20] as List<TransactionWithDetails>)
            .firstOrNull()?.transaction?.date
        val loanPayments = values[21] as Long

        val inScope = accountList.filter { currentScope.matches(it) }
        // The month's entries for whoever's tab this is: their own, and those
        // on accounts in view. Every tab used to list the whole household's.
        val owners = accountList.associate { it.account.id to it.account.personId }
        val inScopeIds = inScope.map { it.account.id }.toSet()
        val monthEntries = currentScope.personIds?.let { ids ->
            recent.filter { item ->
                val entry = item.transaction
                (entry.personId ?: owners[entry.accountId]) in ids || entry.accountId in inScopeIds
            }
        } ?: recent
        val unassigned = accountList.count { it.account.personId == null }
        // The cash pot is the household's, not anybody's account, so it is
        // counted when the whole household is on screen and not otherwise —
        // adding it to one person's savings would give them money that is
        // everybody's.
        val potCounts = currentScope.includesHousehold
        val savingsTotal = inScope.filter { it.isSavings }.sumOf { it.balanceMinor } +
            if (potCounts) cashPot else 0L
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
                netWorthMinor = inScope.sumOf { it.netWorthContributionMinor } +
                    if (potCounts) cashPot else 0L,
                monthIncomeMinor = monthTotals.incomeMinor,
                monthExpenseMinor = monthTotals.expenseMinor,
                committedRecurringMinor = committed,
                savingsInMinor = savings.intoPotMinor,
                savingsOutMinor = savings.outOfPotMinor,
                cashPotMinor = cashPot,
                loanPaymentsMinor = loanPayments,
                cashOutMinor = cash.intoPotMinor,
                cashInMinor = cash.outOfPotMinor,
            ),
            spendingByCategory = categories,
            monthlyTrend = trend,
            upcomingBills = upcoming,
            overdueBills = overdue,
            monthTransactions = monthEntries,
            savingsGoals = goals,
            externalData = external,
            topInsight = insights.topPriority,
            insightCount = insights.insights.size,
            accountActivity = activity,
            widgets = mergeWidgets(widgets),
            cashLog = cashLog,
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
                            personIds = currentScope.personIds.orEmpty(),
                            sort = TransactionSort.AMOUNT_DESC,
                        ),
                    ),
                    transactionRepository.observeCategoryTotals(
                        type = TransactionType.EXPENSE,
                        start = previous.start,
                        end = previous.endInclusive,
                        accountId = currentScope.accountId,
                        personIds = currentScope.personIds,
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

    /**
     * Records notes and coins going into the cash pot ([isIn]) or being spent
     * from it.
     *
     * Nothing feeds the pot automatically. Cash taken out of any account is
     * spent where it was taken out; how much of it is still in a wallet is
     * not something the app can know, and a cash total that is quietly wrong
     * is worse than none at all.
     */
    /** Returns false, leaving what was typed alone, when there is no amount to record. */
    fun recordCash(amountText: String, note: String, isIn: Boolean): Boolean {
        val amount = Money.parseOrNull(amountText)
        if (amount == null || amount <= 0L) {
            message.value = "Enter an amount"
            return false
        }
        viewModelScope.launch {
            val result = cashPotRepository.record(
                amountMinor = amount,
                isIn = isIn,
                note = note.ifBlank { if (isIn) "Put in" else "Spent" },
            )
            message.value = result.errorMessageOrNull()
                ?: if (isIn) {
                    "${Money.format(amount)} put in the cash pot"
                } else {
                    "${Money.format(amount)} spent from the cash pot"
                }
        }
        return true
    }

    /** Sets the pot to what was counted in it; see [CashPotRepository.setTotalTo]. */
    /** Returns false, leaving what was typed alone, when there is no amount to set. */
    fun countCash(amountText: String): Boolean {
        val amount = Money.parseOrNull(amountText)
        if (amount == null || amount < 0L) {
            message.value = "Enter what is in the pot"
            return false
        }
        viewModelScope.launch {
            message.value = cashPotRepository.setTotalTo(amount).errorMessageOrNull()
                ?: "Cash pot set to ${Money.format(amount)}"
        }
        return true
    }

    fun removeCashEntry(entry: CashPotEntryEntity) {
        viewModelScope.launch {
            message.value = cashPotRepository.delete(entry).errorMessageOrNull() ?: "Entry removed"
        }
    }

    fun clearMessage() {
        message.value = null
    }

    /** Jumps to the month behind a tapped bar. */
    fun showMonth(month: YearMonth) {
        if (!month.isAfter(DateUtils.currentYearMonth())) visibleMonth.value = month
    }

    /** Shows one person's tab. */
    fun showPerson(personId: Long) {
        choice.value = ScopeChoice.Person(personId)
    }

    /** Shows the Shared tab. */
    fun showShared() {
        choice.value = ScopeChoice.Shared
    }

    /** Sets who the Shared tab covers. */
    fun setSharedPeople(ids: Set<Long>) {
        viewModelScope.launch { settingsRepository.setSharedPeople(ids) }
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
        /** A fortnight without a backup before Home mentions it. */
        private const val BACKUP_NUDGE_AFTER_MS = 14L * 24 * 60 * 60 * 1000

        /** "Later" puts the reminder off for a week. */
        private const val BACKUP_NUDGE_SNOOZE_MS = 7L * 24 * 60 * 60 * 1000

        /** How much of the cash pot's history the card can show. */
        const val CASH_LOG_LENGTH = 50
        const val MONTHS_ON_CHART = 6
        const val UPCOMING_DAYS = 30L
    }
}

/** A tab the user picked on Home. */
sealed interface ScopeChoice {
    data class Person(val personId: Long) : ScopeChoice
    data object Shared : ScopeChoice
}

/**
 * Works out what Home shows for a tab.
 *
 * Nothing picked yet opens on the first person — the app is built around a
 * person. The Shared tab covers the people chosen for it (everybody until
 * somebody chooses) and the joint person, whose accounts are the household's.
 */
internal fun scopeFor(
    picked: ScopeChoice?,
    people: List<PersonEntity>,
    sharedIds: Set<Long>?,
): DashboardScope {
    val individuals = people.filterNot { it.isShared }
    val joint = people.filter { it.isShared }.map { it.id }.toSet()
    return when (picked) {
        is ScopeChoice.Person -> people.firstOrNull { it.id == picked.personId }
            ?.let { DashboardScope(personIds = setOf(it.id), label = it.name) }
            ?: DashboardScope()
        ScopeChoice.Shared -> {
            val chosen = sharedIds?.filter { id -> individuals.any { it.id == id } }?.toSet()
            val members = chosen ?: individuals.map { it.id }.toSet()
            DashboardScope(
                personIds = if (chosen == null) null else members + joint,
                isShared = true,
                label = "Shared",
            )
        }
        null -> individuals.firstOrNull()
            ?.let { DashboardScope(personIds = setOf(it.id), label = it.name) }
            ?: DashboardScope()
    }
}

/** Which slice of the household the dashboard is showing. */
data class DashboardScope(
    /** Whose money; null for everybody. */
    val personIds: Set<Long>? = null,
    val accountId: Long? = null,
    /** True for the Shared tab rather than one person's. */
    val isShared: Boolean = false,
    val label: String = "Everyone",
) {
    /** The one person whose tab this is, or null for the Shared tab or everybody. */
    val personId: Long? get() = if (isShared) null else personIds?.singleOrNull()

    /**
     * True when the household's own things — the cash pot — belong on this
     * view: the Shared tab, or everybody. Never one person's tab, because
     * the pot is everybody's.
     */
    val includesHousehold: Boolean get() = accountId == null && (isShared || personIds == null)

    fun matches(account: AccountWithBalance): Boolean = when {
        accountId != null -> account.account.id == accountId
        personIds == null -> true
        else -> account.account.personId in personIds || account.account.isShared
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
    /** What has gone into and out of the cash pot, newest first. */
    val cashLog: List<CashPotEntryEntity> = emptyList(),

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

    /**
     * Yearly pay for whoever is on screen: one person, or everybody together.
     * Empty when looking at a single account, which has no pay of its own.
     */
    val incomeInScope: IncomeStats
        get() = when {
            scope.accountId != null -> IncomeStats.NONE
            scope.personIds != null -> people.filter { it.id in scope.personIds }
                .fold(IncomeStats.NONE) { total, person ->
                    total + IncomeStats(person.grossYearlyIncomeMinor, person.netYearlyIncomeMinor)
                }
            else -> people.fold(IncomeStats.NONE) { total, person ->
                total + IncomeStats(person.grossYearlyIncomeMinor, person.netYearlyIncomeMinor)
            }
        }
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
