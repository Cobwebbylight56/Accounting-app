package com.rhys.financetracker.di

import android.content.Context
import androidx.room.Room
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.DashboardWidgetDao
import com.rhys.financetracker.data.local.dao.ExternalDataDao
import com.rhys.financetracker.data.local.dao.ImportProfileDao
import com.rhys.financetracker.data.local.dao.MonthlySnapshotDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.SavingsGoalDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.migration.Migrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Provides the database and its DAOs.
 *
 * Note the deliberate absence of `fallbackToDestructiveMigration()`: if a
 * migration is missing the app fails loudly in development rather than quietly
 * deleting the user's financial history in production.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .addMigrations(*Migrations.ALL)
            // Room's own default is a fixed pool of four threads, shared by
            // every observed query AND by the invalidation refresh that tells
            // those queries something changed. The dashboard alone keeps
            // fourteen queries live, several of them totalling every
            // transaction ever recorded, so after a write the refresh queues
            // up behind all of them and the screen can sit on stale figures.
            // A restart re-runs the queries from scratch, which is why closing
            // and reopening the app "fixes" it.
            .setQueryExecutor(Dispatchers.IO.asExecutor())
            .setTransactionExecutor(Dispatchers.IO.asExecutor())
            // Foreign keys are enforced so orphaned rows cannot appear.
            .build()

    @Provides fun providePersonDao(db: AppDatabase): PersonDao = db.personDao()
    @Provides fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideRecurringRuleDao(db: AppDatabase): RecurringRuleDao = db.recurringRuleDao()
    @Provides fun provideSavingsGoalDao(db: AppDatabase): SavingsGoalDao = db.savingsGoalDao()
    @Provides fun provideSnapshotDao(db: AppDatabase): MonthlySnapshotDao = db.monthlySnapshotDao()
    @Provides fun provideExternalDataDao(db: AppDatabase): ExternalDataDao = db.externalDataDao()
    @Provides fun provideWidgetDao(db: AppDatabase): DashboardWidgetDao = db.dashboardWidgetDao()
    @Provides fun provideImportProfileDao(db: AppDatabase): ImportProfileDao =
        db.importProfileDao()
}
