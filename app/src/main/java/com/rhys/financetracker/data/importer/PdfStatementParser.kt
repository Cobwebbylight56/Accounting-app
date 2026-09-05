package com.rhys.financetracker.data.importer

import com.rhys.financetracker.core.money.Money
import java.time.LocalDate

/**
 * Turns the text of a PDF bank statement into the same table a CSV export
 * would have given.
 *
 * Producing a table rather than transactions is deliberate: everything
 * downstream — the layout detector, duplicate checking, categorisation — then
 * works on a PDF exactly as it does on a CSV, with no second implementation to
 * keep in step.
 *
 * ## The hard part
 *
 * A CSV says which column is which. A PDF gives a line of text:
 *
 * ```
 * 01 Mar 2026  TESCO STORES 3294        42.15    1,957.85
 * 02 Mar 2026  SALARY ACME LTD       1,862.23    3,820.08
 * ```
 *
 * Both rows end in two numbers and nothing marks which is the amount, which is
 * the balance, or whether money came in or went out. Position is no help: banks
 * put paid-out and paid-in in different orders, and some print only one column.
 *
 * ## Using the balance
 *
 * The running balance settles it. Between two rows the balance moves by exactly
 * the amount of the transaction, and the direction of the move says whether it
 * was money in or money out. So the last number on a line is taken as the
 * balance, and the amount is whichever remaining figure equals the change in
 * it.
 *
 * That is self-checking. When no figure on the line matches the balance change,
 * the reading is wrong — a wrapped description, a fee sharing a line, an
 * interest adjustment — and the row is flagged rather than guessed at, so it
 * reaches the review screen visibly wrong instead of silently wrong.
 */
object PdfStatementParser {

    /** The columns a parsed statement is presented in, matching a CSV export. */
    val HEADINGS = listOf("Date", "Description", "Money out", "Money in", "Balance")

    /**
     * One line of a statement, once read.
     *
     * [problem] is set when the line could not be reconciled against the
     * balance; the row is still returned so it can be shown and corrected.
     */
    data class Row(
        val date: LocalDate,
        val description: String,
        val moneyOutMinor: Long?,
        val moneyInMinor: Long?,
        val balanceMinor: Long?,
        val problem: String? = null,
    )

    /**
     * Reads [lines] of extracted PDF text into a sheet.
     *
     * Returns null when too few lines look like transactions, which is how a
     * PDF that is not a statement — a letter, a bill, a payslip — declines to
     * be imported rather than producing nonsense.
     */
    fun toSheet(lines: List<String>, sheetName: String): SheetData? {
        val rows = parse(lines)
        if (rows.size < MIN_ROWS) return null

        return SheetData(
            name = sheetName,
            rows = listOf(HEADINGS) + rows.map { row ->
                listOf(
                    row.date.toString(),
                    row.description,
                    row.moneyOutMinor?.let { Money.formatPlain(it) }.orEmpty(),
                    row.moneyInMinor?.let { Money.formatPlain(it) }.orEmpty(),
                    row.balanceMinor?.let { Money.formatPlain(it) }.orEmpty(),
                )
            },
        )
    }

    /** Every transaction line found in [lines], in the order they appear. */
    fun parse(lines: List<String>): List<Row> {
        val result = mutableListOf<Row>()
        var previousBalance: Long? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val date = leadingDate(line) ?: continue
            val amounts = trailingAmounts(line)
            if (amounts.isEmpty()) continue

            val description = describe(line, amounts)
            if (description.isBlank()) continue

            // A brought-forward line states the opening balance and is not a
            // transaction. It has to be recognised, because it is what every
            // following row is reconciled against — treating it as a payment
            // both invents an entry and leaves the next row unchecked.
            if (amounts.size == 1 && BALANCE_LINE.containsMatchIn(description)) {
                previousBalance = amounts.single()
                continue
            }

            val row = reconcile(date, description, amounts, previousBalance)
            result += row
            row.balanceMinor?.let { previousBalance = it }
        }
        return result
    }

    /**
     * Works out which figure is the amount and which way it went.
     *
     * With a previous balance to compare against this is arithmetic rather than
     * guesswork. Without one — the first row on the statement — the layout has
     * to be assumed, and that assumption is recorded so the row can be checked.
     */
    private fun reconcile(
        date: LocalDate,
        description: String,
        amounts: List<Long>,
        previousBalance: Long?,
    ): Row {
        // One figure and no balance column: the sign is all there is to go on.
        if (amounts.size == 1) {
            val only = amounts.single()
            return Row(
                date = date,
                description = description,
                moneyOutMinor = if (only < 0) -only else null,
                moneyInMinor = if (only >= 0) only else null,
                balanceMinor = null,
                problem = if (only < 0) {
                    null
                } else {
                    "Only one figure on this line, and nothing says which way it went"
                },
            )
        }

        val balance = amounts.last()
        val candidates = amounts.dropLast(1)

        if (previousBalance == null) {
            // The opening row. Take the larger-than-zero figure nearest the
            // balance as the amount and say plainly that it is unverified.
            val amount = candidates.lastOrNull { it != 0L } ?: return Row(
                date, description, null, null, balance,
                problem = "No amount found on this line",
            )
            return Row(
                date = date,
                description = description,
                moneyOutMinor = if (amount > 0) amount else null,
                moneyInMinor = if (amount < 0) -amount else null,
                balanceMinor = balance,
                problem = "First row, so the direction could not be checked against a balance",
            )
        }

        val change = balance - previousBalance
        val size = kotlin.math.abs(change)
        val matched = candidates.firstOrNull { kotlin.math.abs(it) == size }

        if (matched == null) {
            return Row(
                date, description, null, null, balance,
                problem = "The figures on this line do not match the change in balance",
            )
        }
        // The balance went down, so money left the account. No interpretation
        // of column order needed.
        return Row(
            date = date,
            description = description,
            moneyOutMinor = if (change < 0) size else null,
            moneyInMinor = if (change > 0) size else null,
            balanceMinor = balance,
            problem = if (change == 0L) "This line does not change the balance" else null,
        )
    }

    /**
     * The date a statement line starts with.
     *
     * UK statements write it as `01 Mar 2026`, `01/03/2026`, `1 March 2026` or
     * `01 Mar` with the year implied by the statement period; the last of those
     * is rejected here rather than guessed, since a wrong year files a
     * transaction in the wrong month.
     */
    internal fun leadingDate(line: String): LocalDate? {
        NUMERIC_DATE.find(line)?.let { match ->
            val (d, m, y) = match.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(year, m.toInt(), d.toInt()) }.getOrNull()
        }
        NAMED_DATE.find(line)?.let { match ->
            val (d, name, y) = match.destructured
            val month = MONTHS[name.lowercase().take(3)] ?: return null
            return runCatching { LocalDate.of(y.toInt(), month, d.toInt()) }.getOrNull()
        }
        return null
    }

    /**
     * The run of figures at the end of a line, in order.
     *
     * Only a trailing run counts. A description can hold digits of its own —
     * `TESCO STORES 3294`, `CARD 1234` — and those are part of the payee, not
     * money. Requiring pence, brackets or a sign keeps them out.
     */
    internal fun trailingAmounts(line: String): List<Long> {
        val tokens = line.trim().split(WHITESPACE)
        val amounts = mutableListOf<Long>()
        for (token in tokens.asReversed()) {
            if (!looksLikeMoney(token)) break
            val minor = Money.parseOrNull(token) ?: break
            amounts += if (token.startsWith("-") || token.endsWith("-") ||
                (token.startsWith("(") && token.endsWith(")"))
            ) {
                -kotlin.math.abs(minor)
            } else {
                minor
            }
        }
        return amounts.asReversed()
    }

    /**
     * A figure, rather than a reference number that happens to sit at the end.
     *
     * Money on a statement is written with pence, so two decimal places are
     * required. `CARD 1234` therefore stays in the description where it belongs.
     */
    private fun looksLikeMoney(token: String): Boolean =
        MONEY.matches(token) && Money.parseOrNull(token) != null

    /** Everything between the date and the figures. */
    private fun describe(line: String, amounts: List<Long>): String {
        var text = line.trim()
        // Remove the figures from the end, one token at a time.
        repeat(amounts.size) {
            text = text.substringBeforeLast(' ', "").trim()
        }
        // Then the date from the front.
        NUMERIC_DATE.find(text)?.let { text = text.removeRange(it.range).trim() }
        NAMED_DATE.find(text)?.let { text = text.removeRange(it.range).trim() }
        return text.trim(' ', '-', '–', '\t')
    }

    /**
     * Wording that means "this is the balance", not "this is a payment".
     * Statements open and close with one, and some repeat it per page.
     */
    private val BALANCE_LINE = Regex(
        """(?i)\b(balance|brought forward|carried forward|b/?f|c/?f|opening|closing)\b""",
    )

    /** At least this many transaction lines before a PDF counts as a statement. */
    private const val MIN_ROWS = 2

    private val WHITESPACE = Regex("\\s+")

    /** `01/03/2026`, `1-3-26`, `01.03.2026` — at the start of the line. */
    private val NUMERIC_DATE = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})""")

    /** `01 Mar 2026`, `1 March 2026`. */
    private val NAMED_DATE = Regex("""^(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+(\d{4})""")

    /**
     * A money token: optional sign or bracket, digits with optional thousands
     * separators, and exactly two decimal places. A trailing minus is included
     * because some banks print debits that way.
     */
    private val MONEY = Regex("""^[(\-+]?[£$€]?\d{1,3}(?:,\d{3})*(?:\.\d{2})[)\-]?$""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
}
