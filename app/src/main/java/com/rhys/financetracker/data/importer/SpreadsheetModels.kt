package com.rhys.financetracker.data.importer

/**
 * A spreadsheet as the importer sees it: a grid of trimmed strings, plus the
 * name of the sheet it came from.
 *
 * Everything is text at this stage.  Interpreting a cell as a date, a person or
 * an amount is the mapping step's job, and keeping the two apart is what lets
 * the user correct a bad guess without re-reading the file.
 */
data class SheetData(
    val name: String,
    val rows: List<List<String>>,
) {
    val rowCount: Int get() = rows.size
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0

    fun cell(row: Int, column: Int): String =
        rows.getOrNull(row)?.getOrNull(column).orEmpty()

    /** Rows that contain at least one non-blank cell. */
    fun nonEmptyRows(): List<Int> =
        rows.indices.filter { index -> rows[index].any { it.isNotBlank() } }
}

/** A whole workbook. */
data class WorkbookData(
    val fileName: String,
    val sheets: List<SheetData>,
) {
    val isEmpty: Boolean get() = sheets.all { it.rows.isEmpty() }
}

/**
 * What a column in the spreadsheet means.
 *
 * `IGNORE` is a first-class choice: real spreadsheets are full of spacer
 * columns and working notes that should not become data.
 */
enum class ColumnRole(val displayName: String, val hint: String) {
    IGNORE("Ignore", "Skip this column"),
    NAME("Name", "What the payment is called"),
    AMOUNT("Amount", "The value, e.g. 24.99"),
    DATE("Date", "When it happened or is due"),
    CATEGORY("Category", "Food, Fuel, Insurance…"),
    PERSON("Person", "Who it belongs to"),
    ACCOUNT("Account", "Which account it comes from"),
    NOTES("Notes", "Any extra detail"),
    TYPE("Type", "Income or expense"),
    FREQUENCY("Frequency", "Monthly, weekly, yearly…"),
    DAY_OF_MONTH("Day of month", "The day a bill is due"),
}

/**
 * How one block of the spreadsheet maps onto the app's records.
 *
 * A single workbook can need more than one of these — the example sheet has an
 * income block, a savings block and an outgoings block, each laid out
 * differently — so the import screen collects a list of them.
 */
data class ImportMapping(
    val sheetName: String,
    /** Row containing the column headings; -1 when there is no header row. */
    val headerRow: Int,
    val firstDataRow: Int,
    val lastDataRow: Int,
    /** Column index to role. Columns not listed are ignored. */
    val columnRoles: Map<Int, ColumnRole>,
    /** What kind of record the rows become. */
    val target: ImportTarget,
    /**
     * Used when a block has no person column because the whole block belongs to
     * one person — the layout the example spreadsheet uses.
     */
    val defaultPersonName: String? = null,
    val defaultAccountName: String? = null,
    val defaultCategoryName: String? = null,
    /** Applied to every row when there is no frequency column. */
    val defaultFrequency: String = "MONTHLY",
    /** Skip rows whose amount is zero — spreadsheets are full of empty placeholders. */
    val skipZeroAmounts: Boolean = true,
)

/** What the mapped rows should become. */
enum class ImportTarget(val displayName: String, val description: String) {
    RECURRING_EXPENSE(
        "Regular bills",
        "Each row becomes a repeating payment that the app creates for you",
    ),
    RECURRING_INCOME(
        "Regular income",
        "Each row becomes repeating income, such as a salary",
    ),
    ACCOUNT_BALANCE(
        "Account balances",
        "Each row becomes an account with a starting balance",
    ),
    TRANSACTION(
        "One-off transactions",
        "Each row becomes a single entry in the ledger",
    ),
}

/** One row of the spreadsheet, interpreted but not yet saved. */
data class ImportCandidate(
    val sourceRow: Int,
    /**
     * The amount column this candidate was read from.  A sheet with a column of
     * figures per person produces one candidate per person from the same row,
     * so the row number alone does not identify it.
     */
    val sourceColumn: Int = -1,
    val name: String,
    val amountMinor: Long,
    val target: ImportTarget,
    val personName: String?,
    val accountName: String?,
    val categoryName: String?,
    val notes: String?,
    val dayOfMonth: Int?,
    val dateIso: String?,
    val frequencyName: String,
    /** Set when the row cannot be imported; it is shown but not selected. */
    val problem: String? = null,
    val isSelected: Boolean = true,
) {
    val isImportable: Boolean get() = problem == null

    /** Stable identity for list keys and for toggling one candidate. */
    val id: String get() = "$sourceRow:$sourceColumn"
}

/** The outcome of writing the selected candidates to the database. */
data class ImportOutcome(
    val peopleCreated: Int = 0,
    val accountsCreated: Int = 0,
    val categoriesCreated: Int = 0,
    val recurringCreated: Int = 0,
    val transactionsCreated: Int = 0,
    val skipped: Int = 0,
    val problems: List<String> = emptyList(),
) {
    val totalCreated: Int
        get() = peopleCreated + accountsCreated + categoriesCreated +
            recurringCreated + transactionsCreated

    fun summary(): String = buildString {
        append("$totalCreated ")
        append(if (totalCreated == 1) "record" else "records")
        append(" added")
        if (skipped > 0) append(", $skipped skipped")
    }
}
