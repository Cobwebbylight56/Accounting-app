package com.rhys.financetracker.domain.insight

import java.time.LocalDate
import java.time.YearMonth

/**
 * A single piece of advice.
 *
 * The rules these follow, because financial advice that is wrong or nagging is
 * worse than none:
 *
 *  * **Say the number.** "You spent £42 more on takeaways than usual", never
 *    "your spending has increased".
 *  * **Only when there is enough history to mean it.** Every rule states its own
 *    minimum; below that it stays quiet rather than guessing from one month.
 *  * **No moralising.** The app reports what the figures show and what the
 *    arithmetic implies. It does not tell anyone off.
 */
data class Insight(
    val id: String,
    val kind: InsightKind,
    val severity: InsightSeverity,
    val title: String,
    /** One or two sentences, in plain words, always containing the figure. */
    val message: String,
    /** What acting on it would be worth over a year, when that can be said. */
    val annualImpactMinor: Long? = null,
    /** Set when the insight is about one category, so it can be tapped through. */
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val colorHex: String? = null,
)

enum class InsightKind(val displayName: String) {
    SPENDING("Spending"),
    SAVING("Saving"),
    BILLS("Bills"),
    FORECAST("Looking ahead"),
    GOAL("Goals"),
}

/**
 * How much attention an insight deserves.  Used for ordering and colour, not to
 * decide whether to show it — a quiet month should still say so.
 */
enum class InsightSeverity(val weight: Int) {
    /** Something is going well and is worth knowing. */
    GOOD(0),

    /** Neutral information. */
    INFO(1),

    /** Worth a look. */
    WATCH(2),

    /** Money will run out, or a goal will be missed, unless something changes. */
    ACT(3),
}

/**
 * What the figures imply about the months ahead.
 *
 * Everything here is arithmetic on what is already recorded — recurring rules
 * that are known, plus the household's own recent averages. Nothing is
 * extrapolated from a single month, and nothing is invented.
 */
data class Forecast(
    /** The balance expected at the end of the current month. */
    val endOfMonthBalanceMinor: Long,
    /** Bills still to be paid before the month ends. */
    val remainingBillsMinor: Long,
    /** Income still expected before the month ends. */
    val remainingIncomeMinor: Long,
    /** One point per month, starting with the current one. */
    val monthlyProjection: List<ProjectedMonth>,
    /** The month the balance is first expected to go below zero, if any. */
    val firstShortfallMonth: YearMonth?,
    /** Typical monthly surplus or deficit, from the months already recorded. */
    val typicalMonthlyNetMinor: Long,
    /** How many months of history the projection is based on. */
    val monthsOfHistory: Int,
) {
    val isReliable: Boolean get() = monthsOfHistory >= MIN_MONTHS_FOR_FORECAST

    companion object {
        /**
         * Below this, a projection says more about one unusual month than about
         * the household, so the app declines to draw one.
         */
        const val MIN_MONTHS_FOR_FORECAST = 2

        val EMPTY = Forecast(
            endOfMonthBalanceMinor = 0L,
            remainingBillsMinor = 0L,
            remainingIncomeMinor = 0L,
            monthlyProjection = emptyList(),
            firstShortfallMonth = null,
            typicalMonthlyNetMinor = 0L,
            monthsOfHistory = 0,
        )
    }
}

/** One month of the projection. */
data class ProjectedMonth(
    val month: YearMonth,
    val openingBalanceMinor: Long,
    val expectedIncomeMinor: Long,
    val expectedSpendingMinor: Long,
    val closingBalanceMinor: Long,
    /** False for the current month, which is partly real rather than projected. */
    val isProjected: Boolean,
) {
    val netMinor: Long get() = expectedIncomeMinor - expectedSpendingMinor
}

/** How one category is behaving against its own recent history. */
data class CategoryTrend(
    val categoryId: Long?,
    val name: String,
    val colorHex: String?,
    val thisMonthMinor: Long,
    /** Mean of the completed months before this one. */
    val averageMinor: Long,
    val monthsCompared: Int,
    val budgetMinor: Long?,
) {
    val differenceMinor: Long get() = thisMonthMinor - averageMinor

    /** Change against the average, or null when there is nothing to compare with. */
    val percentChange: Int?
        get() = if (averageMinor <= 0L) {
            null
        } else {
            ((differenceMinor.toDouble() / averageMinor) * 100).toInt()
        }

    val isOverBudget: Boolean
        get() = budgetMinor != null && thisMonthMinor > budgetMinor

    /** True once the month is far enough on for a comparison to be fair. */
    fun isComparable(today: LocalDate): Boolean =
        monthsCompared >= 2 && today.dayOfMonth >= FAIR_COMPARISON_DAY

    companion object {
        /**
         * Comparing a whole month's average against the first few days of a new
         * one always looks like an improvement.  Wait until the month is far
         * enough through to be worth reporting.
         */
        const val FAIR_COMPARISON_DAY = 10
    }
}

/** Everything the advice screen shows. */
data class InsightReport(
    val month: YearMonth,
    val insights: List<Insight>,
    val forecast: Forecast,
    val trends: List<CategoryTrend>,
    /** Share of income kept rather than spent, 0..1. */
    val savingsRate: Float,
    val hasEnoughHistory: Boolean,
) {
    val topPriority: Insight? get() = insights.firstOrNull()

    fun of(kind: InsightKind): List<Insight> = insights.filter { it.kind == kind }

    companion object {
        val EMPTY = InsightReport(
            month = YearMonth.now(),
            insights = emptyList(),
            forecast = Forecast.EMPTY,
            trends = emptyList(),
            savingsRate = 0f,
            hasEnoughHistory = false,
        )
    }
}
