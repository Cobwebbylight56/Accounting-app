package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.SavingsGoalDao
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow

@Singleton
class SavingsRepository @Inject constructor(
    private val goalDao: SavingsGoalDao,
) {

    fun observeWithProgress(): Flow<List<SavingsGoalWithProgress>> =
        goalDao.observeActiveWithProgress()

    fun observe(id: Long): Flow<SavingsGoalWithProgress?> = goalDao.observeWithProgress(id)

    fun observeAll(): Flow<List<SavingsGoalEntity>> = goalDao.observeAll()

    suspend fun get(id: Long): SavingsGoalEntity? = goalDao.getById(id)

    suspend fun save(goal: SavingsGoalEntity): AppResult<Long> =
        runCatchingApp("Could not save this savings goal") {
            Validators.validateName(goal.name, "Goal name").errorOrNull?.let { error(it) }
            Validators.validateNotes(goal.notes).errorOrNull?.let { error(it) }
            if (goal.targetAmountMinor <= 0L) error("Enter a target greater than zero")
            if (goal.targetDate != null && goal.targetDate.isBefore(goal.startDate)) {
                error("The target date must be after the start date")
            }
            if (goal.id == 0L) {
                goalDao.insert(goal)
            } else {
                goalDao.update(goal.copy(updatedAt = Instant.now().toEpochMilli()))
                goal.id
            }
        }

    suspend fun duplicate(id: Long): AppResult<Long> =
        runCatchingApp("Could not duplicate this savings goal") {
            val original = goalDao.getById(id) ?: error("That goal no longer exists")
            goalDao.insert(
                original.copy(
                    id = 0L,
                    name = "${original.name} (copy)",
                    manualAdjustmentMinor = 0L,
                    isAchieved = false,
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this savings goal") {
            goalDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    suspend fun delete(goal: SavingsGoalEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this savings goal") {
            goalDao.delete(goal)
        }

    /**
     * Adds to (or subtracts from) a goal tracked by hand, rather than through a
     * linked account.
     */
    suspend fun adjustBalance(id: Long, deltaMinor: Long): AppResult<Unit> =
        runCatchingApp("Could not update this goal") {
            val goal = goalDao.getById(id) ?: error("That goal no longer exists")
            goalDao.update(
                goal.copy(
                    manualAdjustmentMinor = goal.manualAdjustmentMinor + deltaMinor,
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }
}

/**
 * How much needs to be put by each month to reach [goal] on time, and when it
 * will actually be reached at the current rate.  Kept out of the entity so the
 * arithmetic can be unit-tested on its own.
 */
object SavingsProjection {

    /** Monthly amount required to hit the target date; null when there is no target date. */
    fun requiredMonthlyMinor(
        goal: SavingsGoalWithProgress,
        today: LocalDate = DateUtils.today(),
    ): Long? {
        val targetDate = goal.goal.targetDate ?: return null
        if (!targetDate.isAfter(today)) return goal.remainingMinor
        val months = ChronoUnit.MONTHS.between(
            java.time.YearMonth.from(today),
            java.time.YearMonth.from(targetDate),
        ).coerceAtLeast(1L)
        return ceil(goal.remainingMinor.toDouble() / months).toLong()
    }

    /**
     * The month the goal will be reached at the planned contribution, or null
     * when nothing is being contributed.
     */
    fun projectedCompletion(
        goal: SavingsGoalWithProgress,
        today: LocalDate = DateUtils.today(),
    ): LocalDate? {
        val monthly = goal.goal.monthlyContributionMinor
        if (monthly <= 0L) return null
        if (goal.remainingMinor <= 0L) return today
        val months = ceil(goal.remainingMinor.toDouble() / monthly).toLong()
        return today.plusMonths(months)
    }

    /** True when the plan will not reach the target date. */
    fun isBehindSchedule(
        goal: SavingsGoalWithProgress,
        today: LocalDate = DateUtils.today(),
    ): Boolean {
        val targetDate = goal.goal.targetDate ?: return false
        val projected = projectedCompletion(goal, today) ?: return goal.remainingMinor > 0L
        return projected.isAfter(targetDate)
    }
}
