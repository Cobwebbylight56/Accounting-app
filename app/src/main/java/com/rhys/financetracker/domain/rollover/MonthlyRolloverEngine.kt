package com.rhys.financetracker.domain.rollover

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.MonthlySnapshotDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.MonthlySnapshotEntity
import com.rhys.financetracker.domain.recurrence.RecurringTransactionGenerator
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closes off finished months.
 *
 * At the start of a new month the app:
 *  1. generates any recurring transactions that are now due;
 *  2. writes an immutable [MonthlySnapshotEntity] per account for each finished
 *     month that has not been archived yet;
 *  3. leaves every transaction exactly as it was.
 *
 * Balances "carry over" implicitly: an account's balance is its opening balance
 * plus all of its transactions, so the closing figure for March *is* the
 * opening figure for April.  There is deliberately no "carry-over transaction"
 * — inventing one would double-count and would make the ledger disagree with
 * the bank.
 *
 * Nothing here overwrites data.  Re-running the rollover for a month that is
 * already archived is a no-op unless [rebuild] is set, which is offered in
 * Settings for the rare case where a month was archived from data that was
 * later corrected.
 */
@Singleton
class MonthlyRolloverEngine @Inject constructor(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    private val snapshotDao: MonthlySnapshotDao,
    private val generator: RecurringTransactionGenerator,
) {

    /**
     * Archives every completed month that has not been archived yet, oldest
     * first, and generates recurring entries up to today.
     *
     * @param rebuild when true, months that already have snapshots are
     *   recalculated instead of being skipped.
     */
    suspend fun runRollover(
        today: java.time.LocalDate = DateUtils.today(),
        rebuild: Boolean = false,
    ): AppResult<RolloverSummary> = runCatchingApp("Could not complete the monthly update") {
        // 1. Bring recurring entries up to date first, so the archived figures
        //    include this month's bills.
        val generation = generator.generateDue(today)

        val currentMonth = YearMonth.from(today)
        val earliest = transactionDao.earliestDate()
            ?: return@runCatchingApp RolloverSummary(
                monthsArchived = emptyList(),
                transactionsGenerated = generation.getOrNull()?.transactionsCreated ?: 0,
            )

        var month = YearMonth.from(earliest)
        val archived = mutableListOf<String>()

        // 2. Archive every month strictly before the current one.
        while (month.isBefore(currentMonth)) {
            val key = DateUtils.yearMonthKey(month)
            val alreadyDone = snapshotDao.hasMonth(key)
            if (!alreadyDone || rebuild) {
                if (alreadyDone) snapshotDao.deleteMonth(key)
                val snapshots = buildSnapshots(month)
                if (snapshots.isNotEmpty()) {
                    snapshotDao.insertAll(snapshots)
                    archived += key
                }
            }
            month = month.plusMonths(1)
        }

        RolloverSummary(
            monthsArchived = archived,
            transactionsGenerated = generation.getOrNull()?.transactionsCreated ?: 0,
        )
    }

    /**
     * Builds — but does not store — the snapshot rows for [month].  Exposed so
     * the reports screen can show a month that has not been archived yet using
     * exactly the same arithmetic.
     */
    suspend fun buildSnapshots(month: YearMonth): List<MonthlySnapshotEntity> {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        val previousEnd = start.minusDays(1)

        return accountDao.getAll().map { account ->
            val totals = transactionDao.getAccountIncomeExpense(account.id, start, end)
            val transfersIn = transactionDao.getTransfersIn(account.id, start, end)
            val transfersOut = transactionDao.getTransfersOut(account.id, start, end)
            val opening = accountDao.getBalanceAsOf(account.id, previousEnd)
                ?: account.openingBalanceMinor
            val closing = accountDao.getBalanceAsOf(account.id, end)
                ?: account.openingBalanceMinor

            MonthlySnapshotEntity(
                yearMonth = DateUtils.yearMonthKey(month),
                accountId = account.id,
                openingBalanceMinor = opening,
                closingBalanceMinor = closing,
                totalIncomeMinor = totals?.incomeMinor ?: 0L,
                totalExpenseMinor = totals?.expenseMinor ?: 0L,
                totalTransfersInMinor = transfersIn,
                totalTransfersOutMinor = transfersOut,
                transactionCount = transactionDao.countForAccountBetween(account.id, start, end),
            )
        }
    }
}

/** The outcome of a rollover run, reported in a notification and in Settings. */
data class RolloverSummary(
    /** Year-month keys that were archived by this run. */
    val monthsArchived: List<String>,
    val transactionsGenerated: Int,
) {
    val didAnything: Boolean get() = monthsArchived.isNotEmpty() || transactionsGenerated > 0
}
