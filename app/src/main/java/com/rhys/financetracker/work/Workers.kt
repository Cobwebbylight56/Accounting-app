package com.rhys.financetracker.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.backup.BackupManager
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.remote.ExternalDataRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.RecurringRepository
import com.rhys.financetracker.data.repository.SavingsRepository
import com.rhys.financetracker.domain.rollover.MonthlyRolloverEngine
import com.rhys.financetracker.notify.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Background jobs.
 *
 * Everything here is idempotent and safe to run more than once — WorkManager
 * gives at-least-once delivery, and a phone that has been off for a week will
 * run several of these in quick succession when it comes back.
 */

/**
 * Runs the monthly rollover and generates any recurring transactions that have
 * fallen due.  Scheduled daily rather than monthly so that a phone that is off
 * on the 1st still catches up on the 2nd.
 */
@HiltWorker
class RolloverWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val rolloverEngine: MonthlyRolloverEngine,
    private val settingsRepository: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val result = rolloverEngine.runRollover()
        val summary = result.getOrNull() ?: return Result.retry()

        if (summary.didAnything) {
            notifier.notifyRollover(summary.monthsArchived.size, summary.transactionsGenerated)
        }
        settingsRepository.setLastRolloverMonth(
            DateUtils.yearMonthKey(DateUtils.currentYearMonth()),
        )
        return Result.success()
    }

    companion object {
        const val NAME = "rollover"
    }
}

/**
 * Raises reminders for bills that are due soon or already overdue, and warns
 * about accounts running low.  Runs once a day at the hour the user chose.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val recurringRepository: RecurringRepository,
    private val accountRepository: AccountRepository,
    private val savingsRepository: SavingsRepository,
    private val settingsRepository: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        val today = DateUtils.today()

        if (settings.notifyBills) {
            val upcoming = recurringRepository.observeUpcoming(days = LOOK_AHEAD_DAYS, today = today)
                .first()
                .filter { item ->
                    // Each rule decides how much notice it wants.
                    val notice = (item.rule.reminderDaysBefore ?: DEFAULT_REMINDER_DAYS).toLong()
                    DateUtils.daysBetween(today, item.rule.nextDueDate) <= notice
                }
            if (upcoming.isNotEmpty()) {
                notifier.notifyUpcomingBills(
                    count = upcoming.size,
                    totalMinor = upcoming.sumOf { it.rule.amountMinor },
                    nextDue = upcoming.minOf { it.rule.nextDueDate },
                )
            }
        }

        if (settings.notifyOverdue) {
            val overdue = recurringRepository.observeOverdue(today).first()
            if (overdue.isNotEmpty()) {
                notifier.notifyOverdueBills(
                    count = overdue.size,
                    totalMinor = overdue.sumOf { it.rule.amountMinor },
                )
            }
        }

        if (settings.notifyLowBalance) {
            accountRepository.observeLowBalance().first().forEach { account ->
                notifier.notifyLowBalance(account.account.name, account.balanceMinor)
            }
        }

        if (settings.notifyGoals) {
            savingsRepository.observeWithProgress().first()
                // Only worth mentioning at a milestone, not every single day.
                .filter { it.percentComplete in MILESTONES }
                .forEach { notifier.notifyGoalProgress(it.goal.name, it.percentComplete) }
        }

        return Result.success()
    }

    companion object {
        const val NAME = "reminders"
        const val DEFAULT_REMINDER_DAYS = 3
        const val LOOK_AHEAD_DAYS = 7L
        private val MILESTONES = setOf(25, 50, 75, 100)
    }
}

/** Writes an automatic backup to the folder the user picked. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settingsRepository: SettingsRepository,
    private val notifier: Notifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.autoBackupEnabled) return Result.success()
        val folder = settings.autoBackupFolderUri ?: return Result.success()

        return when (val result = backupManager.backupToFolder(folder, settings.autoBackupKeep)) {
            is com.rhys.financetracker.core.result.AppResult.Success -> {
                settingsRepository.setLastBackupAt(System.currentTimeMillis())
                notifier.notifyBackup(success = true, detail = result.data.fileName)
                Result.success()
            }
            is com.rhys.financetracker.core.result.AppResult.Failure -> {
                notifier.notifyBackup(success = false, detail = result.message)
                // Transient problems (the folder is on a card that is not
                // mounted) are worth retrying; the next daily run will try again.
                Result.retry()
            }
        }
    }

    companion object {
        const val NAME = "auto_backup"
    }
}

/** Refreshes the optional external figures, when the user has enabled them. */
@HiltWorker
class ExternalDataWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val externalDataRepository: ExternalDataRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        if (!settings.externalDataEnabled) return Result.success()
        externalDataRepository.refreshAll()
        // A failed refresh is recorded against the individual figure, so the
        // job itself always succeeds; retrying would just spend battery.
        return Result.success()
    }

    companion object {
        const val NAME = "external_data"
    }
}
