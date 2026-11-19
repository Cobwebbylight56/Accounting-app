package com.rhys.financetracker.domain.budget

import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a budget line asks of a month. */
enum class LineGoal {
    /** Spending: keep to it — Fuel, Groceries, the phone bill. */
    SPEND_AT_MOST,

    /** Money put aside or paid off: reach it — the saver, the car loan. */
    PUT_ASIDE_AT_LEAST,
}

/** One line of the budget, as the maths needs it. */
data class BudgetPlan(
    val lineId: Long,
    val name: String,
    /** The usual monthly amount, in pence. */
    val setMinor: Long,
    /**
     * An aim rather than a limit — fuel, the food shop: something to aim
     * for that changes month to month, where going over isn't a big problem.
     */
    val aim: Boolean = false,
    /** For an aim: it follows the last few months' spending. */
    val followAverage: Boolean = false,
    val goal: LineGoal = LineGoal.SPEND_AT_MOST,
)

/** Where a month's budget for a line came from. */
enum class BudgetSource {
    /** The usual amount. */
    SET,

    /** The average of the months before, for a line that follows it. */
    AVERAGE,

    /** Changed for that month only. */
    THIS_MONTH_ONLY,
}

enum class LineStatus {
    /** Spending within the budget. */
    UNDER,

    /** An aim, a little over: within [BudgetMaths.AIM_TOLERANCE]. Counts as hit. */
    ABOUT_RIGHT,

    /** A limit gone over. */
    OVER,

    /** An aim gone over by more than a little: not a big problem, so half marks. */
    OVER_AIM,

    /** Money put aside or paid off, and the amount was reached. */
    MET,

    /** Not reached, with the month over. */
    SHORT,

    /** Not reached yet, with the month still going. */
    NOT_YET,

    /** Nothing to keep to: a budget of nothing. */
    NO_BUDGET,
}

/** How one line did in one month. */
data class LineResult(
    val plan: BudgetPlan,
    val month: YearMonth,
    val budgetMinor: Long,
    val source: BudgetSource,
    val actualMinor: Long,
    val status: LineStatus,
) {
    /** Left to spend (or still to put aside); negative when over. */
    val leftMinor: Long get() = budgetMinor - actualMinor

    /** Counts towards the score. */
    val counts: Boolean get() = status != LineStatus.NO_BUDGET

    /**
     * What it earns towards the score: full marks kept to (or not failed
     * yet), half for an aim gone over — that's not a big problem — and
     * nothing for a limit broken or money not put aside.
     */
    val credit: Double get() = when (status) {
        LineStatus.UNDER, LineStatus.ABOUT_RIGHT, LineStatus.MET, LineStatus.NOT_YET -> 1.0
        LineStatus.OVER_AIM -> 0.5
        LineStatus.OVER, LineStatus.SHORT, LineStatus.NO_BUDGET -> 0.0
    }

    /** Kept to in full, or not failed yet. */
    val kept: Boolean get() = counts && credit == 1.0
}

enum class Grade(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    GETTING_THERE("Getting there"),
    NEEDS_WORK("Needs work"),
}

/** How well a month kept to the budget. */
data class MonthScore(
    val month: YearMonth,
    val inProgress: Boolean,
    /** Lines kept to, of the lines with a budget. */
    val kept: Int,
    val counted: Int,
    /** The spending lines' budgets added up, and what was spent on them. */
    val budgetMinor: Long,
    val spentMinor: Long,
    /** Kept to, as a percentage of the lines; null with nothing to score. */
    val percent: Int?,
    val grade: Grade?,
) {
    /** Under the spending budget overall: positive is money left, negative over. */
    val leftMinor: Long get() = budgetMinor - spentMinor
}

/** A budget that doesn't match what happens, and what it could be. */
data class Suggestion(
    val lineId: Long,
    val name: String,
    val fromMinor: Long,
    val toMinor: Long,
    /** "Over in 3 of the last 3 months, £182 a month on average." */
    val why: String,
) {
    val raise: Boolean get() = toMinor > fromMinor
}

/**
 * The budget's sums: what each line's budget is in a given month, whether it
 * was kept to, how the month scores, and which budgets don't fit.
 *
 * Bills are limits: the same each month, and over is over. Fuel and the food
 * shop are aims: they go up with a long trip or a birthday, and going over
 * isn't a big problem. An aim counts as hit when it's within a fifth over,
 * earns half marks beyond that rather than none, and can follow the average
 * of the last few months instead of a set amount. Any line can be changed
 * for one month without changing the rest.
 */
object BudgetMaths {

    /** How far over an aim can go and still count as hit. */
    const val AIM_TOLERANCE = 0.20

    /** Months averaged for a line that follows its spending, and for suggestions. */
    const val AVERAGE_MONTHS = 3

    /**
     * [plan]'s budget for a month: [change] when it was changed for that
     * month, else the average of [before] (the months before it, with
     * spending in) for a line that follows it, else the usual amount.
     */
    fun budgetFor(plan: BudgetPlan, change: Long?, before: List<Long>): Pair<Long, BudgetSource> {
        if (change != null) return change to BudgetSource.THIS_MONTH_ONLY
        val months = before.takeLast(AVERAGE_MONTHS).filter { it > 0 }
        if (plan.aim && plan.followAverage && months.isNotEmpty()) {
            return roundUpToPound(months.sum() / months.size) to BudgetSource.AVERAGE
        }
        return plan.setMinor to BudgetSource.SET
    }

    /** Whether [actual] keeps to [budget] for [plan], with the month over or [inProgress]. */
    fun status(plan: BudgetPlan, budget: Long, actual: Long, inProgress: Boolean): LineStatus = when {
        budget <= 0 -> LineStatus.NO_BUDGET
        plan.goal == LineGoal.PUT_ASIDE_AT_LEAST -> when {
            actual >= budget -> LineStatus.MET
            inProgress -> LineStatus.NOT_YET
            else -> LineStatus.SHORT
        }
        actual <= budget -> LineStatus.UNDER
        !plan.aim -> LineStatus.OVER
        actual <= budget + Math.round(budget * AIM_TOLERANCE) -> LineStatus.ABOUT_RIGHT
        else -> LineStatus.OVER_AIM
    }

    /** One line's month, put together. */
    fun result(
        plan: BudgetPlan,
        month: YearMonth,
        actual: Long,
        change: Long?,
        before: List<Long>,
        inProgress: Boolean,
    ): LineResult {
        val (budget, source) = budgetFor(plan, change, before)
        return LineResult(plan, month, budget, source, actual, status(plan, budget, actual, inProgress))
    }

    /** How [month] did across [results]. */
    fun score(month: YearMonth, results: List<LineResult>, inProgress: Boolean): MonthScore {
        val counted = results.filter { it.counts }
        val kept = counted.count { it.kept }
        val spending = counted.filter { it.plan.goal == LineGoal.SPEND_AT_MOST }
        val percent = if (counted.isEmpty()) null else (counted.sumOf { it.credit } * 100.0 / counted.size).roundToInt()
        return MonthScore(
            month = month,
            inProgress = inProgress,
            kept = kept,
            counted = counted.size,
            budgetMinor = spending.sumOf { it.budgetMinor },
            spentMinor = spending.sumOf { it.actualMinor },
            percent = percent,
            grade = percent?.let(::gradeOf),
        )
    }

    fun gradeOf(percent: Int): Grade = when {
        percent >= 90 -> Grade.EXCELLENT
        percent >= 75 -> Grade.GOOD
        percent >= 50 -> Grade.GETTING_THERE
        else -> Grade.NEEDS_WORK
    }

    /**
     * Months in a row, newest first, that came in within the spending budget
     * overall. [scores] newest first; a month still going is skipped.
     */
    fun streak(scores: List<MonthScore>): Int =
        scores.filterNot { it.inProgress }
            .takeWhile { it.counted > 0 && it.spentMinor <= it.budgetMinor }
            .size

    /**
     * A better budget for [plan], from [history] — its last few finished
     * months, oldest first, as (budget, spent) — or null when it fits.
     *
     * Over in most of them — a limit at all, an aim by more than a fifth:
     * raise it to what's usually spent, as a bill that's gone up or an aim
     * set too low. Well under in every one: lower it, and the difference can
     * go elsewhere. An aim that follows its average already moves.
     */
    fun suggestion(plan: BudgetPlan, history: List<Pair<Long, Long>>): Suggestion? {
        if (plan.goal != LineGoal.SPEND_AT_MOST || plan.followAverage || plan.setMinor <= 0) return null
        val months = history.takeLast(AVERAGE_MONTHS).filter { (_, spent) -> spent > 0 }
        if (months.size < 2) return null
        val average = months.sumOf { it.second } / months.size
        val slack = if (plan.aim) AIM_TOLERANCE else 0.0
        val over = months.count { (budget, spent) -> spent > budget + Math.round(budget * slack) }
        val set = plan.setMinor
        return when {
            over >= 2 && average > set + Math.round(set * slack) -> {
                val to = roundUpToFive(average)
                Suggestion(plan.lineId, plan.name, set, to, "Over in $over of the last ${months.size} months — ${pounds(average)} a month on average.")
            }
            months.all { (budget, spent) -> spent < budget * WELL_UNDER } && average < set * WELL_UNDER -> {
                val to = roundUpToFive(average).coerceAtLeast(FIVE_POUNDS)
                if (to >= set) null else Suggestion(
                    plan.lineId, plan.name, set, to,
                    "Under in all of the last ${months.size} months — ${pounds(average)} a month on average.",
                )
            }
            else -> null
        }
    }

    /** Up to the next whole pound. */
    fun roundUpToPound(minor: Long): Long = ((minor + 99) / 100) * 100

    /** Up to the next £5: a budget people can remember. */
    fun roundUpToFive(minor: Long): Long = ((minor + FIVE_POUNDS - 1) / FIVE_POUNDS) * FIVE_POUNDS

    private fun pounds(minor: Long): String = "£" + String.format(java.util.Locale.UK, "%,d", abs(minor) / 100)

    /** Spending this far under in every month is a budget set too high. */
    private const val WELL_UNDER = 0.75

    private const val FIVE_POUNDS = 500L
}
