package com.rhys.financetracker.domain.budget

/**
 * Each person's part of a budget line — the columns of the household sheet:
 *
 * ```
 *              Rhys    Hannah    both
 *  fuel         120        80     200
 * ```
 *
 * A line's total is everyone's shares plus anything not anyone's in
 * particular. A line with no shares is split evenly between the people,
 * and a month whose budget differs from usual (changed for the month, or
 * an aim following its average) scales every share alike.
 */
object BudgetShares {

    /**
     * Each of [people]'s budget for a month whose budget is [monthTotal],
     * for a line usually [usualTotal] split as [shares]. Whatever isn't
     * anyone's is left out; see [unassigned].
     */
    fun forMonth(shares: Map<Long, Long>, usualTotal: Long, monthTotal: Long, people: List<Long>): Map<Long, Long> {
        if (people.isEmpty()) return emptyMap()
        val base = if (shares.isEmpty()) evenly(usualTotal, people) else people.associateWith { shares[it] ?: 0L }
        if (usualTotal <= 0L || monthTotal == usualTotal) return base
        return base.mapValues { (_, share) -> Math.round(share.toDouble() * monthTotal / usualTotal) }
    }

    /** What's in the line's total but no one person's: the joint part. */
    fun unassigned(shares: Map<Long, Long>, usualTotal: Long, people: List<Long>): Long =
        if (shares.isEmpty() || people.isEmpty()) 0L else (usualTotal - people.sumOf { shares[it] ?: 0L }).coerceAtLeast(0L)

    /** [total] split evenly between [people], the odd pennies to the first. */
    fun evenly(total: Long, people: List<Long>): Map<Long, Long> {
        if (people.isEmpty()) return emptyMap()
        val each = total / people.size
        val left = total - each * people.size
        return people.mapIndexed { i, id -> id to each + if (i.toLong() < left) 1L else 0L }.toMap()
    }

    /**
     * [personId]'s share changed to [amount]: the new shares and the line's
     * new total. A line that wasn't split yet is split evenly first, so the
     * others keep what they had; the joint part stays as it was.
     */
    fun withPerson(shares: Map<Long, Long>, total: Long, people: List<Long>, personId: Long, amount: Long): Pair<Map<Long, Long>, Long> {
        val start = if (shares.isEmpty()) evenly(total, people) else shares
        val joint = unassigned(shares, total, people)
        val updated = start + (personId to amount.coerceAtLeast(0L))
        return updated to updated.values.sum() + joint
    }

    /** [shares] scaled so the line comes to [newTotal], the joint part scaled alike. */
    fun scaled(shares: Map<Long, Long>, oldTotal: Long, newTotal: Long): Map<Long, Long> {
        if (shares.isEmpty() || oldTotal <= 0L) return shares
        return shares.mapValues { (_, share) -> Math.round(share.toDouble() * newTotal / oldTotal) }
    }
}
