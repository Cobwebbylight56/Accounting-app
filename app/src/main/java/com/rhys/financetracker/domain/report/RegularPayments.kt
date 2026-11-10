package com.rhys.financetracker.domain.report

import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import java.time.YearMonth

/**
 * Which payments are a regular bill — the energy, the TV licence — rather
 * than something bought once. "One-off" is only offered for the second: it
 * was beside every direct debit, which made it look as if the app thought
 * the bills were one-offs.
 */
object RegularPayments {

    /**
     * The ids of [rows] that are regular: set up as a regular payment, paid
     * by direct debit or standing order, or paid to the same payee in more
     * than one month among [rows].
     */
    fun among(rows: List<TransactionWithDetails>): Set<Long> {
        val repeated = rows.groupBy { keyOf(it) }
            .filterValues { list -> list.map { YearMonth.from(it.transaction.date) }.distinct().size >= 2 }
            .keys
        return rows.filter { row ->
            row.transaction.recurringRuleId != null || row.regularKind != null ||
                REGULAR_WORDS.containsMatchIn(" " + TransactionFingerprint.normaliseDescription(row.transaction.description) + " ") ||
                keyOf(row) in repeated
        }.map { it.transaction.id }.toSet()
    }

    private fun keyOf(row: TransactionWithDetails): String {
        val description = row.transaction.description
        return PayeeNames.of(description).lowercase().ifBlank { TransactionFingerprint.normaliseDescription(description) }
    }

    /** How a statement says a payment is set up to repeat. */
    private val REGULAR_WORDS = Regex("""\s(direct debit|standing order|dd|so|ddr)\s""")
}
