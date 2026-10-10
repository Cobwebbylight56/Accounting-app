package com.rhys.financetracker.data.importer

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Finds a person's wage in what has come into their accounts, so it never
 * has to be typed in: the same employer paying a good sum into the same
 * account month after month.
 *
 * Money from people, refunds, interest and transfers between the
 * household's own accounts are not wages, and neither is a payer that has
 * stopped paying.
 */
object WageFinder {

    /** One payment in, as the finder sees it. */
    data class PaidIn(
        val description: String,
        val amountMinor: Long,
        val date: LocalDate,
        val accountId: Long,
        val categoryName: String?,
        /** Whose account it landed in. */
        val personId: Long?,
    )

    /** A wage found: who it is paid to, into which account, how much and on which day. */
    data class Wage(
        val personId: Long,
        val accountId: Long,
        val payer: String,
        /** What a month's pay usually is: the middle of the last few. */
        val amountMinor: Long,
        /** The day of the month it usually lands. */
        val day: Int,
        val lastDate: LocalDate,
        val timesPaid: Int,
    )

    /** The wage paid to each person, the biggest where there are two jobs' worth. */
    fun find(payments: List<PaidIn>, today: LocalDate): List<Wage> {
        val candidates = payments.filter { isPay(it) }
        val owners = candidates.associate { it.accountId to it.personId }
        val asPayments = candidates.map { RecurringDetector.Payment(it.description, it.amountMinor, it.date, it.categoryName, it.accountId) }
        val found = asPayments.groupBy { it.accountId }.values
            .flatMap { RecurringDetector.series(it) }
            .mapNotNull { series -> judge(series, owners, today) }
        // One wage per person: a salary category first, then the most paid.
        return found.groupBy { it.first.personId }
            .map { (_, wages) -> wages.maxWith(compareBy<Pair<Wage, Boolean>> { it.second }.thenBy { it.first.amountMinor }).first }
    }

    private fun judge(series: RecurringDetector.Series, owners: Map<Long, Long?>, today: LocalDate): Pair<Wage, Boolean>? {
        val sorted = series.payments.sortedBy { it.date }
        val months = sorted.map { it.date.withDayOfMonth(1) }.distinct()
        if (months.size < MIN_MONTHS) return null
        val last = sorted.last()
        // Still being paid: the last one within about a month and a half.
        if (ChronoUnit.DAYS.between(last.date, today) > STILL_PAID_DAYS) return null
        val frequency = RecurringDetector.intervalOf(sorted.map { it.date }) ?: return null
        if (frequency != com.rhys.financetracker.domain.model.Frequency.MONTHLY &&
            frequency != com.rhys.financetracker.domain.model.Frequency.FOUR_WEEKLY
        ) {
            return null
        }
        val recent = sorted.takeLast(RECENT)
        val amount = recent.map { it.amountMinor }.sorted()[recent.size / 2]
        if (amount < MIN_WAGE) return null
        // Overtime moves it about; a payer at all sorts of amounts is not a wage.
        val amounts = sorted.map { it.amountMinor }
        if (amounts.max().toDouble() / amounts.min().coerceAtLeast(1L) > MOST_SPREAD) return null
        val personId = owners[last.accountId] ?: return null
        val day = recent.map { it.date.dayOfMonth }.sorted()[recent.size / 2]
        val isSalary = sorted.any { it.categoryName.equals(SpreadsheetImporter.SALARY_CATEGORY, ignoreCase = true) }
        return Wage(
            personId = personId,
            accountId = last.accountId,
            payer = titleCase(series.key),
            amountMinor = amount,
            day = day,
            lastDate = last.date,
            timesPaid = sorted.size,
        ) to isSalary
    }

    /** Pay, not money from a person, a refund, interest or a move between accounts. */
    private fun isPay(payment: PaidIn): Boolean {
        if (payment.amountMinor <= 0L) return false
        val category = payment.categoryName
        if (category.equals(SpreadsheetImporter.SALARY_CATEGORY, ignoreCase = true)) return true
        if (category != null && category in NOT_PAY) return false
        // Money from people is filed under Transfers & payments, left out
        // above; a two-word employer can read like a person's name, so the
        // name alone is no reason to leave it out.
        val words = TransactionFingerprint.normaliseDescription(payment.description).split(' ')
        return words.none { it in NOT_PAY_WORDS }
    }

    private fun titleCase(text: String) =
        text.split(' ').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    private val NOT_PAY = setOf(
        "Transfers & payments", "Transfer", "Refunds", "Interest", "Cash received", "Savings", "Cash",
        "People & services",
    )

    private val NOT_PAY_WORDS = setOf(
        "refund", "refunded", "reversal", "returned", "interest", "cashback", "transfer", "tfr",
    )

    private const val MIN_MONTHS = 2
    private const val STILL_PAID_DAYS = 45L
    private const val RECENT = 3

    /** Less than this a month is not a wage worth planning around: £300. */
    private const val MIN_WAGE = 30_000L

    /** The most a month's pay may be over the least: overtime, a bonus month. */
    private const val MOST_SPREAD = 2.5
}
