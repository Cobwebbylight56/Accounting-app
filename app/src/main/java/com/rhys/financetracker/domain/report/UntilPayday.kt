package com.rhys.financetracker.domain.report

import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import java.time.LocalDate

/**
 * What is there to spend until the next pay day: the money in the accounts
 * spent from now, less the bills and payments still to go out of them
 * before the wage arrives.
 *
 * Counted from what is really there rather than from what came in this
 * month, so it doesn't sink below nothing in the days before pay day just
 * because the wage hasn't landed yet. Without a wage set up it runs to the
 * end of the month.
 */
data class UntilPayday(
    /** What is in the accounts spent from, now. */
    val availableMinor: Long,
    /** Bills, savings and loan payments still to go out of them before [until]. */
    val billsMinor: Long,
    /** The next wage's day, or null when there is no wage to wait for. */
    val payday: LocalDate?,
    /** The last day counted: the day before pay day, or the month's end. */
    val until: LocalDate,
    /** Wages still to come in this month, and the first one's day. */
    val wageDueMinor: Long,
    val wageDueDate: LocalDate?,
) {
    val leftMinor: Long get() = availableMinor - billsMinor

    companion object {
        /**
         * @param rules every regular payment that is running.
         * @param spendAccountIds the accounts spent from: money moved out of
         *   them to savings or a loan goes; moved between them, it stays.
         */
        fun of(
            rules: List<RecurringRuleEntity>,
            spendAccountIds: Set<Long>,
            availableMinor: Long,
            today: LocalDate,
        ): UntilPayday {
            val running = rules.filter { !it.isPaused && !it.isArchived }
            val wages = running.filter { it.type == TransactionType.INCOME && isWage(it) && it.accountId in spendAccountIds }
            // The next pay day after today: on pay day itself the wage is in,
            // and what's left has to last to the one after.
            val payday = wages.mapNotNull { rule ->
                RecurrenceCalculator.upcomingOccurrences(rule, today.plusDays(1), today.plusDays(LOOK_AHEAD), limit = 1).firstOrNull()
            }.minOrNull()
            val until = payday?.minusDays(1) ?: today.withDayOfMonth(today.lengthOfMonth())
            val goingOut = running.filter { rule ->
                rule.accountId in spendAccountIds && when (rule.type) {
                    TransactionType.EXPENSE -> true
                    TransactionType.TRANSFER -> rule.transferAccountId !in spendAccountIds
                    TransactionType.INCOME -> false
                }
            }
            val bills = goingOut.sumOf { rule -> RecurrenceCalculator.upcomingOccurrences(rule, today, until).size * rule.amountMinor }
            val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
            val wageDays = wages.flatMap { rule -> RecurrenceCalculator.upcomingOccurrences(rule, today, monthEnd).map { it to rule.amountMinor } }
            return UntilPayday(
                availableMinor = availableMinor,
                billsMinor = bills,
                payday = payday,
                until = until,
                wageDueMinor = wageDays.sumOf { it.second },
                wageDueDate = wageDays.minOfOrNull { it.first },
            )
        }

        private fun isWage(rule: RecurringRuleEntity): Boolean =
            rule.notes == SpreadsheetImporter.WAGE_MARKER || rule.paymentKind == PaymentKind.MONEY_IN

        /** A wage paid every month is always due within this many days. */
        private const val LOOK_AHEAD = 40L
    }
}
