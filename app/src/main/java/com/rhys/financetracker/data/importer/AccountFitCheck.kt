package com.rhys.financetracker.data.importer

import com.rhys.financetracker.data.local.projection.AccountPayee

/**
 * Notices when a statement is about to be filed against the wrong account.
 *
 * Nothing else in the app can catch this. The rows import cleanly, the
 * duplicate check passes — there is nothing to duplicate, since the account
 * has never seen these payments — and the totals quietly become wrong for both
 * accounts at once. By the time it is noticed, undoing it means finding
 * several dozen entries by hand.
 *
 * ## What gives it away
 *
 * Payees are strikingly account-specific in practice. The account the season
 * ticket comes out of is not the one the nursery fees come out of, and neither
 * is the card used for petrol. So a file whose payees are all strangers to the
 * chosen account, and all familiar on another one, is almost certainly pointed
 * at the wrong account.
 *
 * ## Why it only ever warns
 *
 * It can be legitimately wrong: a new account, a card just switched to, a
 * genuine first month. So this never blocks an import and never re-files
 * anything — it says which account the payees look like and leaves the choice
 * alone. A warning that is occasionally unnecessary costs a glance; refusing
 * an import that was correct costs the whole job.
 */
object AccountFitCheck {

    /**
     * What the payees suggest, when they suggest anything.
     *
     * [recognisedHere] and [recognisedThere] are counts of distinct payees, not
     * of rows, so one weekly shop cannot outvote everything else.
     */
    data class Verdict(
        val suggestedAccountId: Long,
        val recognisedThere: Int,
        val recognisedHere: Int,
        val payeesConsidered: Int,
    )

    /**
     * Whether [candidates] look like they belong to an account other than
     * [chosenAccountId].
     *
     * Returns null whenever the evidence is thin — which is most of the time,
     * and deliberately so.
     */
    fun check(
        candidates: List<ImportCandidate>,
        chosenAccountId: Long,
        payees: List<AccountPayee>,
    ): Verdict? {
        val statementPayees = candidates
            .filter { it.isImportable && it.target == ImportTarget.TRANSACTION }
            .map { TransactionFingerprint.normaliseDescription(it.name) }
            .filter { it.isNotBlank() }
            .toSet()
        if (statementPayees.size < MIN_PAYEES) return null

        // Distinct payees per account, restricted to the ones this file names.
        val known = mutableMapOf<Long, MutableSet<String>>()
        for (payee in payees) {
            val normalised = TransactionFingerprint.normaliseDescription(payee.description)
            if (normalised in statementPayees) {
                known.getOrPut(payee.accountId) { mutableSetOf() }.add(normalised)
            }
        }

        val here = known[chosenAccountId]?.size ?: 0
        val (thereId, there) = known
            .filterKeys { it != chosenAccountId }
            .maxByOrNull { it.value.size }
            ?.let { it.key to it.value.size }
            ?: return null

        // Enough of a showing elsewhere to mean something, clearly better than
        // here in both absolute and relative terms. All three matter: two
        // payees is coincidence, and "three against two" is not a finding.
        if (there < MIN_EVIDENCE) return null
        if (there - here < MIN_MARGIN) return null
        if (there < here * 2) return null

        return Verdict(
            suggestedAccountId = thereId,
            recognisedThere = there,
            recognisedHere = here,
            payeesConsidered = statementPayees.size,
        )
    }

    /** Below this the file is too short to say anything about. */
    private const val MIN_PAYEES = 5

    /** Distinct payees the other account must recognise before it is raised. */
    private const val MIN_EVIDENCE = 3

    /** And by this much more than the chosen one. */
    private const val MIN_MARGIN = 3
}
