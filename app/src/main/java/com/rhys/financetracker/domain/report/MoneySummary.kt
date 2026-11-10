package com.rhys.financetracker.domain.report

import java.util.Locale
import kotlin.math.abs

/**
 * A month of the household's money in a few plain lines, to share with the
 * Claude app and ask what it makes of it.
 *
 * Claude's own personal-finance feature links bank accounts directly, and
 * only in the US. This needs no link: the app writes the summary, it's shown
 * in full before anything leaves the phone, and it goes only where it's
 * shared. Totals, categories and payee names — never an account number,
 * sort code or card number.
 */
object MoneySummary {

    data class Input(
        /** "October 2026", or "November 2026 so far". */
        val periodName: String,
        val figures: ProgressFigures,
        /** The stretch before, to compare with; null when there's nothing to compare. */
        val before: ProgressFigures? = null,
        val beforeName: String? = null,
        /** The biggest payments: who to, and how much. */
        val biggest: List<Pair<String, Long>> = emptyList(),
        /** What's owed on cards and loans now, what's saved, and what's to spend. */
        val owedNowMinor: Long = 0,
        val savedNowMinor: Long = 0,
        val availableNowMinor: Long = 0,
        /** How many people's money this is. */
        val people: Int = 1,
    )

    /** The text to share, starting with the question to ask. */
    fun text(input: Input): String = buildString {
        val whose = if (input.people >= 2) "our household's" else "my"
        appendLine(
            "Here's a summary of $whose money for ${input.periodName}, from the finance app I use. " +
                "Amounts are in pounds. What stands out to you, where could we cut back, and what's one " +
                "thing worth trying next month?",
        )
        appendLine()
        val f = input.figures
        appendLine(input.periodName)
        appendLine("- Money in: ${pounds(f.moneyInMinor)}")
        appendLine("- Spent: ${pounds(f.spentMinor)}")
        appendLine("- Left over: ${signed(f.leftOverMinor)}")
        if (f.savedMinor != 0L) appendLine("- Put into savings: ${signed(f.savedMinor)}")
        if (f.owedChangeMinor != 0L) {
            val way = if (f.owedChangeMinor > 0) "up" else "down"
            appendLine("- Owed on cards and loans: $way ${pounds(f.owedChangeMinor)}")
        }

        val before = input.before
        if (before != null && !before.isEmpty && input.beforeName != null) {
            appendLine()
            appendLine("Compared with ${input.beforeName}")
            appendLine("- Money in: ${change(f.moneyInMinor - before.moneyInMinor)}")
            appendLine("- Spent: ${change(f.spentMinor - before.spentMinor)}")
        }

        val categories = f.byCategory.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (categories.isNotEmpty()) {
            appendLine()
            appendLine("Spending by category")
            categories.take(MAX_CATEGORIES).forEach { (name, minor) -> appendLine("- $name: ${pounds(minor)}") }
            val rest = categories.drop(MAX_CATEGORIES)
            if (rest.isNotEmpty()) appendLine("- Everything else (${rest.size} more): ${pounds(rest.sumOf { it.value })}")
        }

        if (input.biggest.isNotEmpty()) {
            appendLine()
            appendLine("Biggest payments")
            input.biggest.forEach { (payee, minor) -> appendLine("- $payee: ${pounds(minor)}") }
        }

        if (input.owedNowMinor != 0L || input.savedNowMinor != 0L || input.availableNowMinor != 0L) {
            appendLine()
            appendLine("Where things stand now")
            appendLine("- To spend in current accounts: ${signed(input.availableNowMinor)}")
            appendLine("- Saved: ${pounds(input.savedNowMinor)}")
            appendLine("- Owed on cards and loans: ${pounds(input.owedNowMinor)}")
        }
    }.trimEnd()

    /** "£1,234.56". */
    fun pounds(minor: Long): String = "£" + String.format(Locale.UK, "%,.2f", abs(minor) / 100.0)

    /** "£12.00", or "−£12.00" when it's below nothing. */
    private fun signed(minor: Long): String = if (minor < 0) "−" + pounds(minor) else pounds(minor)

    /** "£40.00 more", "£12.50 less", "the same". */
    private fun change(minor: Long): String = when {
        minor > 0 -> "${pounds(minor)} more"
        minor < 0 -> "${pounds(minor)} less"
        else -> "the same"
    }

    /** More categories than this and the rest are added up as one line. */
    const val MAX_CATEGORIES = 12
}
