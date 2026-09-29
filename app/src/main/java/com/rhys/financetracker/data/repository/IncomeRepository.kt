package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.IncomeChangeDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import java.time.YearMonth
import com.rhys.financetracker.data.local.entity.IncomeChangeEntity
import com.rhys.financetracker.domain.income.PayRise
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Yearly pay and how it changes.
 *
 * The person record holds what somebody earns now; this keeps how it got
 * there. A rise dated today or earlier moves the person's pay at once. One
 * dated later waits and is applied on its day by [applyDue], which Home runs
 * each time it opens.
 */
@Singleton
class IncomeRepository @Inject constructor(
    private val incomeChangeDao: IncomeChangeDao,
    private val personDao: PersonDao,
    private val recurringRuleDao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
    private val categoryRepository: CategoryRepository,
) {

    /** The regular payment that is [personId]'s wage, if one is set up. */
    fun observeWage(personId: Long): Flow<RecurringRuleEntity?> =
        recurringRuleDao.observeWage(personId, SpreadsheetImporter.WAGE_MARKER)

    /**
     * Pays [amountMinor] into [accountId] on [dayOfMonth] every month —
     * 31 meaning the last day — as [personId]'s wage.
     *
     * It is posted by itself on the day, so the month's money in is right
     * before any statement arrives. When the statement does arrive, its wage
     * replaces this one — at the bank's figure, overtime and all.
     */
    suspend fun setWage(
        personId: Long,
        accountId: Long,
        amountMinor: Long,
        dayOfMonth: Int,
        today: LocalDate = DateUtils.today(),
    ): AppResult<Long> = runCatchingApp("Could not set up the wage") {
        require(amountMinor > 0L) { "Enter the take-home for a month" }
        val person = personDao.getById(personId) ?: error("That person no longer exists")
        val day = dayOfMonth.coerceIn(1, 31)
        // Anchored on a month that has the day, so "the 31st" means the last
        // day of every month rather than drifting to the 30th.
        var anchor = YearMonth.from(today)
        while (anchor.lengthOfMonth() < day || anchor.atDay(day).isAfter(today)) {
            anchor = anchor.minusMonths(1)
        }
        var next = DateUtils.safeDayOfMonth(YearMonth.from(today), day)
        if (next.isBefore(today)) next = DateUtils.safeDayOfMonth(YearMonth.from(today).plusMonths(1), day)
        val salary = categoryRepository.findOrCreate(
            SpreadsheetImporter.SALARY_CATEGORY, CategoryKind.INCOME, SALARY_COLOUR,
        )
        val existing = recurringRuleDao.getWage(personId, SpreadsheetImporter.WAGE_MARKER)
        val rule = RecurringRuleEntity(
            id = existing?.id ?: 0L,
            name = "${person.name.substringBefore(' ')}'s wage",
            amountMinor = amountMinor,
            type = TransactionType.INCOME,
            frequency = Frequency.MONTHLY,
            startDate = anchor.atDay(day),
            nextDueDate = next,
            lastGeneratedDate = existing?.lastGeneratedDate,
            accountId = accountId,
            categoryId = salary.id,
            personId = personId,
            mode = RecurrenceMode.AUTO_POST,
            notes = SpreadsheetImporter.WAGE_MARKER,
        )
        if (existing == null) {
            recurringRuleDao.insert(rule)
        } else {
            recurringRuleDao.update(rule)
            existing.id
        }
    }

    /** Stops paying the wage in by itself. */
    suspend fun stopWage(personId: Long): AppResult<Unit> = runCatchingApp("Could not stop the wage") {
        val wage = recurringRuleDao.getWage(personId, SpreadsheetImporter.WAGE_MARKER) ?: return@runCatchingApp
        recurringRuleDao.setArchived(wage.id, true, Instant.now().toEpochMilli())
    }

    /**
     * Records overtime or other extra pay for [personId], into [accountId].
     *
     * Kept as its own entry so the month is right straight away. When the
     * statement's wage replaces the app's, it already includes the overtime,
     * so this entry is folded into it rather than counted twice.
     */
    suspend fun addOvertime(
        personId: Long,
        accountId: Long,
        amountMinor: Long,
        date: LocalDate,
        note: String?,
    ): AppResult<Long> = runCatchingApp("Could not record the overtime") {
        require(amountMinor > 0L) { "Enter the overtime" }
        val overtime = categoryRepository.findOrCreate(
            SpreadsheetImporter.OVERTIME_CATEGORY, CategoryKind.INCOME, OVERTIME_COLOUR,
        )
        transactionDao.insert(
            TransactionEntity(
                amountMinor = amountMinor,
                type = TransactionType.INCOME,
                date = date,
                description = note?.trim()?.takeIf { it.isNotEmpty() } ?: "Overtime",
                accountId = accountId,
                categoryId = overtime.id,
                personId = personId,
                source = RecordSource.MANUAL,
            ),
        )
    }

    fun observeHistory(personId: Long): Flow<List<IncomeChangeEntity>> =
        incomeChangeDao.observeForPerson(personId)

    /**
     * Records a change to [personId]'s pay: [newGrossMinor] before tax and,
     * when known, [newNetMinor] take-home. Without a take-home it is
     * estimated from the old one and marked as an estimate.
     */
    suspend fun addChange(
        personId: Long,
        effectiveDate: LocalDate,
        newGrossMinor: Long,
        newNetMinor: Long?,
        reason: String?,
        today: LocalDate = DateUtils.today(),
    ): AppResult<IncomeChangeEntity> = runCatchingApp("Could not record that pay change") {
        val person = personDao.getById(personId) ?: error("That person no longer exists")
        require(newGrossMinor > 0L) { "Enter the new pay" }
        // What it was just before: the latest change still waiting, if there
        // is one, otherwise what they earn now.
        val waiting = incomeChangeDao.getPending(personId)
            .filter { it.effectiveDate <= effectiveDate }
            .maxByOrNull { it.effectiveDate }
        val previousGross = waiting?.newGrossMinor ?: person.grossYearlyIncomeMinor
        val previousNet = waiting?.newNetMinor ?: person.netYearlyIncomeMinor
        val estimated = newNetMinor == null
        val net = newNetMinor ?: PayRise.estimateNet(previousGross, previousNet, newGrossMinor)
        val change = IncomeChangeEntity(
            personId = personId,
            effectiveDate = effectiveDate,
            previousGrossMinor = previousGross,
            previousNetMinor = previousNet,
            newGrossMinor = newGrossMinor,
            newNetMinor = net,
            netIsEstimate = estimated && net != null,
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
        )
        val id = incomeChangeDao.insert(change)
        applyDue(today)
        incomeChangeDao.getById(id) ?: change.copy(id = id)
    }

    /** Moves everybody's pay on for every change whose day has come. */
    suspend fun applyDue(today: LocalDate = DateUtils.today()): Int {
        val due = incomeChangeDao.getDue(today)
        due.forEach { change ->
            val person = personDao.getById(change.personId) ?: return@forEach
            personDao.update(
                person.copy(
                    grossYearlyIncomeMinor = change.newGrossMinor ?: person.grossYearlyIncomeMinor,
                    netYearlyIncomeMinor = change.newNetMinor ?: person.netYearlyIncomeMinor,
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
            incomeChangeDao.update(change.copy(isApplied = true))
            // The wage paid in each month follows the new take-home.
            change.newNetMinor?.let { net ->
                recurringRuleDao.getWage(change.personId, SpreadsheetImporter.WAGE_MARKER)?.let { wage ->
                    recurringRuleDao.update(wage.copy(amountMinor = net / 12L))
                }
            }
        }
        return due.size
    }

    /**
     * Removes a change. If it is what the person earns now, their pay goes
     * back to what it was before it.
     */
    suspend fun delete(change: IncomeChangeEntity): AppResult<Unit> =
        runCatchingApp("Could not remove that pay change") {
            val person = personDao.getById(change.personId)
            if (change.isApplied && person != null &&
                person.grossYearlyIncomeMinor == change.newGrossMinor
            ) {
                personDao.update(
                    person.copy(
                        grossYearlyIncomeMinor = change.previousGrossMinor,
                        netYearlyIncomeMinor = change.previousNetMinor,
                        updatedAt = Instant.now().toEpochMilli(),
                    ),
                )
            }
            incomeChangeDao.delete(change)
        }

    private companion object {
        const val SALARY_COLOUR = "#2E7D32"
        const val OVERTIME_COLOUR = "#558B2F"
    }
}
