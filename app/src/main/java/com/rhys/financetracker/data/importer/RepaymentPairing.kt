package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import kotlin.math.abs

/**
 * Finds a credit card or loan payment counted twice.
 *
 * Paying the Capital One card from Nationwide shows on both statements:
 * money out of Nationwide, and "Direct Debit Payment - Thank You" in on the
 * card. Read on their own, the first is spending and the second income, and
 * the card's spending was already counted when the card was used. Paired
 * up, they are one move from one account to the other: no spending, no
 * income, and the card's balance still comes down.
 */
object RepaymentPairing {

    /** A card, loan or other borrowing paid off from elsewhere. */
    data class Owed(
        val id: Long,
        val name: String,
        /** The day its balance was given for: rows on it are inside that figure. */
        val balanceDate: LocalDate,
    )

    /** One entry, as the pairing sees it. */
    data class Entry(
        val id: Long,
        val accountId: Long,
        val type: TransactionType,
        val transferAccountId: Long?,
        val amountMinor: Long,
        val date: LocalDate,
        val description: String,
        val source: RecordSource,
        val categoryName: String?,
        /** Filed by the user: left as they put it, unless filed as a repayment. */
        val categoryByUser: Boolean,
    )

    sealed interface Step {
        /** The money in on the card is a move already held: put it away. */
        data class AlreadyMoved(val incomeId: Long, val transferId: Long) : Step

        /** The two halves of one payment: the money out becomes the move, the money in is put away. */
        data class Paired(val incomeId: Long, val expenseId: Long, val owedId: Long) : Step
    }

    /**
     * What to do with each payment into [owed] accounts that is held twice.
     *
     * @param mayPayFrom whether money out of the first account could be paying
     *   the second: the same person's, or shared.
     */
    fun find(
        owed: List<Owed>,
        entries: List<Entry>,
        mayPayFrom: (fromAccountId: Long, owedId: Long) -> Boolean,
    ): List<Step> {
        val owedById = owed.associateBy { it.id }
        val used = mutableSetOf<Long>()
        val steps = mutableListOf<Step>()
        val incomes = entries
            .filter { it.type == TransactionType.INCOME && it.accountId in owedById && mayBeARepayment(it) }
            .sortedBy { it.date }
        for (income in incomes) {
            val card = owedById.getValue(income.accountId)
            val counts = counts(income.date, income.source, card)
            fun near(other: Entry) = other.id !in used && other.amountMinor == income.amountMinor &&
                abs(other.date.toEpochDay() - income.date.toEpochDay()) <= MARGIN_DAYS &&
                // A move dated the other side of the card's balance day would
                // change what the card's balance comes to.
                counts(other.date, other.source, card) == counts

            val moved = entries.filter { it.type == TransactionType.TRANSFER && it.transferAccountId == card.id && near(it) }
                .minByOrNull { abs(it.date.toEpochDay() - income.date.toEpochDay()) }
            if (moved != null) {
                used += moved.id
                steps += Step.AlreadyMoved(income.id, moved.id)
                continue
            }
            val paid = entries.filter { other ->
                other.type == TransactionType.EXPENSE && other.accountId !in owedById &&
                    near(other) && mayPayFrom(other.accountId, card.id) && mayPayOff(other)
            }
            // One that names the card; or else one filed as paying credit
            // that names none of the others.
            val named = paid.filter { names(it, card) }
            val chosen = named.ifEmpty { paid.filter { it.categoryName == CREDIT && owed.none { other -> names(it, other) } } }
            // Two payments the same size, the same week, from two places:
            // which one paid the card can't be told, so neither is touched.
            val only = chosen.singleOrNull() ?: continue
            used += only.id
            steps += Step.Paired(income.id, only.id, card.id)
        }
        return steps
    }

    /**
     * Money out of a bank account that names one of the household's cards or
     * loans — "CAPITAL ONE DD", "ZOPA BANK LTD" — paired with the one it pays:
     * a move into it, not spending, whether or not that card's statement is
     * in yet. Left as spending, it was counted twice once the card's own
     * statement came in with what was bought on it.
     *
     * Only when the payment names one of them, plainly — a word of four
     * letters or more from its name ("capital", "zopa"), so an account called
     * "car" doesn't claim NCP CAR PARK — and the user didn't file it as
     * something else.
     */
    fun namedRepayments(
        owed: List<Owed>,
        entries: List<Entry>,
        mayPayFrom: (fromAccountId: Long, owedId: Long) -> Boolean,
    ): List<Paired> {
        val owedIds = owed.map { it.id }.toSet()
        return entries
            .filter { it.type == TransactionType.EXPENSE && it.accountId !in owedIds && mayPayOff(it) }
            .mapNotNull { entry ->
                val text = TransactionFingerprint.normaliseDescription(entry.description)
                val named = owed.filter { card -> namesPlainly(text, card.name) && mayPayFrom(entry.accountId, card.id) }
                named.singleOrNull()?.let { Paired(entry.id, it.id) }
            }
    }

    /** A payment out and the card or loan it pays. */
    data class Paired(val expenseId: Long, val owedId: Long)

    private fun namesPlainly(text: String, name: String): Boolean {
        if (OwnAccountMatcher.nameScore(name, text) == null) return false
        val padded = " $text "
        return TransactionFingerprint.normaliseDescription(name).split(' ')
            .filter { it.length >= PLAIN_WORD && it !in NOT_A_NAME }
            .any { padded.contains(" $it ") }
    }

    /** Words in a card's or loan's name that don't say whose it is. */
    private val NOT_A_NAME = setOf("card", "credit", "loan", "loans", "finance", "mortgage", "account", "bank")
    private const val PLAIN_WORD = 4

    /** Money in on a card that may be it being paid: not a refund, nor filed as something else. */
    private fun mayBeARepayment(entry: Entry): Boolean {
        val category = entry.categoryName
        if (category != null && category.equals(REFUNDS, ignoreCase = true)) return false
        if (entry.categoryByUser && category != null && category !in REPAYMENT_CATEGORIES) return false
        return true
    }

    /** Money out that isn't filed by the user as something else. */
    private fun mayPayOff(entry: Entry): Boolean {
        val category = entry.categoryName
        return !(entry.categoryByUser && category != null && category !in REPAYMENT_CATEGORIES)
    }

    /** Whether [entry] names [card]: "CAPITAL ONE" for the Capital One card. */
    private fun names(entry: Entry, card: Owed): Boolean =
        OwnAccountMatcher.nameScore(card.name, TransactionFingerprint.normaliseDescription(entry.description)) != null

    /** Whether an entry on [date] moves the card's balance: see AccountDao.COUNTS. */
    private fun counts(date: LocalDate, source: RecordSource, card: Owed): Boolean =
        date.isAfter(card.balanceDate) || (date == card.balanceDate && source == RecordSource.MANUAL)

    private const val CREDIT = "Credit & loans"
    private const val REFUNDS = "Refunds"
    private val REPAYMENT_CATEGORIES = setOf(CREDIT, MerchantCategoriser.PEOPLE_IN)

    /** A payment leaves one bank and reaches the card within a few working days. */
    const val MARGIN_DAYS = 4L

    /** The note on money in that is now counted as the move instead; it comes back if the move goes. */
    const val NOTE = "Counted once, as the payment from your other account."
}
