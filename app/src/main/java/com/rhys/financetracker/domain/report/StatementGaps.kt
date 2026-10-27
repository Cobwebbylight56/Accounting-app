package com.rhys.financetracker.domain.report

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import java.time.YearMonth

/**
 * Which months' statements are missing for an account: each month from its
 * first statement to last month with none in, apart from those said to have
 * had nothing in them — a statement with no payments adds no rows, so its
 * month looked missing for ever.
 */
object StatementGaps {

    /**
     * Whether the bank sends [type]/[holding] a statement every month: current
     * accounts and cards. Savings, ISAs and loans get one a year, or on
     * request, so a month without one is no gap.
     */
    fun monthly(type: AccountType, holding: Holding): Boolean =
        holding == Holding.SPEND || type == AccountType.CREDIT_CARD

    /**
     * The months with no statement, oldest first, from the first in [have] (but
     * no more than [lookBack] months before [lastMonth]) up to [lastMonth].
     */
    fun missing(have: Set<YearMonth>, lastMonth: YearMonth, lookBack: Long, said: Set<YearMonth>): List<YearMonth> {
        val first = have.minOrNull() ?: return emptyList()
        val from = maxOf(first, lastMonth.minusMonths(lookBack))
        return generateSequence(from) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(lastMonth) }
            .filter { it !in have && it !in said }
            .toList()
    }

    /** How a month said to have had no statement is kept: "12:2026-10". */
    fun key(accountId: Long, month: YearMonth): String = "$accountId:$month"
}
