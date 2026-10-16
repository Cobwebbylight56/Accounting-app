package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.report.ProgressFigures
import com.rhys.financetracker.domain.report.ProgressReport
import com.rhys.financetracker.domain.report.ProgressReporter
import com.rhys.financetracker.domain.report.ProgressSchedule
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Makes the start-of-month and halfway reports — for the household and for
 * each person — keeps them, and says which are new.
 *
 * A report waits until the stretch it covers has its payments in: a month
 * judged before its last statement is imported would look like a month of
 * very little spending.
 */
@Singleton
class ProgressRepository @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val transactionDao: TransactionDao,
    private val accountRepository: AccountRepository,
    private val peopleRepository: PeopleRepository,
    private val settingsRepository: SettingsRepository,
) {

    /** Makes this period's reports if they're due and not made yet; returns the new ones. */
    suspend fun makeDue(today: LocalDate = DateUtils.today()): List<ProgressReport> {
        val period = ProgressSchedule.current(today)
        val settings = settingsRepository.settings.first()
        if (settings.progressChecked == period.key) return emptyList()
        if (settings.progressReports.any { it.key.startsWith("${period.key}|") }) return emptyList()
        // Not until there are payments from the last few days of the stretch.
        if (transactionDao.liveSince(period.now.endInclusive.minusDays(IN_BY_DAYS)).isEmpty()) return emptyList()

        val individuals = peopleRepository.activePeople().filterNot { it.isShared }
        val scopes = buildList {
            add(Scope(if (individuals.size >= 2) TOGETHER else OVERALL, "all", null, null))
            if (individuals.size >= 2) {
                individuals.forEach { add(Scope(it.name.substringBefore(' '), it.id.toString(), setOf(it.id), it.id)) }
            }
        }
        val reports = scopes.mapNotNull { scope ->
            ProgressReporter.build(
                period = period,
                scope = scope.label,
                scopeKey = scope.key,
                now = figures(period.now, scope),
                before = figures(period.before, scope),
                madeOn = today,
                beforeWholeMonthSpentMinor = period.beforeWholeMonth?.let { whole ->
                    transactionRepository.observeIncomeExpense(whole.start, whole.endInclusive, null, scope.personIds)
                        .first().expenseMinor
                },
            )
        }
        settingsRepository.addProgressReports(period.key, reports)
        return reports
    }

    private suspend fun figures(range: ClosedRange<LocalDate>, scope: Scope): ProgressFigures {
        val start = range.start
        val end = range.endInclusive
        val totals = transactionRepository.observeIncomeExpense(start, end, null, scope.personIds).first()
        val saved = transactionRepository.observePotFlow(CategoryKind.SAVING, start, end, null, scope.personIds).first()
        val byCategory = transactionRepository.observeCategoryTotals(TransactionType.EXPENSE, start, end, null, scope.personIds)
            .first()
            .filter { it.categoryName != null }
            .associate { it.categoryName!! to it.totalMinor }
        return ProgressFigures(
            moneyInMinor = totals.incomeMinor,
            spentMinor = totals.expenseMinor,
            savedMinor = saved.netMinor,
            owedChangeMinor = owed(end, scope) - owed(start.minusDays(1), scope),
            byCategory = byCategory,
        )
    }

    /** What's owed on the cards and loans in [scope] at the end of [day]. */
    private suspend fun owed(day: LocalDate, scope: Scope): Long =
        accountRepository.getAll()
            .filter { !it.isArchived && it.holding == Holding.OWED }
            .filter { scope.personId == null || (it.personId == scope.personId && !it.isShared) }
            .sumOf { -accountRepository.balanceAsOf(it.id, day) }

    private data class Scope(val label: String, val key: String, val personIds: Set<Long>?, val personId: Long?)

    private companion object {
        const val TOGETHER = "Together"
        const val OVERALL = "Overall"

        /** A stretch counts as in once there are payments from its last few days. */
        const val IN_BY_DAYS = 3L
    }
}
