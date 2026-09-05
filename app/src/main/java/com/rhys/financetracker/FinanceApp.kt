package com.rhys.financetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.rhys.financetracker.data.repository.SeedRepository
import com.rhys.financetracker.di.ApplicationScope
import com.rhys.financetracker.domain.rollover.MonthlyRolloverEngine
import com.rhys.financetracker.notify.NotificationChannels
import com.rhys.financetracker.security.AppLockManager
import com.rhys.financetracker.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * On every launch it:
 *  * seeds the database if it is empty;
 *  * runs the monthly rollover, which also generates any recurring
 *    transactions that fell due while the app was closed;
 *  * makes sure the background workers are scheduled.
 *
 * All of that happens off the main thread in [applicationScope], so a slow
 * first start never blocks the UI.
 */
@HiltAndroidApp
class FinanceApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var seedRepository: SeedRepository
    @Inject lateinit var rolloverEngine: MonthlyRolloverEngine
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var appLockManager: AppLockManager

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /** Lets Hilt inject dependencies into WorkManager workers. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        NotificationChannels.createAll(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLockManager)

        applicationScope.launch {
            seedRepository.seedIfEmpty()
            // Catch up on anything that fell due while the app was closed.
            rolloverEngine.runRollover()
            workScheduler.scheduleAll()
        }
    }
}
