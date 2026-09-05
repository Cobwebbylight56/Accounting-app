package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.data.local.seed.SampleData
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Populates an empty database, and loads the worked example from the original
 * spreadsheet on request.
 *
 * Both operations are idempotent: seeding checks whether the rows already exist
 * and does nothing if they do, so it is safe to call on every start.
 */
@Singleton
class SeedRepository @Inject constructor(
    private val database: AppDatabase,
) {

    /**
     * Ensures the categories, the shared person and the dashboard layout exist.
     * Called once per launch from `FinanceApp`.
     */
    suspend fun seedIfEmpty(): AppResult<Boolean> =
        runCatchingApp("Could not prepare the database") {
            var didSeed = false

            if (database.categoryDao().count() == 0) {
                seedCategories()
                didSeed = true
            }
            if (database.personDao().count() == 0) {
                database.personDao().insertAll(DefaultData.defaultPeople())
                didSeed = true
            }
            if (database.dashboardWidgetDao().count() == 0) {
                database.dashboardWidgetDao().upsertAll(DefaultData.defaultDashboardWidgets())
                didSeed = true
            }
            didSeed
        }

    /**
     * Inserts the example household from the "Book r and h" spreadsheet:
     * two people, seven accounts, both salaries and every bill.
     *
     * @return the number of records created.
     */
    suspend fun loadSampleData(startFrom: LocalDate = DateUtils.startOfMonth(DateUtils.today())):
        AppResult<Int> = runCatchingApp("Could not load the sample data") {
        seedIfEmpty()
        var created = 0

        // --- people ------------------------------------------------------
        val peopleIds = mutableMapOf<String, Long>()
        DefaultData.defaultPeople().forEach { shared ->
            val existing = database.personDao().getByName(shared.name)
            peopleIds[shared.name] = existing?.id ?: database.personDao().insert(shared)
        }
        SampleData.people.forEachIndexed { index, person ->
            val existing = database.personDao().getByName(person.name)
            peopleIds[person.name] = existing?.id ?: database.personDao().insert(
                PersonEntity(
                    name = person.name,
                    colorHex = person.colorHex,
                    sortOrder = index,
                ),
            ).also { created++ }
        }

        // --- accounts ----------------------------------------------------
        val accountIds = mutableMapOf<String, Long>()
        SampleData.accounts.forEachIndexed { index, account ->
            val existing = database.accountDao().getByName(account.name)
            accountIds[account.name] = existing?.id ?: database.accountDao().insert(
                AccountEntity(
                    name = account.name,
                    type = account.type,
                    personId = peopleIds[account.personName],
                    openingBalanceMinor = Money.fromMajor(account.openingBalanceMajor),
                    openingBalanceDate = startFrom,
                    colorHex = account.colorHex,
                    sortOrder = index,
                    notes = account.notes,
                ),
            ).also { created++ }
        }

        // --- recurring income, bills and savings transfers ----------------
        val categories = database.categoryDao().getAll().associateBy { it.name.lowercase() }
        val savingsDestination = accountIds[SampleData.SAVINGS_DESTINATION_ACCOUNT]

        SampleData.allRules.forEach { rule ->
            val accountId = accountIds[rule.accountName] ?: return@forEach
            val dueDate = DateUtils.safeDayOfMonth(
                java.time.YearMonth.from(startFrom),
                rule.dayOfMonth,
            )
            database.recurringRuleDao().insert(
                RecurringRuleEntity(
                    name = rule.name,
                    amountMinor = Money.fromMajor(rule.amountMajor),
                    type = rule.type,
                    frequency = rule.frequency,
                    startDate = dueDate,
                    nextDueDate = dueDate,
                    accountId = accountId,
                    transferAccountId = if (rule.type == TransactionType.TRANSFER) {
                        savingsDestination
                    } else {
                        null
                    },
                    categoryId = categories[rule.categoryName.lowercase()]?.id,
                    personId = peopleIds[rule.personName],
                    isVariableAmount = rule.isVariableAmount,
                    // Variable bills ask for confirmation; fixed ones just post.
                    reminderDaysBefore = if (rule.type == TransactionType.EXPENSE) 3 else null,
                    notes = rule.notes,
                ),
            )
            created++
        }

        // --- savings goals ------------------------------------------------
        SampleData.goals.forEachIndexed { index, goal ->
            database.savingsGoalDao().insert(
                SavingsGoalEntity(
                    name = goal.name,
                    targetAmountMinor = Money.fromMajor(goal.targetMajor),
                    monthlyContributionMinor = Money.fromMajor(goal.monthlyContributionMajor),
                    accountId = goal.accountName?.let { accountIds[it] },
                    startDate = startFrom,
                    targetDate = startFrom.plusYears(1),
                    colorHex = goal.colorHex,
                    iconKey = goal.iconKey,
                    notes = goal.notes,
                    sortOrder = index,
                ),
            )
            created++
        }

        created
    }

    /**
     * Removes every record but keeps the app usable, re-seeding the defaults.
     * Offered in Settings behind a confirmation, and used by "restore" before
     * a backup is written in.
     */
    suspend fun clearAllData(): AppResult<Unit> =
        runCatchingApp("Could not clear the data") {
            database.transactionDao().deleteAll()
            database.recurringRuleDao().deleteAll()
            database.savingsGoalDao().deleteAll()
            database.monthlySnapshotDao().deleteAll()
            database.accountDao().deleteAll()
            database.categoryDao().deleteAll()
            database.personDao().deleteAll()
            database.externalDataDao().deleteAll()
            seedIfEmpty()
        }

    /** Inserts the default categories, resolving parent references by name. */
    private suspend fun seedCategories() {
        val seeds = DefaultData.defaultCategories()
        val idsByName = mutableMapOf<String, Long>()

        // Parents first, so a child always has an id to point at.
        seeds.filter { it.parent == null }.forEachIndexed { index, seed ->
            idsByName[seed.name] = database.categoryDao().insert(seed.toEntity(index, null))
        }
        seeds.filter { it.parent != null }.forEachIndexed { index, seed ->
            val parentId = idsByName[seed.parent]
            idsByName[seed.name] = database.categoryDao()
                .insert(seed.toEntity(1_000 + index, parentId))
        }
    }
}
