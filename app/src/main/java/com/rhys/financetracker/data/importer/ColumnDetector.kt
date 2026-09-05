package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils

/**
 * Guesses what each column of a spreadsheet holds, so the user starts from a
 * mapping that is mostly right and only has to correct it.
 *
 * Two signals are combined:
 *  * the header text, matched against the words people actually use; and
 *  * the shape of the data underneath — a column of parseable amounts is an
 *    amount column even when its heading is blank, which is common in
 *    hand-built household spreadsheets.
 */
object ColumnDetector {

    private val NAME_WORDS = setOf(
        "name", "item", "description", "detail", "details", "payment", "bill",
        "expense", "outgoing", "outgoings", "income", "source", "what", "label",
    )
    private val AMOUNT_WORDS = setOf(
        "amount", "cost", "value", "price", "total", "sum", "£", "gbp", "monthly",
        "per month", "pcm", "spend", "paid", "debit", "credit",
    )
    private val DATE_WORDS = setOf("date", "due", "when", "paid on", "day")
    private val CATEGORY_WORDS = setOf("category", "type", "group", "kind", "class")
    private val PERSON_WORDS = setOf("person", "who", "owner", "name of person", "member")
    private val ACCOUNT_WORDS = setOf("account", "bank", "card", "from", "paid from")
    private val NOTES_WORDS = setOf("note", "notes", "comment", "comments", "memo", "info")
    private val FREQUENCY_WORDS = setOf("frequency", "how often", "repeat", "recurrence", "every")

    // Bank statement headings. UK banks all word these slightly differently:
    // "Paid out"/"Paid in" (Lloyds, Halifax), "Money out"/"Money in" (Nationwide,
    // Santander), "Debit"/"Credit" (Barclays, HSBC), "Withdrawn"/"Deposited".
    private val MONEY_OUT_WORDS = setOf(
        "paid out", "money out", "withdrawn", "withdrawal", "withdrawals",
        "debit", "debits", "out (", "spent", "payments out",
    )
    private val MONEY_IN_WORDS = setOf(
        "paid in", "money in", "deposited", "deposit", "credit", "credits",
        "in (", "received", "payments in",
    )
    // Read so it can be excluded: a running balance is a column of perfectly
    // good-looking amounts, and importing it would add the account's whole
    // history over again as transactions.
    private val BALANCE_WORDS = setOf("balance", "running total", "cleared bal")

    /**
     * @param headerRow the row believed to hold headings, or null when there is none.
     * @param sampleRows data rows used to sniff the content of each column.
     */
    fun detect(
        sheet: SheetData,
        headerRow: Int?,
        sampleRows: List<Int>,
    ): Map<Int, ColumnRole> {
        val roles = mutableMapOf<Int, ColumnRole>()
        val used = mutableSetOf<ColumnRole>()

        for (column in 0 until sheet.columnCount) {
            val header = headerRow?.let { sheet.cell(it, column) }?.trim()?.lowercase().orEmpty()
            val samples = sampleRows.map { sheet.cell(it, column) }.filter { it.isNotBlank() }

            val role = roleFromHeader(header)
                ?: roleFromContent(samples)
                ?: ColumnRole.IGNORE

            // Only one column can play each single-valued role; a second
            // candidate is more likely to be a working column.
            roles[column] = if (role != ColumnRole.IGNORE && role in used) {
                if (role == ColumnRole.AMOUNT) ColumnRole.AMOUNT else ColumnRole.IGNORE
            } else {
                role.also { if (it != ColumnRole.IGNORE) used += it }
            }
        }
        return roles
    }

    /** Finds the most likely header row: the first row with several text cells. */
    fun detectHeaderRow(sheet: SheetData): Int? =
        sheet.rows.indices.firstOrNull { row ->
            val cells = sheet.rows[row].filter { it.isNotBlank() }
            cells.size >= 2 && cells.count { Money.parseOrNull(it) == null } >= cells.size - 1
        }

    private fun roleFromHeader(header: String): ColumnRole? {
        if (header.isBlank()) return null
        fun matches(words: Set<String>) = words.any { header.contains(it) }
        return when {
            matches(FREQUENCY_WORDS) -> ColumnRole.FREQUENCY
            matches(DATE_WORDS) -> ColumnRole.DATE
            // Before AMOUNT, all three of them: "Balance", "Paid out" and
            // "Debit" would otherwise be swallowed by the amount words.
            matches(BALANCE_WORDS) -> ColumnRole.BALANCE
            matches(MONEY_OUT_WORDS) -> ColumnRole.MONEY_OUT
            matches(MONEY_IN_WORDS) -> ColumnRole.MONEY_IN
            matches(AMOUNT_WORDS) -> ColumnRole.AMOUNT
            matches(PERSON_WORDS) -> ColumnRole.PERSON
            matches(ACCOUNT_WORDS) -> ColumnRole.ACCOUNT
            matches(CATEGORY_WORDS) -> ColumnRole.CATEGORY
            matches(NOTES_WORDS) -> ColumnRole.NOTES
            matches(NAME_WORDS) -> ColumnRole.NAME
            else -> null
        }
    }

    private fun roleFromContent(samples: List<String>): ColumnRole? {
        if (samples.isEmpty()) return null
        val amountLike = samples.count { Money.parseOrNull(it) != null }
        val dateLike = samples.count { looksLikeDate(it) }
        val textLike = samples.count { Money.parseOrNull(it) == null && !looksLikeDate(it) }

        return when {
            dateLike >= samples.size * 0.6 -> ColumnRole.DATE
            amountLike >= samples.size * 0.6 -> ColumnRole.AMOUNT
            textLike >= samples.size * 0.6 -> ColumnRole.NAME
            else -> null
        }
    }

    internal fun looksLikeDate(value: String): Boolean {
        if (DateUtils.parseIsoOrNull(value) != null) return true
        // dd/mm/yyyy and dd-mm-yy, the formats a UK spreadsheet is likely to hold.
        val parts = value.split('/', '-', '.')
        if (parts.size != 3) return false
        return parts.all { it.toIntOrNull() != null }
    }
}
