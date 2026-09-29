package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate

/**
 * Proof that the whole statement was read — or where it was not.
 *
 * A statement prints a running balance, and between any two printed balances
 * the rows in between must add up to exactly the difference. If a row was
 * missed, or read the wrong way round, they do not, and the gap says where
 * and by how much. Every row is counted, including the ones already in the
 * app: they are still part of the statement.
 *
 * It also gives the balance at the end of the statement, which is what the
 * account's balance is set to once the statement has been imported.
 */
data class StatementCheck(
    val rows: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val moneyInMinor: Long,
    val moneyOutMinor: Long,
    /** The balance before the first row, worked back from the first printed balance. */
    val startBalanceMinor: Long?,
    /** The balance after the last row. */
    val endBalanceMinor: Long?,
    /** How many rows carried a printed balance to check against. */
    val balancesSeen: Int,
    val gaps: List<Gap>,
) {
    /** Somewhere the rows do not add up to the change in the printed balance. */
    data class Gap(
        val after: LocalDate?,
        val before: LocalDate?,
        val unaccountedMinor: Long,
        /**
         * Rows in the gap that would close it exactly if they were the other
         * way round — money in read as money out, or the reverse.
         */
        val suspectIds: List<String> = emptyList(),
    )

    /** True when there were balances to check against and every row accounted for them. */
    val isProvenComplete: Boolean get() = balancesSeen >= 2 && gaps.isEmpty()

    val canBeChecked: Boolean get() = balancesSeen >= 2

    companion object {
        /**
         * Checks [candidates] in statement order. Newest-first files are
         * turned round first, so the running balance reads forwards.
         */
        fun of(candidates: List<ImportCandidate>): StatementCheck {
            val rows = candidates.filter {
                it.isImportable && it.target == ImportTarget.TRANSACTION
            }
            val dated = rows.map { it to it.dateIso?.let(DateUtils::parseIsoOrNull) }
            val firstListed = dated.firstOrNull { it.second != null }?.second
            val lastListed = dated.lastOrNull { it.second != null }?.second
            val ordered = if (firstListed != null && lastListed != null && firstListed > lastListed) {
                rows.reversed()
            } else {
                rows
            }
            fun signed(row: ImportCandidate) =
                if (row.transactionType == TransactionType.INCOME) row.amountMinor else -row.amountMinor

            val gaps = mutableListOf<Gap>()
            var seen = 0
            var lastBalance: Long? = null
            var lastBalanceDate: LocalDate? = null
            var since = 0L
            val window = mutableListOf<ImportCandidate>()
            var start: Long? = null
            ordered.forEach { row ->
                val date = row.dateIso?.let(DateUtils::parseIsoOrNull)
                since += signed(row)
                window += row
                val balance = row.balanceMinor
                if (balance != null) {
                    seen++
                    if (lastBalance == null) {
                        // Everything up to here, including this row, led to it.
                        start = balance - since
                    } else {
                        val unaccounted = balance - lastBalance!! - since
                        if (unaccounted != 0L) {
                            // Swapping a row moves the sum by twice its amount.
                            val suspects = window.filter { candidate ->
                                val isIn = candidate.transactionType == TransactionType.INCOME
                                (!isIn && unaccounted == 2 * candidate.amountMinor) ||
                                    (isIn && unaccounted == -2 * candidate.amountMinor)
                            }.map { it.id }
                            gaps += Gap(lastBalanceDate, date, unaccounted, suspects)
                        }
                    }
                    lastBalance = balance
                    lastBalanceDate = date
                    since = 0L
                    window.clear()
                }
            }
            // Rows after the last printed balance still move the end balance.
            val end = lastBalance?.let { it + since }
            val dates = ordered.mapNotNull { it.dateIso?.let(DateUtils::parseIsoOrNull) }
            return StatementCheck(
                rows = ordered.size,
                firstDate = dates.minOrNull(),
                lastDate = dates.maxOrNull(),
                moneyInMinor = ordered.filter { it.transactionType == TransactionType.INCOME }
                    .sumOf { it.amountMinor },
                moneyOutMinor = ordered.filter { it.transactionType != TransactionType.INCOME }
                    .sumOf { it.amountMinor },
                startBalanceMinor = start,
                endBalanceMinor = end,
                balancesSeen = seen,
                gaps = gaps,
            )
        }
    }
}
