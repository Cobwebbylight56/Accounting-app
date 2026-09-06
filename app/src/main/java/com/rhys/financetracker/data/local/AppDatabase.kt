package com.rhys.financetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.local.entity.ExternalDataEntity
import com.rhys.financetracker.data.local.entity.ImportProfileEntity
import com.rhys.financetracker.data.local.entity.MonthlySnapshotEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity

/**
 * The application database.
 *
 * ## Changing the schema
 * 1. Edit the entity.
 * 2. Increase [DATABASE_VERSION].
 * 3. Add a `Migration` to `data/local/migration/Migrations.kt` and register it
 *    in `di/DatabaseModule.kt`.
 *
 * Never use `fallbackToDestructiveMigration()` in a release build — the whole
 * point of this app is that the user's history is never lost.  The exported
 * schema files under `app/schemas` let migrations be tested.
 */
@Database(
    entities = [
        PersonEntity::class,
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        RecurringRuleEntity::class,
        SavingsGoalEntity::class,
        MonthlySnapshotEntity::class,
        ExternalDataEntity::class,
        DashboardWidgetEntity::class,
        ImportProfileEntity::class,
    ],
    version = AppDatabase.DATABASE_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun savingsGoalDao(): SavingsGoalDao
    abstract fun monthlySnapshotDao(): MonthlySnapshotDao
    abstract fun externalDataDao(): ExternalDataDao
    abstract fun dashboardWidgetDao(): DashboardWidgetDao
    abstract fun importProfileDao(): ImportProfileDao

    companion object {
        const val DATABASE_VERSION = 3
        const val DATABASE_NAME = "finance_tracker.db"
    }
}
