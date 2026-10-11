package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.TransactionType
import kotlin.math.abs

/**
 * Finds spending on a credit card that was read as money in.
 *
 * Before card statements were read the right way round, the balance going
 * up — which on a card is spending — was taken for money arriving. So the
 * Audible subscription paid on the Zopa card showed as £8.99 in for two
 * months, and missing from the months it was paid.
 *
 * Money in on a card from a payee it is paid to regularly — the same name,
 * the same amount, on about the same day of the month — with no payment
 * near it to be a refund of, is one of those payments.
 */
object CardSpendingCheck {

    /** Money in to turn into spending, filed like [likeId], the payments it is one of. */
    data class Misread(val id: Long, val likeId: Long)

    fun find(entries: List<RepaymentPairing.Entry>, cardIds: Set<Long>): List<Misread> {
        val onCards = entries.filter { it.accountId in cardIds }
        val spending = onCards.filter { it.type == TransactionType.EXPENSE }
            .groupBy { Triple(it.accountId, RecurringDetector.keyOf(it.description), it.amountMinor) }
            .filterKeys { it.second.isNotBlank() }
        return onCards
            .filter { it.type == TransactionType.INCOME && mayBeMisread(it) }
            .mapNotNull { income ->
                val series = spending[Triple(income.accountId, RecurringDetector.keyOf(income.description), income.amountMinor)]
                    ?: return@mapNotNull null
                if (series.size < MIN_PAID) return@mapNotNull null
                // A refund sits near the payment it gives back.
                if (series.any { abs(it.date.toEpochDay() - income.date.toEpochDay()) <= REFUND_DAYS }) return@mapNotNull null
                // And it falls on the series' day of the month.
                val days = series.map { it.date.dayOfMonth }.sorted()
                val usual = days[days.size / 2]
                val apart = abs(income.date.dayOfMonth - usual).let { minOf(it, DAYS_IN_MONTH - it) }
                if (apart > DAY_SLACK) return@mapNotNull null
                Misread(income.id, series.maxBy { it.date }.id)
            }
    }

    /** Not filed by the user, nor a refund, interest, cashback or the card being paid. */
    private fun mayBeMisread(entry: RepaymentPairing.Entry): Boolean {
        if (entry.categoryByUser) return false
        if (entry.categoryName in NOT_SPENDING) return false
        if (PdfStatementParser.cardDirectionFromWording(entry.description) == false) return false
        val words = TransactionFingerprint.normaliseDescription(entry.description).split(' ')
        return words.none { it in GIVEN_BACK }
    }

    private val NOT_SPENDING = setOf("Refunds", "Interest", MerchantCategoriser.PEOPLE_IN, "Cashback")
    private val GIVEN_BACK = setOf(
        "refund", "refunded", "reversal", "reversed", "returned", "credit", "interest", "cashback", "rebate", "payment",
    )

    /** Seen at least this often as spending before money in from it is doubted. */
    private const val MIN_PAID = 2

    /** A refund comes within a couple of weeks of what it refunds. */
    private const val REFUND_DAYS = 14L

    private const val DAY_SLACK = 4
    private const val DAYS_IN_MONTH = 31
}
