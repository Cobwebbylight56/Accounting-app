package com.rhys.financetracker.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.rhys.financetracker.data.prefs.SettingsRepository
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Registers the periodic background work.
 *
 * `KEEP` is used for the jobs whose timing does not change, so re-scheduling on
 * every launch does not reset their clocks.  The reminder job uses `UPDATE`
 * because the user can move the hour it runs at.
 */
@Singleton
class WorkScheduler @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun scheduleAll() {
        val settings = settingsRepository.settings.first()
        scheduleRollover()
        scheduleReminders(settings.reminderHour)
        scheduleBackup(settings.autoBackupEnabled)
        scheduleExternalData(settings.externalDataEnabled)
    }

    /** Daily, shortly after midnight, so a new month is ready when the user wakes. */
    private fun scheduleRollover() {
        val request = PeriodicWorkRequestBuilder<RolloverWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntil(LocalTime.of(0, 15)))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            RolloverWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun scheduleReminders(hour: Int) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntil(LocalTime.of(hour.coerceIn(0, 23), 0)))
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReminderWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleBackup(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(BackupWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(Duration.ofDays(1))
            .setInitialDelay(delayUntil(LocalTime.of(2, 0)))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            BackupWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun scheduleExternalData(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(ExternalDataWorker.NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ExternalDataWorker>(Duration.ofDays(1))
            .setConstraints(
                Constraints.Builder()
                    // Never spend the user's mobile data on optional figures.
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            ExternalDataWorker.NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** How long until the next occurrence of [time]. */
    private fun delayUntil(time: LocalTime): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(time)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }
}
