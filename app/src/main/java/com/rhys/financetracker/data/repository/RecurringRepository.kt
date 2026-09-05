package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RecurringRepository @Inject constructor(
    private val ruleDao: RecurringRuleDao,
) {

    fun observeAll(): Flow<List<RecurringRuleWithDetails>> = ruleDao.observeActiveWithDetails()

    fun observeByType(type: TransactionType): Flow<List<RecurringRuleWithDetails>> =
        ruleDao.observeByType(type)

    fun observeUpcoming(days: Long = 30, today: LocalDate = DateUtils.today()):
        Flow<List<RecurringRuleWithDetails>> =
        ruleDao.observeUpcoming(today, today.plusDays(days))

    fun observeOverdue(today: LocalDate = DateUtils.today()): Flow<List<RecurringRuleWithDetails>> =
        ruleDao.observeOverdue(today)

    fun observe(id: Long): Flow<RecurringRuleWithDetails?> = ruleDao.observeWithDetails(id)

    suspend fun get(id: Long): RecurringRuleEntity? = ruleDao.getById(id)

    suspend fun getAll(): List<RecurringRuleEntity> = ruleDao.getAll()

    /**
     * Saves a rule, recalculating its cursor so that changing the start date or
     * frequency takes effect immediately rather than at the next occurrence of
     * the old schedule.
     */
    suspend fun save(rule: RecurringRuleEntity): AppResult<Long> =
        runCatchingApp("Could not save this recurring payment") {
            validate(rule)
            val prepared = rule.copy(
                nextDueDate = resolveNextDueDate(rule),
                updatedAt = Instant.now().toEpochMilli(),
            )
            if (prepared.id == 0L) {
                ruleDao.insert(prepared)
            } else {
                ruleDao.update(prepared)
                prepared.id
            }
        }

    suspend fun duplicate(id: Long): AppResult<Long> =
        runCatchingApp("Could not duplicate this recurring payment") {
            val original = ruleDao.getById(id) ?: error("That recurring payment no longer exists")
            ruleDao.insert(
                original.copy(
                    id = 0L,
                    name = "${original.name} (copy)",
                    occurrencesGenerated = 0,
                    lastGeneratedDate = null,
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    suspend fun setPaused(id: Long, paused: Boolean): AppResult<Unit> =
        runCatchingApp("Could not pause this recurring payment") {
            ruleDao.setPaused(id, paused, Instant.now().toEpochMilli())
        }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this recurring payment") {
            ruleDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    suspend fun delete(rule: RecurringRuleEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this recurring payment") {
            ruleDao.delete(rule)
        }

    /**
     * The total of every active rule of [type], expressed as a monthly figure.
     * This is what the dashboard means by "committed": money already spoken
     * for, whatever frequency it is actually paid at.
     */
    suspend fun monthlyCommitmentMinor(type: TransactionType): Long =
        ruleDao.getActiveByType(type).sumOf {
            RecurrenceCalculator.monthlyEquivalentMinor(it.amountMinor, it.frequency, it.interval)
        }

    /** Bills still to fall due between [from] and the end of the month. */
    suspend fun remainingThisMonthMinor(from: LocalDate = DateUtils.today()): Long {
        val end = DateUtils.endOfMonth(from)
        return ruleDao.getAllActive()
            .filter { it.type == TransactionType.EXPENSE }
            .sumOf { rule ->
                RecurrenceCalculator.upcomingOccurrences(rule, from, end).size * rule.amountMinor
            }
    }

    private fun resolveNextDueDate(rule: RecurringRuleEntity): LocalDate {
        // A brand-new rule starts at its start date; an edited one keeps its
        // place in the schedule unless the schedule itself has moved past it.
        if (rule.id == 0L) return rule.startDate
        val today = DateUtils.today()
        if (!rule.nextDueDate.isBefore(rule.startDate)) return rule.nextDueDate
        return RecurrenceCalculator.nextOccurrenceAfter(rule, today.minusDays(1)) ?: rule.startDate
    }

    private fun validate(rule: RecurringRuleEntity) {
        Validators.validateName(rule.name, "Name").errorOrNull?.let { error(it) }
        Validators.validateNotes(rule.notes).errorOrNull?.let { error(it) }
        Validators.validateDate(rule.startDate).errorOrNull?.let { error(it) }
        Validators.validateDateOrder(rule.startDate, rule.endDate).errorOrNull?.let { error(it) }
        if (rule.frequency.isCustom) {
            Validators.validateInterval(rule.interval).errorOrNull?.let { error(it) }
        }
        if (rule.amountMinor <= 0L) error("Enter an amount greater than zero")
        if (rule.type == TransactionType.TRANSFER) {
            val destination = rule.transferAccountId
                ?: error("Choose the account the money is going to")
            if (destination == rule.accountId) {
                error("A transfer must be between two different accounts")
            }
        }
        if (rule.frequency == Frequency.ONE_OFF && rule.endDate != null &&
            rule.endDate.isBefore(rule.startDate)
        ) {
            error("The end date must be after the start date")
        }
    }
}
