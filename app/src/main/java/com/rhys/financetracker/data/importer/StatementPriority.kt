package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.ExistingEntry
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.temporal.ChronoUnit

/**
 * Pairs a statement row with the entry already held for the same payment, so
 * the bank's version can replace a remembered one instead of sitting beside it.
 *
 * ## Why this is needed at all
 *
 * Duplicate checking is a fingerprint of the account, date, amount, direction
 * and description, which recognises the *same file* imported twice. It cannot
 * recognise the *same payment* written down twice, because the two never agree
 * on the wording:
 *
 * ```
 * spreadsheet   01 Mar   Virgin media                    46.50
 * statement     03 Mar   VIRGIN MEDIA PAYMENTS 998812    46.50
 * ```
 *
 * Different day, different words, one payment. Left alone the ledger holds it
 * twice and every total for March is £46.50 too high.
 *
 * ## What is matched
 *
 * The amount and the direction must agree exactly — money is the one part of
 * the record nobody paraphrases. The date only has to be close, because a
 * spreadsheet is usually dated the day somebody wrote it down rather than the
 * day the money moved.
 *
 * That alone is not enough to act on: on a busy account two different £20
 * payments a day apart would match each other. So a pair is only accepted when
 * one of two further things is true.
 *
 * * **The payees agree** — they share a distinctive word, as "Virgin media"
 *   and "VIRGIN MEDIA PAYMENTS 998812" share two. Then a wider spread of days
 *   is allowed, since there is real evidence beyond coincidence.
 * * **Neither has any other option** — one entry of that amount in the file,
 *   one in the ledger, nothing else it could be.
 *
 * Anything else is left as it is. An unmerged pair shows up as two similar
 * rows, which is visible and takes one tap to delete; a wrongly merged pair
 * quietly attaches somebody's note to the wrong payment, and nothing on screen
 * would ever say so.
 */
object StatementPriority {

    /**
     * A statement row and the stored entry it was found to be describing.
     *
     * Carries what the stored entry said so the review screen can show what is
     * about to change, rather than announcing a correction unseen.
     */
    data class Correction(
        val candidateId: String,
        val existing: ExistingEntry,
        /** True when the payees agreed, rather than the pair being the only option. */
        val payeesAgreed: Boolean,
    )

    /**
     * Every correction that can be made with confidence, keyed by candidate id.
     *
     * [existing] should already be limited to the account being imported into
     * and to sources a statement outranks; both are checked again here so the
     * rule holds wherever this is called from.
     */
    fun corrections(
        candidates: List<ImportCandidate>,
        existing: List<ExistingEntry>,
    ): Map<String, Correction> {
        val rows = candidates.mapNotNull { candidate ->
            val date = candidate.dateIso?.let { DateUtils.parseIsoOrNull(it) }
            if (!candidate.isImportable ||
                candidate.target != ImportTarget.TRANSACTION ||
                candidate.isAlreadyPresent ||
                date == null
            ) {
                null
            } else {
                candidate to date
            }
        }
        val correctable = existing.filter { it.source.yieldsTo(RecordSource.STATEMENT) }
        if (rows.isEmpty() || correctable.isEmpty()) return emptyMap()

        // Every pair the amount, direction and date allow, before any judgement
        // about whether it can be acted on.
        val possible = mutableListOf<Possible>()
        for ((candidate, date) in rows) {
            val type = candidate.transactionType ?: TransactionType.EXPENSE
            for (entry in correctable) {
                if (entry.amountMinor != candidate.amountMinor || entry.type != type) continue
                val agreed = payeesAgree(candidate.name, entry.description)
                val gap = kotlin.math.abs(ChronoUnit.DAYS.between(entry.date, date))
                if (gap <= if (agreed) AGREED_DAYS else NEARBY_DAYS) {
                    possible += Possible(candidate, entry, agreed, gap)
                }
            }
        }
        if (possible.isEmpty()) return emptyMap()

        // How much choice each side has, counted before anything is claimed:
        // "the only thing it could be" has to mean that of the whole file, not
        // of whatever happened to be left by the time we got here.
        val optionsForCandidate = possible.groupingBy { it.candidate.id }.eachCount()
        val optionsForEntry = possible.groupingBy { it.entry.id }.eachCount()

        // Best evidence first, so an agreeing pair claims its entry before a
        // merely-close one can, and the nearer date wins between equals. The
        // entry id only breaks a remaining tie, so the result never depends on
        // what order the rows happened to arrive in.
        val ordered = possible.sortedWith(
            compareByDescending<Possible> { it.payeesAgreed }
                .thenBy { it.dayGap }
                .thenBy { it.entry.id },
        )

        val corrections = mutableMapOf<String, Correction>()
        val claimed = mutableSetOf<Long>()
        for (pair in ordered) {
            if (pair.candidate.id in corrections || pair.entry.id in claimed) continue
            val onlyOption = optionsForCandidate[pair.candidate.id] == 1 &&
                optionsForEntry[pair.entry.id] == 1
            if (!pair.payeesAgreed && !onlyOption) continue
            corrections[pair.candidate.id] =
                Correction(pair.candidate.id, pair.entry, pair.payeesAgreed)
            claimed += pair.entry.id
        }
        return corrections
    }

    /** A pairing the amounts and dates allow, with the evidence for it. */
    private data class Possible(
        val candidate: ImportCandidate,
        val entry: ExistingEntry,
        val payeesAgreed: Boolean,
        val dayGap: Long,
    )

    /**
     * True when two descriptions of a payment share a distinctive word.
     *
     * Distinctive is doing the work: "payment", "card" and "ltd" appear on half
     * a statement and agreeing on one of those is no evidence at all. What is
     * left — a brand, a branch number — is what actually identifies a payee.
     */
    internal fun payeesAgree(one: String, other: String): Boolean {
        val first = distinctiveWords(one)
        if (first.isEmpty()) return false
        return distinctiveWords(other).any { it in first }
    }

    private fun distinctiveWords(text: String): Set<String> =
        TransactionFingerprint.normaliseDescription(text)
            .split(' ')
            .filter { it.length >= MIN_WORD && it !in COMMON_WORDS }
            .toSet()

    /**
     * How far apart the dates may be when the payees agree.
     *
     * Wide enough for a bill written down at the start of the month and taken
     * a week later, and still nowhere near the month between one payment of a
     * monthly bill and the next.
     */
    private const val AGREED_DAYS = 10L

    /** How far apart they may be on the amount alone, which is weaker evidence. */
    private const val NEARBY_DAYS = 4L

    /** Shorter than this identifies nothing: "co", "uk", "dd". */
    private const val MIN_WORD = 4

    /** Words that appear on everything and so distinguish nothing. */
    private val COMMON_WORDS = setOf(
        "payment", "payments", "card", "debit", "credit", "direct", "online",
        "bank", "transfer", "limited", "plc", "ltd", "from", "with", "purchase",
        "standing", "order", "faster", "monthly", "annual", "subscription",
    )
}
