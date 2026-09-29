package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding
import java.time.LocalDate
import kotlin.math.abs

/**
 * Recognises a statement row that moves money to or from another of the
 * person's own accounts — a payment into their saver, off their car loan,
 * onto their credit card.
 *
 * ## Why
 *
 * Without this, "TRANSFER TO START TO SAVE" was filed as savings on the
 * current account and stopped there: the saver in the app never went up.
 * "CAR LOAN PAYMENT" was ordinary spending, and the loan never came down.
 * Recognised as a move between two accounts, the one row does both halves.
 *
 * ## How, in order of confidence
 *
 * 1. **Learned** — the same payee was filed as a move to that account before.
 * 2. **By name** — every meaningful word of the account's name is in the
 *    description ("Start to Save" in "TRANSFER TO START TO SAVE 1234").
 *    Words like "account" or "savings" say nothing on their own, and a
 *    short word alone ("car") is not enough — "NCP CAR PARK" is not the car
 *    loan — so then the whole name has to appear.
 * 3. **A loan's own payment** — money out for exactly the loan's monthly
 *    payment, within a few days of its payment day.
 * 4. **The only saver** — a row already recognised as savings, when the
 *    person has exactly one set-aside account for it to go to.
 */
object OwnAccountMatcher {

    /** Another account the money could be going to or coming from. */
    data class Target(
        val id: Long,
        val name: String,
        val type: AccountType,
        val holding: Holding,
        /** The regular payment into it, for loans that have one. */
        val monthlyPaymentMinor: Long? = null,
        val paymentDay: Int? = null,
    )

    /** Words that say nothing about which account is meant. */
    private val STOP = setOf(
        "the", "my", "a", "to", "and", "of", "for", "account", "acc", "ac", "current",
        "main", "bank", "savings", "saving", "loan", "loans", "finance", "mortgage",
        "card", "credit", "pot", "space", "fund", "payment", "transfer",
    )

    private const val MEANINGFUL_WORD = 4
    private const val PAYMENT_DAY_SLACK = 5

    fun match(
        description: String,
        amountMinor: Long,
        date: LocalDate?,
        isMoneyOut: Boolean,
        isSavings: Boolean,
        targets: List<Target>,
        learned: Map<String, Long>,
    ): Target? {
        if (targets.isEmpty()) return null
        val text = TransactionFingerprint.normaliseDescription(description)

        learned[text]?.let { id -> targets.firstOrNull { it.id == id }?.let { return it } }

        val named = targets.mapNotNull { target -> nameScore(target.name, text)?.let { target to it } }
        if (named.isNotEmpty()) {
            val best = named.maxOf { it.second }
            named.filter { it.second == best }.singleOrNull()?.let { return it.first }
        }

        if (isMoneyOut && date != null) {
            targets.filter { target ->
                (target.type == AccountType.LOAN || target.type == AccountType.MORTGAGE) &&
                    target.monthlyPaymentMinor == amountMinor &&
                    target.paymentDay?.let { dayDistance(it, date.dayOfMonth) <= PAYMENT_DAY_SLACK } == true
            }.singleOrNull()?.let { return it }
        }

        if (isSavings) {
            targets.filter { it.holding == Holding.SET_ASIDE }.singleOrNull()?.let { return it }
        }
        return null
    }

    /**
     * How strongly [name] is named in [text] — the number of its meaningful
     * words — or null when it is not.
     */
    internal fun nameScore(name: String, text: String): Int? {
        val padded = " $text "
        val words = TransactionFingerprint.normaliseDescription(name).split(' ')
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val meaningful = words.filterNot { it in STOP }
        val phrase = words.joinToString(" ")
        val whole = padded.contains(" $phrase ") ||
            (phrase.replace(" ", "").length >= 6 &&
                text.replace(" ", "").contains(phrase.replace(" ", "")))
        return when {
            meaningful.isEmpty() -> null
            meaningful.all { padded.contains(" $it ") } &&
                meaningful.any { it.length >= MEANINGFUL_WORD } -> meaningful.size
            whole -> meaningful.size
            else -> null
        }
    }

    /** Days between two days of the month, going round the end of the month. */
    private fun dayDistance(a: Int, b: Int): Int {
        val d = abs(a - b)
        return minOf(d, 31 - d)
    }
}
