package com.rhys.financetracker.domain.recurrence

import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Works out when a recurring rule falls due.
 *
 * The rules it follows:
 *  * **Monthly-style frequencies keep the day of the month from the start
 *    date.**  A bill starting on 31 January is due on 28 February and then 31
 *    March — it does not drift to the 28th of every later month, which is what
 *    naive `plusMonths` chaining produces.
 *  * **Week-based frequencies keep the weekday**, because they are a whole
 *    number of weeks apart by definition.
 *  * Every calculation is derived from [RecurringRuleEntity.startDate] and an
 *    occurrence index, so the schedule is reproducible: recomputing it after a
 *    restore gives exactly the same dates.
 */
object RecurrenceCalculator {

    /** Safety limit so a misconfigured rule can never loop forever. */
    const val MAX_OCCURRENCES_PER_RUN = 500

    /**
     * The date of occurrence number [index], counting the start date as
     * index 0.
     *
     * @param interval multiplier for the custom frequencies; ignored otherwise.
     */
    fun occurrenceDate(
        start: LocalDate,
        frequency: Frequency,
        interval: Int,
        index: Int,
    ): LocalDate {
        require(index >= 0) { "index must not be negative" }
        val safeInterval = interval.coerceAtLeast(1)
        return when (frequency) {
            Frequency.ONE_OFF -> start
            Frequency.DAILY -> start.plusDays(index.toLong())
            Frequency.WEEKLY -> start.plusWeeks(index.toLong())
            Frequency.FORTNIGHTLY -> start.plusWeeks(2L * index)
            Frequency.FOUR_WEEKLY -> start.plusWeeks(4L * index)
            Frequency.MONTHLY -> addMonthsKeepingDay(start, index.toLong())
            Frequency.QUARTERLY -> addMonthsKeepingDay(start, 3L * index)
            Frequency.HALF_YEARLY -> addMonthsKeepingDay(start, 6L * index)
            Frequency.YEARLY -> addMonthsKeepingDay(start, 12L * index)
            Frequency.CUSTOM_DAYS -> start.plusDays(safeInterval.toLong() * index)
            Frequency.CUSTOM_MONTHS -> addMonthsKeepingDay(start, safeInterval.toLong() * index)
        }
    }

    /**
     * Adds [months] to [start] while preserving the original day of the month
     * where the target month is long enough, and clamping to the last day where
     * it is not.
     */
    fun addMonthsKeepingDay(start: LocalDate, months: Long): LocalDate {
        if (months == 0L) return start
        val shifted = start.withDayOfMonth(1).plusMonths(months)
        val day = start.dayOfMonth.coerceAtMost(shifted.lengthOfMonth())
        return shifted.withDayOfMonth(day)
    }

    /**
     * The next occurrence strictly after [after], or `null` when the rule has
     * finished (past its end date or its occurrence limit).
     */
    fun nextOccurrenceAfter(rule: RecurringRuleEntity, after: LocalDate): LocalDate? {
        if (rule.frequency == Frequency.ONE_OFF) {
            return rule.startDate.takeIf { it.isAfter(after) }
        }
        var index = estimateIndexFor(rule, after)
        var candidate = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)

        // Walk backwards in case the estimate overshot, then forwards until the
        // candidate is past `after`.  Both loops are bounded.
        var guard = 0
        while (index > 0 && candidate.isAfter(after) && guard++ < MAX_OCCURRENCES_PER_RUN) {
            val previous = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index - 1)
            if (!previous.isAfter(after)) break
            index--
            candidate = previous
        }
        guard = 0
        while (!candidate.isAfter(after) && guard++ < MAX_OCCURRENCES_PER_RUN) {
            index++
            candidate = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)
        }
        if (!candidate.isAfter(after)) return null

        return candidate.takeIf { isWithinLimits(rule, it, index) }
    }

    /**
     * Every occurrence from [rule]'s current cursor up to and including
     * [through], in date order.  This is what the generator iterates over when
     * catching up after the app has not been opened for a while.
     */
    fun occurrencesDue(
        rule: RecurringRuleEntity,
        through: LocalDate,
        limit: Int = MAX_OCCURRENCES_PER_RUN,
    ): List<LocalDate> {
        if (rule.isPaused || rule.isArchived) return emptyList()

        val result = mutableListOf<LocalDate>()
        var index = indexOf(rule, rule.nextDueDate)
        var date = rule.nextDueDate
        var generated = rule.occurrencesGenerated

        while (result.size < limit && !date.isAfter(through)) {
            if (rule.endDate != null && date.isAfter(rule.endDate)) break
            if (rule.maxOccurrences != null && generated >= rule.maxOccurrences) break

            result += date
            generated++

            if (rule.frequency == Frequency.ONE_OFF) break
            index++
            date = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)
        }
        return result
    }

    /**
     * Projects forward from today — used by the cash-flow forecast and the
     * "upcoming bills" card.  Does not touch the database.
     */
    fun upcomingOccurrences(
        rule: RecurringRuleEntity,
        from: LocalDate,
        to: LocalDate,
        limit: Int = 50,
    ): List<LocalDate> {
        if (rule.isPaused || rule.isArchived) return emptyList()
        val result = mutableListOf<LocalDate>()
        var index = indexOf(rule, maxOf(rule.nextDueDate, from))
        var date = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)

        // Skip anything before the window.
        var guard = 0
        while (date.isBefore(from) && guard++ < MAX_OCCURRENCES_PER_RUN) {
            if (rule.frequency == Frequency.ONE_OFF) return emptyList()
            index++
            date = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)
        }

        while (result.size < limit && !date.isAfter(to)) {
            if (rule.endDate != null && date.isAfter(rule.endDate)) break
            result += date
            if (rule.frequency == Frequency.ONE_OFF) break
            index++
            date = occurrenceDate(rule.startDate, rule.frequency, rule.interval, index)
        }
        return result
    }

    /**
     * Converts an amount at any frequency to the equivalent monthly figure, so
     * that a weekly £20 and a yearly £600 can be compared and summed.
     */
    fun monthlyEquivalentMinor(amountMinor: Long, frequency: Frequency, interval: Int): Long {
        val safeInterval = interval.coerceAtLeast(1)
        val perYear = when (frequency) {
            Frequency.ONE_OFF -> return 0L
            Frequency.CUSTOM_DAYS -> 365.0 / safeInterval
            Frequency.CUSTOM_MONTHS -> 12.0 / safeInterval
            else -> frequency.approximateOccurrencesPerYear
        }
        return Math.round(amountMinor * perYear / 12.0)
    }

    /** Converts a monthly figure to the equivalent yearly one. */
    fun yearlyEquivalentMinor(amountMinor: Long, frequency: Frequency, interval: Int): Long =
        monthlyEquivalentMinor(amountMinor, frequency, interval) * 12L

    // ------------------------------------------------------------- internals

    /**
     * The occurrence index that produces [date], or the nearest index at or
     * before it.  Estimated arithmetically and then corrected, which is far
     * cheaper than stepping one occurrence at a time from the start date for a
     * rule that began years ago.
     */
    internal fun indexOf(rule: RecurringRuleEntity, date: LocalDate): Int {
        if (!date.isAfter(rule.startDate)) return 0
        val safeInterval = rule.interval.coerceAtLeast(1)
        val estimate = when (rule.frequency) {
            Frequency.ONE_OFF -> 0L
            Frequency.DAILY -> ChronoUnit.DAYS.between(rule.startDate, date)
            Frequency.WEEKLY -> ChronoUnit.WEEKS.between(rule.startDate, date)
            Frequency.FORTNIGHTLY -> ChronoUnit.WEEKS.between(rule.startDate, date) / 2
            Frequency.FOUR_WEEKLY -> ChronoUnit.WEEKS.between(rule.startDate, date) / 4
            Frequency.MONTHLY -> ChronoUnit.MONTHS.between(rule.startDate, date)
            Frequency.QUARTERLY -> ChronoUnit.MONTHS.between(rule.startDate, date) / 3
            Frequency.HALF_YEARLY -> ChronoUnit.MONTHS.between(rule.startDate, date) / 6
            Frequency.YEARLY -> ChronoUnit.YEARS.between(rule.startDate, date)
            Frequency.CUSTOM_DAYS ->
                ChronoUnit.DAYS.between(rule.startDate, date) / safeInterval
            Frequency.CUSTOM_MONTHS ->
                ChronoUnit.MONTHS.between(rule.startDate, date) / safeInterval
        }
        var index = estimate.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        // Correct for month-length clamping, which can make the estimate out by one.
        var guard = 0
        while (index > 0 &&
            occurrenceDate(rule.startDate, rule.frequency, rule.interval, index).isAfter(date) &&
            guard++ < MAX_OCCURRENCES_PER_RUN
        ) {
            index--
        }
        guard = 0
        while (occurrenceDate(rule.startDate, rule.frequency, rule.interval, index + 1)
                .let { !it.isAfter(date) } && guard++ < MAX_OCCURRENCES_PER_RUN
        ) {
            index++
        }
        return index
    }

    private fun estimateIndexFor(rule: RecurringRuleEntity, after: LocalDate): Int =
        indexOf(rule, after)

    private fun isWithinLimits(rule: RecurringRuleEntity, date: LocalDate, index: Int): Boolean {
        if (rule.endDate != null && date.isAfter(rule.endDate)) return false
        if (rule.maxOccurrences != null && index >= rule.maxOccurrences) return false
        return true
    }
}
