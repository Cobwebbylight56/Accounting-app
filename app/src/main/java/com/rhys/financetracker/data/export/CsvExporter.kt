package com.rhys.financetracker.data.export

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.report.Report
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes CSV.
 *
 * Two shapes are produced: a report (sections and rows, matching what is on
 * screen) and a raw transaction ledger, which is what you want when the file is
 * going back into a spreadsheet for further work.
 */
@Singleton
class CsvExporter @Inject constructor() {

    fun exportReport(report: Report): String = buildString {
        appendLine(escape(report.title))
        appendLine(escape("Period: ${report.period.label}"))
        appendLine(escape("Scope: ${report.scope.label}"))
        appendLine(escape("Produced: ${DateUtils.format(report.generatedOn)}"))
        appendLine()

        report.sections.forEach { section ->
            appendLine(escape(section.title))
            appendLine("Item,Amount,Detail")
            section.rows.forEach { row ->
                appendLine(
                    listOf(row.label, row.value, row.secondary.orEmpty())
                        .joinToString(",") { escape(it) },
                )
            }
            section.note?.let { appendLine(escape(it)) }
            appendLine()
        }
    }

    /**
     * The full ledger.  Amounts are written unformatted (`24.99`, not `£24.99`)
     * so that a spreadsheet reads them as numbers rather than text.
     */
    fun exportTransactions(transactions: List<TransactionWithDetails>): String = buildString {
        appendLine(
            listOf(
                "Date", "Description", "Type", "Amount", "Account", "To account",
                "Category", "Person", "Notes", "Tags", "Confirmed", "Cleared",
            ).joinToString(","),
        )
        transactions.forEach { item ->
            val entry = item.transaction
            appendLine(
                listOf(
                    entry.date.toString(),
                    entry.description,
                    entry.type.displayName,
                    Money.formatPlain(entry.amountMinor),
                    item.accountName.orEmpty(),
                    item.transferAccountName.orEmpty(),
                    item.categoryName.orEmpty(),
                    item.personName.orEmpty(),
                    entry.notes.orEmpty(),
                    entry.tags.orEmpty(),
                    if (entry.isConfirmed) "Yes" else "No",
                    if (entry.isCleared) "Yes" else "No",
                ).joinToString(",") { escape(it) },
            )
        }
    }

    /**
     * Quotes a field when it contains a comma, a quote or a line break, and
     * doubles any quote inside it — the CSV convention every spreadsheet reads.
     */
    private fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
