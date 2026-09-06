package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.money.Money

/**
 * Recognises the shape of a hand-built household budget sheet, so the whole
 * thing can be imported in one go.
 *
 * The layout this handles is the one people actually build, and the one most
 * importers cannot read:
 *
 * ```
 *        C          D        E         F        G       H        I     J
 *  2  income  befor tax  27,455.76          16,692.48
 *  3                       Rhys               Hannah             both
 *  4  income               1862.23             1447           3309.23
 *  ...
 * 18  OUTGOINGS
 * 19  car                    60.75            80.59            141.34
 * 20  road tax               37.62            24.06             61.68
 * ```
 *
 * There is one **column of figures per person**, not one row per record, and a
 * derived "both" column that must be ignored or every amount is counted twice.
 * Blocks are separated by blank rows and introduced by a heading such as
 * "OUTGOINGS" or "savings &".
 *
 * The detector finds the person columns and the blocks; [mappingsFor] then
 * produces one [ImportMapping] per person per block, which the normal import
 * pipeline turns into candidates. Nothing here writes anything.
 */
object HouseholdLayoutDetector {

    /** Column headings that hold a derived total rather than one person's money. */
    private val TOTAL_HEADINGS = setOf(
        "both", "total", "totals", "combined", "joint", "sum", "all", "overall", "together",
    )

    /** Words that introduce a block of outgoings. */
    private val EXPENSE_HEADINGS = setOf(
        "outgoing", "outgoings", "expense", "expenses", "bills", "spending", "out", "payments",
    )

    /** Words that introduce a block of income. */
    private val INCOME_HEADINGS = setOf("income", "wages", "salary", "earnings", "in", "pay")

    /**
     * Column headings from an ordinary one-record-per-row export.  A sheet
     * headed "Date | Description | Amount" is not a household layout, and
     * treating "Description" as a person would produce nonsense.
     */
    private val COLUMN_HEADINGS = setOf(
        "date", "description", "amount", "value", "cost", "price", "category",
        "notes", "note", "memo", "account", "person", "who", "type", "frequency",
        "name", "item", "day", "month", "year", "reference", "payee", "details",
        // A bank statement's own headings. These sit above columns of figures,
        // which is precisely the shape this detector looks for in a person, so
        // without them a statement imports as people called "Money out" and
        // "Money in" — and every import adds two more.
        "money in", "money out", "paid in", "paid out", "money-in", "money-out",
        "payments", "receipts",
        "debit", "credit", "debits", "credits", "withdrawn", "withdrawal",
        "withdrawals", "deposit", "deposits",
    )

    /** Words that introduce a block of balances. */
    private val BALANCE_HEADINGS = setOf(
        "savings", "saving", "banks", "bank", "balances", "balance", "accounts", "account",
    )

    /** Row labels that are totals of the rows above and must not become records. */
    private val TOTAL_ROW_LABELS = setOf(
        "spent", "total", "totals", "left over", "leftover", "remaining", "balance",
        "all savings", "sum", "subtotal", "difference", "net",
    )

    /**
     * A block may be a single row: the income block in the example sheet is one
     * row holding both salaries, and dropping it would silently lose the most
     * important figures on the sheet.  A wrong guess is visible in the preview;
     * a missing one is not.
     */
    private const val MIN_ROWS_PER_BLOCK = 1

    /** A name column needs a few rows before its shape means anything. */
    private const val MIN_ROWS_FOR_NAME_COLUMN = 2

    /** How much of a column must parse as an amount before it counts as a figures column. */
    private const val AMOUNT_COLUMN_THRESHOLD = 0.6

    fun detect(sheet: SheetData): DetectedLayout? {
        val headerRow = findPersonHeaderRow(sheet) ?: return null
        val people = personColumns(sheet, headerRow)
        if (people.isEmpty()) return null

        val nameColumn = findNameColumn(sheet, headerRow, people) ?: return null
        val blocks = findBlocks(sheet, headerRow, nameColumn, people)
        if (blocks.isEmpty()) return null

        return DetectedLayout(
            sheetName = sheet.name,
            headerRow = headerRow,
            nameColumn = nameColumn,
            people = people,
            blocks = blocks,
        )
    }

    /**
     * The row holding the people's names.
     *
     * It is the row with two or more short text cells that are not amounts and
     * not one of the words a budget sheet uses for its own structure — which in
     * practice is the row reading "Rhys | Hannah | both".
     */
    internal fun findPersonHeaderRow(sheet: SheetData): Int? {
        val limit = minOf(sheet.rowCount, 25)
        var best: Pair<Int, Int>? = null
        for (row in 0 until limit) {
            val cells = sheet.rows.getOrNull(row).orEmpty()
            val names = cells.count { looksLikePersonName(it) }
            // A header row is mostly names; a data row has figures on it too.
            val amounts = cells.count { Money.parseOrNull(it) != null }
            if (names >= 2 && amounts == 0) {
                if (best == null || names > best.second) best = row to names
            }
        }
        return best?.first
    }

    /**
     * A person's name: a short piece of text that is not a number, not a date,
     * and not one of the structural words the sheet uses for itself.
     */
    internal fun looksLikePersonName(cell: String): Boolean {
        val text = cell.trim()
        if (text.length !in 2..24) return false
        if (Money.parseOrNull(text) != null) return false
        if (ColumnDetector.looksLikeDate(text)) return false
        if (!text.first().isLetter()) return false
        val lower = text.lowercase()
        if (lower in TOTAL_HEADINGS || lower in COLUMN_HEADINGS) return false
        if (lower in EXPENSE_HEADINGS || lower in INCOME_HEADINGS || lower in BALANCE_HEADINGS) {
            return false
        }
        // A name is one or two words; "befor tax" and "monthly outgoings" are not.
        return text.split(' ').size <= 2
    }

    /**
     * The columns that hold each person's figures.
     *
     * A heading in the "both"/"total" family is dropped: it is arithmetic the
     * app does for itself, and importing it would double every amount.
     */
    internal fun personColumns(sheet: SheetData, headerRow: Int): List<PersonColumn> {
        val header = sheet.rows.getOrNull(headerRow).orEmpty()
        return header.indices.mapNotNull { column ->
            val name = header[column].trim()
            if (!looksLikePersonName(name)) return@mapNotNull null
            if (name.lowercase() in TOTAL_HEADINGS) return@mapNotNull null
            // The figures may sit in the heading's own column or the one beside
            // it, depending on how the sheet was laid out.
            val amountColumn = listOf(column, column + 1)
                .firstOrNull { isAmountColumn(sheet, it, headerRow) }
                ?: return@mapNotNull null
            PersonColumn(name = name, amountColumn = amountColumn)
        }.distinctBy { it.amountColumn }
    }

    /** True when most of the non-blank cells below [headerRow] parse as amounts. */
    internal fun isAmountColumn(sheet: SheetData, column: Int, headerRow: Int): Boolean {
        if (column >= sheet.columnCount) return false
        val cells = ((headerRow + 1) until sheet.rowCount)
            .map { sheet.cell(it, column) }
            .filter { it.isNotBlank() }
        if (cells.size < 3) return false
        val amounts = cells.count {
            Money.parseOrNull(it) != null && !ColumnDetector.looksLikeDate(it)
        }
        return amounts.toDouble() / cells.size >= AMOUNT_COLUMN_THRESHOLD
    }

    /**
     * The column holding what each row is called — the leftmost column that is
     * mostly text and sits to the left of the first person's figures.
     */
    internal fun findNameColumn(
        sheet: SheetData,
        headerRow: Int,
        people: List<PersonColumn>,
    ): Int? {
        val firstAmount = people.minOfOrNull { it.amountColumn } ?: return null
        for (column in 0 until firstAmount) {
            val cells = ((headerRow + 1) until sheet.rowCount)
                .map { sheet.cell(it, column) }
                .filter { it.isNotBlank() }
            if (cells.size < MIN_ROWS_FOR_NAME_COLUMN) continue
            val text = cells.count { Money.parseOrNull(it) == null }
            if (text.toDouble() / cells.size >= 0.8) return column
        }
        return null
    }

    /**
     * Splits the sheet into blocks of consecutive rows, and works out what each
     * one holds from its heading and its content.
     */
    internal fun findBlocks(
        sheet: SheetData,
        headerRow: Int,
        nameColumn: Int,
        people: List<PersonColumn>,
    ): List<DetectedBlock> {
        val blocks = mutableListOf<DetectedBlock>()
        var blockStart: Int? = null
        var heading: String? = null
        var pendingHeading: String? = null

        fun close(endRow: Int) {
            val start = blockStart ?: return
            if (endRow - start + 1 >= MIN_ROWS_PER_BLOCK) {
                blocks += buildBlock(sheet, start, endRow, nameColumn, people, heading)
            }
            blockStart = null
            heading = null
        }

        for (row in (headerRow + 1) until sheet.rowCount) {
            val label = sheet.cell(row, nameColumn).trim()
            val hasFigures = people.any { sheet.cell(row, it.amountColumn).isNotBlank() }

            when {
                // A blank row ends the current block.
                label.isEmpty() && !hasFigures -> {
                    close(row - 1)
                    pendingHeading = null
                }
                // A label with no figures beside it is a heading for what follows.
                label.isNotEmpty() && !hasFigures -> {
                    close(row - 1)
                    pendingHeading = label
                }
                else -> {
                    if (blockStart == null) {
                        blockStart = row
                        heading = pendingHeading
                        pendingHeading = null
                    }
                }
            }
        }
        close(sheet.rowCount - 1)

        return blocks.filter { it.rowCount >= MIN_ROWS_PER_BLOCK }
    }

    private fun buildBlock(
        sheet: SheetData,
        firstRow: Int,
        lastRow: Int,
        nameColumn: Int,
        people: List<PersonColumn>,
        heading: String?,
    ): DetectedBlock {
        // Trim any trailing total rows: "spent", "left over", "ALL SAVINGS".
        var end = lastRow
        while (end > firstRow && isTotalRow(sheet.cell(end, nameColumn))) end--

        val labels = (firstRow..end).map { sheet.cell(it, nameColumn).lowercase() }
        return DetectedBlock(
            heading = heading,
            target = classify(heading, labels),
            firstRow = firstRow,
            lastRow = end,
            nameColumn = nameColumn,
            people = people,
        )
    }

    internal fun isTotalRow(label: String): Boolean {
        val text = label.trim().lowercase()
        if (text.isEmpty()) return false
        return TOTAL_ROW_LABELS.any { text == it || text.startsWith("$it ") }
    }

    /**
     * What a block should become.
     *
     * The heading decides it when there is one.  Otherwise the row labels do:
     * a block full of account-like words is balances, and anything else in a
     * budget sheet is an outgoing, which is the safe default because it is by
     * far the most common and the preview makes a wrong guess obvious.
     */
    internal fun classify(heading: String?, labels: List<String>): ImportTarget {
        val words = heading?.lowercase()?.split(' ', '&', '/', ',')?.map { it.trim() }.orEmpty()
        when {
            words.any { it in EXPENSE_HEADINGS } -> return ImportTarget.RECURRING_EXPENSE
            words.any { it in BALANCE_HEADINGS } -> return ImportTarget.ACCOUNT_BALANCE
            words.any { it in INCOME_HEADINGS } -> return ImportTarget.RECURRING_INCOME
        }

        val accountish = labels.count { label ->
            BALANCE_HEADINGS.any { label.contains(it) } ||
                label.contains("cash") || label.contains("coin") || label.contains("saver")
        }
        if (labels.isNotEmpty() && accountish.toDouble() / labels.size >= 0.4) {
            return ImportTarget.ACCOUNT_BALANCE
        }
        val incomeish = labels.count { label ->
            label.contains("income") || label.contains("salary") || label.contains("wage") ||
                label.contains("benefit") || label.contains("pay")
        }
        if (labels.isNotEmpty() && incomeish.toDouble() / labels.size >= 0.5) {
            return ImportTarget.RECURRING_INCOME
        }
        // Anything else in a budget sheet is an outgoing, which is both the
        // commonest case and the one a wrong guess is easiest to spot in the
        // preview.
        return ImportTarget.RECURRING_EXPENSE
    }

    /**
     * One mapping per person per block — exactly what a user would have built by
     * hand, running the import once for each column of figures.
     */
    fun mappingsFor(layout: DetectedLayout): List<ImportMapping> =
        layout.blocks.flatMap { block ->
            block.people.map { person ->
                ImportMapping(
                    sheetName = layout.sheetName,
                    headerRow = layout.headerRow,
                    firstDataRow = block.firstRow,
                    lastDataRow = block.lastRow,
                    columnRoles = mapOf(
                        block.nameColumn to ColumnRole.NAME,
                        person.amountColumn to ColumnRole.AMOUNT,
                    ),
                    target = block.target,
                    defaultPersonName = person.name,
                    // Each person's money needs somewhere to live; naming the
                    // account after them means the importer creates one rather
                    // than dropping everything into a shared pot.
                    defaultAccountName = accountNameFor(person.name, block.target),
                )
            }
        }

    /**
     * Balances name their own account (the row label is the account), so only
     * the income and outgoing blocks need a default account.
     */
    internal fun accountNameFor(personName: String, target: ImportTarget): String? =
        if (target == ImportTarget.ACCOUNT_BALANCE) null else "$personName's account"
}

/** One person's column of figures. */
data class PersonColumn(
    val name: String,
    val amountColumn: Int,
)

/** A run of rows that share a meaning, such as the OUTGOINGS block. */
data class DetectedBlock(
    val heading: String?,
    val target: ImportTarget,
    val firstRow: Int,
    val lastRow: Int,
    val nameColumn: Int,
    val people: List<PersonColumn>,
) {
    val rowCount: Int get() = lastRow - firstRow + 1

    /** What to call this block on screen. */
    val label: String
        get() = heading?.replaceFirstChar { it.uppercase() } ?: target.displayName
}

/** Everything the detector worked out about a sheet. */
data class DetectedLayout(
    val sheetName: String,
    val headerRow: Int,
    val nameColumn: Int,
    val people: List<PersonColumn>,
    val blocks: List<DetectedBlock>,
) {
    val isUsable: Boolean get() = people.isNotEmpty() && blocks.isNotEmpty()

    /** A plain-words summary, shown before anything is imported. */
    fun describe(): String = buildString {
        append(people.joinToString(" and ") { it.name })
        append(if (people.size == 1) " found, across " else " found, across ")
        append(blocks.size)
        append(if (blocks.size == 1) " block" else " blocks")
        append(": ")
        append(blocks.joinToString(", ") { "${it.label.lowercase()} (${it.rowCount} rows)" })
    }
}
