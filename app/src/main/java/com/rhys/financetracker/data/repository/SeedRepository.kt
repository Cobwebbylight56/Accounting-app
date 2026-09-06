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
            // Topped up rather than seeded once. Checking only for an empty
            // table meant a card added in a later version never appeared for
            // anybody who had already run the app: their row was missing and
            // the table was not empty, so nothing was ever inserted. The
            // charts were added after the first release, which is exactly the
            // case that went missing.
            // The same top-up as the widgets, and for the same reason: a
            // category added in a later version was never inserted for anybody
            // who had already run the app, because the table was not empty.
            // "Cash" arrived that way, and without this the one install that
            // needed it would never have seen it.
            if (seedMissingCategories()) didSeed = true
            if (seedMissingWidgets()) didSeed = true
            didSeed
        }

    /**
     * Adds any default category the database does not already hold by name.
     *
     * Matched on the name alone: somebody who made their own "Cash" category
     * should keep theirs rather than be given a second one beside it.
     *
     * @return true when anything was added.
     */
    private suspend fun seedMissingCategories(): Boolean {
        val existing = database.categoryDao().getAll()
        if (existing.isEmpty()) return false
        val byName = existing.associateBy { it.name }.toMutableMap()
        val missing = DefaultData.defaultCategories().filter { it.name !in byName }
        if (missing.isEmpty()) return false

        var order = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
        // Parents first, so a child added at the same time has an id to point
        // at rather than being orphaned.
        for (seed in missing.filter { it.parent == null } + missing.filter { it.parent != null }) {
            val parentId = seed.parent?.let { byName[it]?.id }
            val id = database.categoryDao().insert(seed.toEntity(order++, parentId))
            byName[seed.name] = seed.toEntity(order, parentId).copy(id = id)
        }
        return true
    }

    /**
     * Adds a row for any dashboard card that has none, keeping the positions
     * and visibility of the ones already there.
     *
     * @return true when anything was added.
     */
    private suspend fun seedMissingWidgets(): Boolean {
        val existing = database.dashboardWidgetDao().getAll()
        val known = existing.map { it.widgetKey }.toSet()
        val missing = DefaultData.defaultDashboardWidgets()
            .filter { it.widgetKey !in known }
        if (missing.isEmpty()) return false

        // Placed after whatever is already on screen, so a new card never
        // displaces the order somebody has arranged.
        val nextPosition = (existing.maxOfOrNull { it.position } ?: -1) + 1
        database.dashboardWidgetDao().upsertAll(
            missing.mapIndexed { index, widget ->
                widget.copy(position = nextPosition + index)
            },
        )
        return true
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
