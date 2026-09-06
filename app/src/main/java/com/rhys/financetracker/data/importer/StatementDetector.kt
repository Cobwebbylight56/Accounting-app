package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.money.Money

/**
 * Recognises a downloaded bank statement and works out how to read it.
 *
 * This is the route that needs no bank connection at all: export a statement
 * from online banking as CSV — every UK bank offers it, usually going back
 * several years — and import the file. Old statements can be imported in any
 * order, which is how a spending history gets built up from scratch.
 *
 * The awkward parts of a real statement file, and what is done about them:
 *
 *  * **Preamble.** Banks put the account name, sort code and a blank line or
 *    two above the headings, so the header row is searched for rather than
 *    assumed to be first.
 *  * **Two money columns or one.** Some banks give "Paid out" and "Paid in";
 *    others give a single signed "Amount". Both are handled, and which one it
 *    is decides whether a minus sign means "money out" or means "something is
 *    wrong with this row".
 *  * **The running balance.** It is a column of entirely plausible amounts
 *    sitting next to the real one. Importing it would add the account's whole
 *    history again, so it is identified and deliberately left alone.
 */
object StatementDetector {

    /** How far down to look for the headings before giving up. */
    private const val MAX_PREAMBLE_ROWS = 30

    /**
     * A statement needs at least this many data rows to be worth offering.
     *
     * One, because a savings account really can have a single movement in a
     * month. The headings have already had to name a date beside a money
     * column to get this far, which is the part that says "statement"; the
     * number of rows underneath says nothing except how busy the account was.
     */
    private const val MIN_DATA_ROWS = 1

    /**
     * The mapping for [sheet] when it looks like a statement, or null when it
     * does not — in which case the caller falls back to the household layout
     * detector or to manual mapping.
     */
    fun detect(sheet: SheetData, accountName: String? = null): ImportMapping? {
        val headerRow = findHeaderRow(sheet) ?: return null

        val sampleRows = (headerRow + 1 until sheet.rowCount)
            .filter { row -> sheet.rows.getOrNull(row)?.any { it.isNotBlank() } == true }
            .take(20)
        if (sampleRows.size < MIN_DATA_ROWS) return null

        val roles = ColumnDetector.detect(sheet, headerRow, sampleRows).toMutableMap()

        // A statement is only readable if we know when and how much.
        val hasDate = roles.containsValue(ColumnRole.DATE)
        val hasMoneyColumns = roles.containsValue(ColumnRole.MONEY_IN) ||
            roles.containsValue(ColumnRole.MONEY_OUT)
        val hasAmount = roles.containsValue(ColumnRole.AMOUNT)
        if (!hasDate || !(hasMoneyColumns || hasAmount)) return null

        // With paid-in and paid-out columns present, a lone "Amount" column is
        // something else entirely and is better ignored than added twice.
        if (hasMoneyColumns && hasAmount) {
            roles.entries.filter { it.value == ColumnRole.AMOUNT }
                .forEach { roles[it.key] = ColumnRole.IGNORE }
        }

        ensureDescriptionColumn(sheet, headerRow, sampleRows, roles)

        val lastDataRow = sheet.rows.indices.lastOrNull { row ->
            row > headerRow && sheet.rows[row].any { it.isNotBlank() }
        } ?: return null

        return ImportMapping(
            sheetName = sheet.name,
            headerRow = headerRow,
            firstDataRow = headerRow + 1,
            lastDataRow = lastDataRow,
            columnRoles = roles,
            target = ImportTarget.TRANSACTION,
            defaultAccountName = accountName,
            // Only when the direction is carried by the sign, which is exactly
            // the single-amount-column case.
            amountSignIsDirection = !hasMoneyColumns,
        )
    }

    /** Whether [sheet] is worth offering as a statement at all. */
    fun looksLikeStatement(sheet: SheetData): Boolean = detect(sheet) != null

    /**
     * The headings row: the first row naming a date alongside a money column.
     *
     * Matching on the headings rather than the shape of the data matters here,
     * because the preamble of a bank export often contains dates of its own.
     */
    private fun findHeaderRow(sheet: SheetData): Int? {
        val limit = minOf(MAX_PREAMBLE_ROWS, sheet.rowCount)
        for (row in 0 until limit) {
            val cells = sheet.rows.getOrNull(row).orEmpty().map { it.trim().lowercase() }
            if (cells.count { it.isNotBlank() } < 2) continue

            val namesADate = cells.any { cell -> DATE_HEADINGS.any { cell.contains(it) } }
            val namesMoney = cells.any { cell -> MONEY_HEADINGS.any { cell.contains(it) } }
            // Headings are words, not figures; a data row that happens to use
            // these words would still be full of parseable amounts.
            val mostlyText = cells.filter { it.isNotBlank() }
                .count { Money.parseOrNull(it) == null } >= cells.count { it.isNotBlank() } - 1

            if (namesADate && namesMoney && mostlyText) return row
        }
        return null
    }

    /**
     * Statements always describe the payee somewhere, but the heading varies
     * ("Description", "Reference", "Details", "Transaction", "Narrative") and
     * some banks split it over two columns. If the headings gave us nothing,
     * the widest column of plain text is the description.
     */
    private fun ensureDescriptionColumn(
        sheet: SheetData,
        headerRow: Int,
        sampleRows: List<Int>,
        roles: MutableMap<Int, ColumnRole>,
    ) {
        if (roles.containsValue(ColumnRole.NAME)) return

        val candidate = (0 until sheet.columnCount)
            .filter { roles[it] == ColumnRole.IGNORE || roles[it] == null }
            .maxByOrNull { column ->
                sampleRows.sumOf { row ->
                    val cell = sheet.cell(row, column)
                    if (Money.parseOrNull(cell) == null && !ColumnDetector.looksLikeDate(cell)) {
                        cell.length
                    } else {
                        0
                    }
                }
            } ?: return

        val hasText = sampleRows.any { sheet.cell(it, candidate).isNotBlank() }
        if (hasText) roles[candidate] = ColumnRole.NAME
    }

    private val DATE_HEADINGS = setOf("date", "posted", "transaction date", "when")

    private val MONEY_HEADINGS = setOf(
        "amount", "paid out", "paid in", "money out", "money in", "debit", "credit",
        "withdrawn", "deposited", "value", "balance", "payments", "receipts",
    )
}
