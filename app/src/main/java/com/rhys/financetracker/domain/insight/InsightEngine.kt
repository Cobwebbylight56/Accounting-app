package com.rhys.financetracker.domain.insight

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RecurrenceCalculator
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the figures into advice.
 *
 * Each rule below is one question a person would actually ask of their own
 * budget. They share three constraints:
 *
 *  * **Every message contains the number.** "£42 more on takeaways than usual",
 *    not "spending has increased".
 *  * **Each rule states how much history it needs** and stays silent below that,
 *    rather than reading meaning into a single month.
 *  * **Nothing scolds.** The app reports what the arithmetic says and what
 *    changing it would be worth. What to do about it is the household's call.
 *
 * The whole class is pure: values in, advice out, no database and no clock of
 * its own, so every rule is testable.
 */
@Singleton
class InsightEngine @Inject constructor() {

    private companion object {
        /** Below this, a change is noise rather than a habit. */
        const val MEANINGFUL_CHANGE_PERCENT = 15

        /** And it must also be worth this much, so £1.20 on a 200% rise stays quiet. */
        const val MEANINGFUL_CHANGE_MINOR = 1_500L

        /** A savings rate below this is worth mentioning; above it, worth praising. */
        const val LOW_SAVINGS_RATE = 0.05f
        const val GOOD_SAVINGS_RATE = 0.15f

        /** Emergency fund rule of thumb, in months of outgoings. */
        const val EMERGENCY_FUND_MONTHS = 3

        /** How many categories count as "where most of it goes". */
        const val TOP_CATEGORY_COUNT = 3

        /** A trimming suggestion assumes this much is reachable without pain. */
        const val TRIM_FRACTION = 0.10

        /** Subscriptions above this share of spending are worth a look. */
        const val SUBSCRIPTION_SHARE_THRESHOLD = 0.08
    }

    /**
     * @param trends this month against each category's own recent average.
     * @param forecast the projection from [Forecaster].
     * @param incomeMinor / [spendingMinor] this month so far.
     * @param savingsBalanceMinor everything currently in savings accounts.
     */
    fun analyse(
        month: YearMonth,
        trends: List<CategoryTrend>,
        forecast: Forecast,
        incomeMinor: Long,
        spendingMinor: Long,
        savedThisMonthMinor: Long,
        savingsBalanceMinor: Long,
        goals: List<SavingsGoalWithProgress>,
        rules: List<RecurringRuleEntity>,
        unconfirmedCount: Int,
        overdueCount: Int,
        today: LocalDate = DateUtils.today(),
    ): InsightReport {
        val insights = buildList {
            addAll(spendingAdvice(trends, today))
            addAll(savingsAdvice(incomeMinor, savedThisMonthMinor, savingsBalanceMinor, rules))
            addAll(billsAdvice(rules, spendingMinor, unconfirmedCount, overdueCount))
            addAll(forecastAdvice(forecast))
            addAll(goalAdvice(goals, today))
        }.sortedWith(
            compareByDescending<Insight> { it.severity.weight }
                .thenByDescending { it.annualImpactMinor ?: 0L },
        )

        val savingsRate = if (incomeMinor <= 0L) {
            0f
        } else {
            (savedThisMonthMinor.toFloat() / incomeMinor).coerceIn(0f, 1f)
        }

        return InsightReport(
            month = month,
            insights = insights,
            forecast = forecast,
            trends = trends.sortedByDescending { abs(it.differenceMinor) },
            savingsRate = savingsRate,
            hasEnoughHistory = forecast.isReliable,
        )
    }

    // ------------------------------------------------------------- spending

    /**
     * Where spending has moved, and what trimming the biggest categories would
     * actually be worth over a year — which is the figure that changes minds,
     * because £15 a week does not sound like £780.
     */
    internal fun spendingAdvice(
        trends: List<CategoryTrend>,
        today: LocalDate,
    ): List<Insight> = buildList {
        val comparable = trends.filter { it.isComparable(today) }

        // Categories that have risen meaningfully.
        comparable
            .filter { trend ->
                val percent = trend.percentChange ?: return@filter false
                percent >= MEANINGFUL_CHANGE_PERCENT &&
                    trend.differenceMinor >= MEANINGFUL_CHANGE_MINOR
            }
            .sortedByDescending { it.differenceMinor }
            .take(3)
            .forEach { trend ->
                add(
                    Insight(
                        id = "spend-up-${trend.categoryId}",
                        kind = InsightKind.SPENDING,
                        severity = InsightSeverity.WATCH,
                        title = "${trend.name} is up",
                        message = "You have spent ${Money.format(trend.thisMonthMinor)} on " +
                            "${trend.name.lowercase()} this month — " +
                            "${Money.format(trend.differenceMinor)} more than your usual " +
                            "${Money.format(trend.averageMinor)} " +
                            "(${trend.percentChange}% up on the last " +
                            "${trend.monthsCompared} months).",
                        annualImpactMinor = trend.differenceMinor * 12,
                        categoryId = trend.categoryId,
                        categoryName = trend.name,
                        colorHex = trend.colorHex,
                    ),
                )
            }

        // And where it has genuinely fallen, which is worth saying out loud.
        comparable
            .filter { trend ->
                val percent = trend.percentChange ?: return@filter false
                percent <= -MEANINGFUL_CHANGE_PERCENT &&
                    abs(trend.differenceMinor) >= MEANINGFUL_CHANGE_MINOR
            }
            .maxByOrNull { abs(it.differenceMinor) }
            ?.let { trend ->
                add(
                    Insight(
                        id = "spend-down-${trend.categoryId}",
                        kind = InsightKind.SPENDING,
                        severity = InsightSeverity.GOOD,
                        title = "${trend.name} is down",
                        message = "${Money.format(abs(trend.differenceMinor))} less on " +
                            "${trend.name.lowercase()} than usual this month. Kept up for a " +
                            "year that is ${Money.format(abs(trend.differenceMinor) * 12)}.",
                        annualImpactMinor = abs(trend.differenceMinor) * 12,
                        categoryId = trend.categoryId,
                        categoryName = trend.name,
                        colorHex = trend.colorHex,
                    ),
                )
            }

        // Over a category's own budget.
        trends.filter { it.isOverBudget }.forEach { trend ->
            val budget = trend.budgetMinor ?: return@forEach
            add(
                Insight(
                    id = "over-budget-${trend.categoryId}",
                    kind = InsightKind.SPENDING,
                    severity = InsightSeverity.ACT,
                    title = "${trend.name} is over budget",
                    message = "${Money.format(trend.thisMonthMinor)} spent against a budget " +
                        "of ${Money.format(budget)} — " +
                        "${Money.format(trend.thisMonthMinor - budget)} over.",
                    categoryId = trend.categoryId,
                    categoryName = trend.name,
                    colorHex = trend.colorHex,
                )
            )
        }

        // Where the money actually goes, and what a 10% trim would be worth.
        val ranked = trends.sortedByDescending { it.thisMonthMinor }
        val total = trends.sumOf { it.thisMonthMinor }
        val top = ranked.take(TOP_CATEGORY_COUNT).filter { it.thisMonthMinor > 0L }
        if (total > 0L && top.size == TOP_CATEGORY_COUNT) {
            val topTotal = top.sumOf { it.thisMonthMinor }
            val share = ((topTotal.toDouble() / total) * 100).roundToInt()
            add(
                Insight(
                    id = "top-categories",
                    kind = InsightKind.SPENDING,
                    severity = InsightSeverity.INFO,
                    title = "Where most of it goes",
                    message = "${top.joinToString(", ") { it.name }} account for $share% of " +
                        "your spending (${Money.format(topTotal)}). Trimming those three by a " +
                        "tenth would free " +
                        "${Money.format((topTotal * TRIM_FRACTION).toLong())} a month, or " +
                        "${Money.format((topTotal * TRIM_FRACTION * 12).toLong())} a year.",
                    annualImpactMinor = (topTotal * TRIM_FRACTION * 12).toLong(),
                    categoryId = top.first().categoryId,
                    categoryName = top.first().name,
                    colorHex = top.first().colorHex,
                ),
            )
        }
    }

    // -------------------------------------------------------------- savings

    internal fun savingsAdvice(
        incomeMinor: Long,
        savedThisMonthMinor: Long,
        savingsBalanceMinor: Long,
        rules: List<RecurringRuleEntity>,
    ): List<Insight> = buildList {
        if (incomeMinor <= 0L) return@buildList

        val rate = savedThisMonthMinor.toFloat() / incomeMinor
        val percent = (rate * 100).roundToInt()

        when {
            rate >= GOOD_SAVINGS_RATE -> add(
                Insight(
                    id = "savings-rate-good",
                    kind = InsightKind.SAVING,
                    severity = InsightSeverity.GOOD,
                    title = "You are saving $percent% of what comes in",
                    message = "${Money.format(savedThisMonthMinor)} put by out of " +
                        "${Money.format(incomeMinor)}. At this rate that is " +
                        "${Money.format(savedThisMonthMinor * 12)} over a year.",
                    annualImpactMinor = savedThisMonthMinor * 12,
                ),
            )

            rate >= LOW_SAVINGS_RATE -> add(
                Insight(
                    id = "savings-rate-ok",
                    kind = InsightKind.SAVING,
                    severity = InsightSeverity.INFO,
                    title = "Saving $percent% of what comes in",
                    message = "${Money.format(savedThisMonthMinor)} a month. Raising it to " +
                        "15% would mean ${Money.format((incomeMinor * 0.15).toLong())} a " +
                        "month — ${Money.format(((incomeMinor * 0.15) - savedThisMonthMinor)
                            .toLong())} more than now.",
                ),
            )

            else -> add(
                Insight(
                    id = "savings-rate-low",
                    kind = InsightKind.SAVING,
                    severity = InsightSeverity.WATCH,
                    title = "Little is being put by this month",
                    message = "${Money.format(savedThisMonthMinor)} saved out of " +
                        "${Money.format(incomeMinor)} coming in. Even " +
                        "${Money.format((incomeMinor * 0.05).toLong())} a month — a twentieth " +
                        "— would be ${Money.format((incomeMinor * 0.05 * 12).toLong())} a year.",
                    annualImpactMinor = (incomeMinor * 0.05 * 12).toLong(),
                ),
            )
        }

        // An emergency fund measured in months of real outgoings, not a
        // round number plucked from nowhere.
        val monthlyOutgoings = rules
            .filter { it.type == TransactionType.EXPENSE && !it.isPaused }
            .sumOf {
                RecurrenceCalculator.monthlyEquivalentMinor(it.amountMinor, it.frequency, it.interval)
            }
        if (monthlyOutgoings > 0L) {
            val target = monthlyOutgoings * EMERGENCY_FUND_MONTHS
            val monthsCovered = savingsBalanceMinor.toDouble() / monthlyOutgoings
            if (savingsBalanceMinor < target) {
                add(
                    Insight(
                        id = "emergency-fund",
                        kind = InsightKind.SAVING,
                        severity = if (monthsCovered < 1) InsightSeverity.WATCH else InsightSeverity.INFO,
                        title = "Your savings would cover ${formatMonths(monthsCovered)}",
                        message = "Your regular bills come to " +
                            "${Money.format(monthlyOutgoings)} a month, so three months' " +
                            "cover would be ${Money.format(target)}. You have " +
                            "${Money.format(savingsBalanceMinor)} — " +
                            "${Money.format(target - savingsBalanceMinor)} short.",
                    ),
                )
            } else {
                add(
                    Insight(
                        id = "emergency-fund-met",
                        kind = InsightKind.SAVING,
                        severity = InsightSeverity.GOOD,
                        title = "You have ${formatMonths(monthsCovered)} of cover",
                        message = "${Money.format(savingsBalanceMinor)} in savings against " +
                            "${Money.format(monthlyOutgoings)} of monthly bills. That is a " +
                            "solid cushion.",
                    ),
                )
            }
        }
    }

    // ---------------------------------------------------------------- bills

    internal fun billsAdvice(
        rules: List<RecurringRuleEntity>,
        spendingMinor: Long,
        unconfirmedCount: Int,
        overdueCount: Int,
    ): List<Insight> = buildList {
        if (overdueCount > 0) {
            add(
                Insight(
                    id = "overdue",
                    kind = InsightKind.BILLS,
                    severity = InsightSeverity.ACT,
                    title = "$overdueCount ${if (overdueCount == 1) "payment is" else "payments are"} overdue",
                    message = "These have passed their due date without being recorded, so " +
                        "your balances are showing more than you really have.",
                ),
            )
        }

        if (unconfirmedCount > 0) {
            add(
                Insight(
                    id = "unconfirmed",
                    kind = InsightKind.BILLS,
                    severity = InsightSeverity.WATCH,
                    title = "$unconfirmedCount ${if (unconfirmedCount == 1) "amount needs" else "amounts need"} checking",
                    message = "These are bills whose amount changes each time. Until you " +
                        "confirm them, your figures use last month's amount.",
                ),
            )
        }

        // Subscriptions add up quietly; that is the whole point of them.
        val subscriptions = rules.filter { rule ->
            rule.type == TransactionType.EXPENSE && !rule.isPaused &&
                rule.frequency in setOf(Frequency.MONTHLY, Frequency.YEARLY) &&
                rule.amountMinor <= 3_000L
        }
        if (subscriptions.size >= 3) {
            val monthly = subscriptions.sumOf {
                RecurrenceCalculator.monthlyEquivalentMinor(it.amountMinor, it.frequency, it.interval)
            }
            val share = if (spendingMinor > 0L) monthly.toDouble() / spendingMinor else 0.0
            add(
                Insight(
                    id = "subscriptions",
                    kind = InsightKind.BILLS,
                    severity = if (share >= SUBSCRIPTION_SHARE_THRESHOLD) {
                        InsightSeverity.WATCH
                    } else {
                        InsightSeverity.INFO
                    },
                    title = "${subscriptions.size} small regular payments",
                    message = "${subscriptions.take(4).joinToString(", ") { it.name }}" +
                        (if (subscriptions.size > 4) " and others" else "") +
                        " come to ${Money.format(monthly)} a month — " +
                        "${Money.format(monthly * 12)} a year. Worth checking you still use " +
                        "all of them.",
                    annualImpactMinor = monthly * 12,
                ),
            )
        }
    }

    // ------------------------------------------------------------- forecast

    internal fun forecastAdvice(forecast: Forecast): List<Insight> = buildList {
        if (!forecast.isReliable) {
            add(
                Insight(
                    id = "forecast-early",
                    kind = InsightKind.FORECAST,
                    severity = InsightSeverity.INFO,
                    title = "Not enough history to look ahead yet",
                    message = "After a couple of complete months the app can project your " +
                        "balance forward. Until then it would be guessing.",
                ),
            )
            return@buildList
        }

        forecast.firstShortfallMonth?.let { month ->
            add(
                Insight(
                    id = "shortfall",
                    kind = InsightKind.FORECAST,
                    severity = InsightSeverity.ACT,
                    title = "Money runs short in ${DateUtils.formatMonth(month)}",
                    message = "On what is set up and what you normally spend, the balance " +
                        "goes below zero in ${DateUtils.formatMonth(month)}. Bringing " +
                        "spending down by " +
                        "${Money.format(abs(forecast.typicalMonthlyNetMinor).coerceAtLeast(1L))} " +
                        "a month would close the gap.",
                ),
            )
        }

        add(
            Insight(
                id = "end-of-month",
                kind = InsightKind.FORECAST,
                severity = if (forecast.endOfMonthBalanceMinor < 0L) {
                    InsightSeverity.ACT
                } else {
                    InsightSeverity.INFO
                },
                title = "Expected at the end of the month",
                message = "${Money.format(forecast.endOfMonthBalanceMinor)}, after the " +
                    "${Money.format(forecast.remainingBillsMinor)} of bills still to come " +
                    "and the ${Money.format(forecast.remainingIncomeMinor)} still expected in.",
            ),
        )

        val twelveMonths = forecast.typicalMonthlyNetMinor * 12
        if (forecast.typicalMonthlyNetMinor != 0L) {
            add(
                Insight(
                    id = "annual-trajectory",
                    kind = InsightKind.FORECAST,
                    severity = if (twelveMonths < 0L) InsightSeverity.WATCH else InsightSeverity.GOOD,
                    title = "A year at this rate",
                    message = if (twelveMonths < 0L) {
                        "You are running ${Money.format(abs(forecast.typicalMonthlyNetMinor))} " +
                            "short in a typical month. Over a year that is " +
                            "${Money.format(abs(twelveMonths))}."
                    } else {
                        "You keep about " +
                            "${Money.format(forecast.typicalMonthlyNetMinor)} in a typical " +
                            "month — ${Money.format(twelveMonths)} over a year."
                    },
                    annualImpactMinor = abs(twelveMonths),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- goals

    internal fun goalAdvice(
        goals: List<SavingsGoalWithProgress>,
        today: LocalDate,
    ): List<Insight> = buildList {
        goals.forEach { goal ->
            val targetDate = goal.goal.targetDate ?: return@forEach
            if (goal.remainingMinor <= 0L) {
                add(
                    Insight(
                        id = "goal-done-${goal.goal.id}",
                        kind = InsightKind.GOAL,
                        severity = InsightSeverity.GOOD,
                        title = "${goal.goal.name} is there",
                        message = "${Money.format(goal.currentAmountMinor)} against a target " +
                            "of ${Money.format(goal.goal.targetAmountMinor)}.",
                        colorHex = goal.goal.colorHex,
                    ),
                )
                return@forEach
            }

            val months = java.time.temporal.ChronoUnit.MONTHS.between(
                YearMonth.from(today),
                YearMonth.from(targetDate),
            )
            if (months <= 0L) return@forEach

            val needed = goal.remainingMinor / months
            val contributing = goal.goal.monthlyContributionMinor
            if (contributing < needed) {
                add(
                    Insight(
                        id = "goal-behind-${goal.goal.id}",
                        kind = InsightKind.GOAL,
                        severity = InsightSeverity.WATCH,
                        title = "${goal.goal.name} will not make its date",
                        message = "${Money.format(goal.remainingMinor)} still to find over " +
                            "$months months means ${Money.format(needed)} a month. You are " +
                            "putting by ${Money.format(contributing)} — " +
                            "${Money.format(needed - contributing)} short.",
                        colorHex = goal.goal.colorHex,
                    ),
                )
            }
        }
    }

    /** "just over 2 months", "a fortnight" — a figure people can picture. */
    internal fun formatMonths(months: Double): String = when {
        months < 0.25 -> "under a week"
        months < 0.75 -> "a couple of weeks"
        months < 1.25 -> "about a month"
        months < 2.0 -> "about six weeks"
        else -> "about ${months.roundToInt()} months"
    }
}
