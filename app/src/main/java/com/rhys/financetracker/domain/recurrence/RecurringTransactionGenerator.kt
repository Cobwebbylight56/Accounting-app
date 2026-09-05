package com.rhys.financetracker.domain.recurrence

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecurrenceMode
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns due recurring rules into real transactions.
 *
 * This is what removes the manual work: the user sets a bill up once, and every
 * month it appears by itself.  The generator is safe to run as often as you
 * like — it is idempotent, because a rule's cursor only ever moves forwards and
 * because each candidate entry is checked against
 * [TransactionDao.existsForRuleOnDate] before being written.
 *
 * It is called on app start, by a daily background worker, and by the monthly
 * rollover.
 */
@Singleton
class RecurringTransactionGenerator @Inject constructor(
    private val ruleDao: RecurringRuleDao,
    private val transactionDao: TransactionDao,
) {

    /**
     * @param through generate every occurrence dated on or before this day.
     *   Defaults to today, so future bills are forecast but not posted.
     */
    suspend fun generateDue(through: LocalDate = DateUtils.today()): AppResult<GenerationSummary> =
        runCatchingApp("Could not create the recurring transactions") {
            val dueRules = ruleDao.getDueThrough(through)
            var created = 0
            var skipped = 0
            var rulesTouched = 0

            for (rule in dueRules) {
                val occurrences = RecurrenceCalculator.occurrencesDue(rule, through)
                if (occurrences.isEmpty()) continue

                var generatedCount = rule.occurrencesGenerated
                var lastGenerated = rule.lastGeneratedDate

                for (date in occurrences) {
                    // REMIND_ONLY rules never post; they exist purely to raise a
                    // notification, so the cursor advances but nothing is written.
                    if (rule.mode == RecurrenceMode.REMIND_ONLY) {
                        skipped++
                    } else if (transactionDao.existsForRuleOnDate(rule.id, date)) {
                        skipped++
                    } else {
                        transactionDao.insert(rule.toTransaction(date))
                        created++
                    }
                    generatedCount++
                    lastGenerated = date
                }

                ruleDao.updateSchedule(
                    id = rule.id,
                    nextDueDate = nextCursorAfter(rule, occurrences.last()),
                    lastGenerated = lastGenerated,
                    occurrences = generatedCount,
                    updatedAt = Instant.now().toEpochMilli(),
                )
                rulesTouched++
            }

            GenerationSummary(
                transactionsCreated = created,
                occurrencesSkipped = skipped,
                rulesProcessed = rulesTouched,
                generatedThrough = through,
            )
        }

    /**
     * Where the cursor should sit once [lastGenerated] has been posted.
     *
     * A finished rule (one-off, past its end date, or at its occurrence limit)
     * parks its cursor on the day after the last occurrence and is then paused,
     * so it stops being picked up without its history being touched.
     */
    private suspend fun nextCursorAfter(
        rule: RecurringRuleEntity,
        lastGenerated: LocalDate,
    ): LocalDate {
        val next = RecurrenceCalculator.nextOccurrenceAfter(rule, lastGenerated)
        if (next != null && rule.frequency != Frequency.ONE_OFF) return next

        ruleDao.setPaused(rule.id, paused = true, updatedAt = Instant.now().toEpochMilli())
        return lastGenerated.plusDays(1)
    }

    /**
     * Recomputes a rule's cursor from scratch — used after the user edits the
     * start date or frequency, so the schedule matches the new settings.
     */
    fun recalculateCursor(rule: RecurringRuleEntity, from: LocalDate = DateUtils.today()): LocalDate {
        if (!rule.startDate.isBefore(from)) return rule.startDate
        return RecurrenceCalculator.nextOccurrenceAfter(rule, from.minusDays(1)) ?: rule.startDate
    }
}

/** What one generation run did, shown in the "what's new" banner and the logs. */
data class GenerationSummary(
    val transactionsCreated: Int,
    val occurrencesSkipped: Int,
    val rulesProcessed: Int,
    val generatedThrough: LocalDate,
) {
    val didAnything: Boolean get() = transactionsCreated > 0
}

/**
 * Builds the transaction for one occurrence of a rule.
 *
 * A variable-amount rule (energy, fuel) posts its last known amount but is left
 * unconfirmed, so the user is prompted to correct it rather than the app
 * quietly recording a figure that is probably wrong.
 */
internal fun RecurringRuleEntity.toTransaction(date: LocalDate): TransactionEntity {
    val now = Instant.now().toEpochMilli()
    return TransactionEntity(
        amountMinor = amountMinor,
        type = type,
        date = date,
        description = name,
        accountId = accountId,
        transferAccountId = transferAccountId,
        categoryId = categoryId,
        personId = personId,
        recurringRuleId = id,
        savingsGoalId = savingsGoalId,
        notes = notes,
        isConfirmed = mode == RecurrenceMode.AUTO_POST && !isVariableAmount,
        isCleared = mode == RecurrenceMode.AUTO_POST && !isVariableAmount,
        createdAt = now,
        updatedAt = now,
    )
}
