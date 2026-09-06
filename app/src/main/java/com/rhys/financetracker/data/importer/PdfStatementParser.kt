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
        val documentYear = inferYear(lines)
        val read = readLines(lines, documentYear)
        if (read.isEmpty()) return emptyList()

        // Where the money columns sit. Right-aligned, so grouping the end
        // positions across the whole statement recovers the columns even
        // though the text has been flattened.
        val columns = columnsIn(read)
        val balanceColumn = balanceColumnIn(read, columns)
        val (outColumn, inColumn) = directionColumns(read, columns, balanceColumn)

        val result = mutableListOf<Row>()
        var previousBalance: Long? = null

        for (line in read) {
            // Wording settles a brought-forward line before anything else. A
            // statement whose columns are not padded out gives no useful
            // position, and reading this as a payment invents an entry the
            // size of the entire balance.
            if (line.figures.size == 1 && BALANCE_LINE.containsMatchIn(line.description)) {
                previousBalance = line.figures.single().minor
                continue
            }

            // On a full row the last figure is the balance — that much is
            // true of every statement that prints one. Columns are only
            // needed for rows carrying a single figure, which is where a
            // balance printed once a day leaves nothing to reconcile against.
            val single = line.figures.singleOrNull()
            val balance = when {
                line.figures.size >= 2 -> line.figures.last().minor
                single != null && balanceColumn != null && near(single.endsAt, balanceColumn) ->
                    single.minor
                else -> null
            }
            val others = when {
                line.figures.size >= 2 -> line.figures.dropLast(1)
                balance != null -> emptyList()
                else -> line.figures
            }

            if (others.isEmpty()) {
                // A balance on its own updates the running total and is not
                // an entry in its own right.
                if (balance != null) previousBalance = balance
                continue
            }

            result += read(line, others, balance, previousBalance, outColumn, inColumn)
            // A row that prints no balance breaks the running chain. Carrying
            // the last one forward would compare the next row against a total
            // two transactions old, so it would neither reconcile nor deserve
            // to be flagged for failing to.
            previousBalance = balance
        }
        return result
    }

    /**
     * Reads one row, using whatever evidence the line offers.
     *
     * The balance is the strongest: it settles both the amount and the
     * direction by arithmetic. When it is absent — plenty of statements print
     * a balance only once a day — the column the figure sits in says the
     * direction instead, and the row is still read rather than abandoned.
     *
     * An amount is always produced when there is a figure to produce one from.
     * Returning nothing leaves the row with no value at all, which the importer
     * can only treat as unreadable, and that silently drops real transactions.
     */
    private fun read(
        line: ReadLine,
        others: List<Figure>,
        balance: Long?,
        previousBalance: Long?,
        outColumn: Int?,
        inColumn: Int?,
    ): Row {
        val figure = others.firstOrNull { it.minor != 0L }
            ?: others.firstOrNull()
            ?: return Row(
                line.date, line.description, null, null, balance,
                problem = "No amount was found on this line",
            )
        val size = kotlin.math.abs(figure.minor)

        // 1. The balance proves it.
        if (balance != null && previousBalance != null) {
            val change = balance - previousBalance
            if (change != 0L && kotlin.math.abs(change) == size) {
                return Row(
                    date = line.date,
                    description = line.description,
                    moneyOutMinor = if (change < 0) size else null,
                    moneyInMinor = if (change > 0) size else null,
                    balanceMinor = balance,
                )
            }
        }

        // 2. Otherwise every other kind of evidence, strongest first.
        //
        // Note what is NOT here: "this figure is not in the paid-out column"
        // is not evidence that it is money in. It was, and on a statement
        // whose columns were not proven that inverted the lot — a current
        // account showing £25,000 in and £2,900 out, with every direct debit
        // and card payment recorded as income. A figure only counts as money
        // in when something positively says so.
        val outward = when {
            figure.minor < 0L -> true
            line.marker != null -> line.marker !in CREDIT_MARKERS
            outColumn != null && near(figure.endsAt, outColumn) -> true
            inColumn != null && near(figure.endsAt, inColumn) -> false
            else -> directionFromWording(line.description)
        }

        // Reaching here with both balances present means the line had a
        // balance to check against and failed the check, which is the more
        // useful thing to say — and usually the reason the direction could
        // not be proven either.
        val mismatched = balance != null && previousBalance != null

        return Row(
            date = line.date,
            description = line.description,
            moneyOutMinor = if (outward != false) size else null,
            moneyInMinor = if (outward == false) size else null,
            balanceMinor = balance,
            problem = when {
                mismatched && outward == null ->
                    "The balance on this line does not match the amount, and nothing " +
                        "said which way the money went; read as money out — check it"
                mismatched -> "The balance on this line does not match the amount; check it"
                // Falling back to money out is not itself a problem. On a
                // current account that is what almost every line is, and now
                // that position is no longer guessed at, the fallback carries
                // ordinary shop names on any statement without a running
                // balance. Flagging those marks half a normal file doubtful,
                // which teaches you to ignore the flag that does mean
                // something. The review screen shows each row's direction
                // before anything is saved, which is the check that belongs
                // here.
                else -> null
            },
        )
    }

    /** One line of the statement, once its date and figures have been read. */
    private data class ReadLine(
        val date: LocalDate,
        val description: String,
        val figures: List<Figure>,
        /** "CR", "DR" and so on, where the bank marks direction with a letter. */
        val marker: String? = null,
    )

    /** The lines that look like statement rows, in order. */
    private fun readLines(lines: List<String>, documentYear: Int?): List<ReadLine> {
        val read = mutableListOf<ReadLine>()
        var lastDate: LocalDate? = null
        for (raw in lines) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val figures = trailingFigures(line)
            if (figures.isEmpty()) continue
            val date = leadingDate(line.trim(), documentYear)
                ?: lastDate?.takeIf { figures.size >= 2 }
                ?: continue
            lastDate = date
            val description = describe(line)
            if (description.isBlank()) continue
            read += ReadLine(date, description, figures, trailingMarker(line))
        }
        return read
    }

    /**
     * The money columns, as end positions, left to right.
     *
     * Positions within [COLUMN_TOLERANCE] of each other are the same column:
     * a figure's width varies with its size, and right alignment is never
     * pixel-exact once a PDF has been flattened to text.
     */
    private fun columnsIn(lines: List<ReadLine>): List<Int> {
        val positions = lines.flatMap { line -> line.figures.map { it.endsAt } }.sorted()
        if (positions.isEmpty()) return emptyList()

        val columns = mutableListOf<Int>()
        var group = mutableListOf(positions.first())
        for (position in positions.drop(1)) {
            if (position - group.last() <= COLUMN_TOLERANCE) {
                group += position
            } else {
                columns += group[group.size / 2]
                group = mutableListOf(position)
            }
        }
        columns += group[group.size / 2]
        return columns
    }

    /**
     * The column holding the running balance, if the statement has one.
     *
     * The rightmost money column is the usual place for it, but not every
     * statement prints one — and on those, the rightmost column is paid-in,
     * which must not be mistaken for a balance or every credit loses its
     * amount entirely.
     *
     * A balance appears against most rows; paid-in appears only against the
     * occasional one. So the rightmost column counts as the balance only when
     * it is used at least as often as any other money column.
     */
    private fun balanceColumnIn(lines: List<ReadLine>, columns: List<Int>): Int? {
        if (columns.size < 2) return null
        val uses = columns.associateWith { column ->
            lines.count { line -> line.figures.any { near(it.endsAt, column) } }
        }
        val rightmost = columns.last()

        // First, does it behave like a running total? On rows carrying both an
        // amount and a balance, a balance moves by that amount. That is
        // self-verifying and settles it outright.
        //
        // It has to come first because plenty of statements print the balance
        // only at the end of each day. Judging the column by how often it is
        // used then rejects it — the paid-out column is on far more rows — and
        // rejecting it costs the reader the one piece of evidence that proves
        // direction by arithmetic, leaving a whole statement to be guessed at.
        if (runningTotalProofs(lines, rightmost) >= MIN_BALANCE_PROOF) return rightmost

        val busiestOther = columns.dropLast(1).maxOfOrNull { uses[it] ?: 0 } ?: 0
        if ((uses[rightmost] ?: 0) < busiestOther) return null

        // And it has to leave the statement with transactions in it. A figure
        // alone in the balance column is a balance and not an entry, so if
        // calling this column the balance would empty most of the rows, it is
        // not a balance — it is the money column, on a statement that prints
        // no running total. Getting that wrong does not misread those rows, it
        // discards them.
        // Nothing above proved this column is a running total, so it is only
        // being taken on position and frequency. On that much evidence it may
        // not cost the file a single row: a figure alone in the balance column
        // is read as a balance rather than an entry, and on a statement with
        // no running total those figures are the amounts themselves.
        //
        // A brought-forward line is exempt. It is a balance on its own by
        // definition, and counting it here would reject the column on exactly
        // the statements that do print one.
        val emptied = lines.count { line ->
            line.figures.size == 1 &&
                near(line.figures.single().endsAt, rightmost) &&
                !BALANCE_LINE.containsMatchIn(line.description)
        }
        return rightmost.takeIf { emptied == 0 }
    }

    /**
     * How many rows prove [column] is a running balance.
     *
     * A row proves it by carrying both an amount and a balance where the
     * balance has moved by that amount since the last one printed. Rows in
     * between, where the bank printed no balance, break the chain and simply
     * do not count either way.
     */
    private fun runningTotalProofs(lines: List<ReadLine>, column: Int): Int {
        var previous: Long? = null
        var proofs = 0
        for (line in lines) {
            val last = line.figures.lastOrNull() ?: continue
            if (!near(last.endsAt, column)) continue
            val amounts = line.figures.dropLast(1)
            val before = previous
            previous = last.minor
            if (before == null || amounts.isEmpty()) continue
            val change = last.minor - before
            if (change != 0L && amounts.any { kotlin.math.abs(it.minor) == kotlin.math.abs(change) }) {
                proofs++
            }
        }
        return proofs
    }

    /** Rows that have to agree before a column counts as the running balance. */
    private const val MIN_BALANCE_PROOF = 2

    /**
     * Which column means money out, learned from the rows that prove it.
     *
     * Rather than assuming an order — banks disagree about whether paid-out or
     * paid-in comes first — the rows where a balance is present and moves are
     * used as worked examples, and the majority verdict is applied to the rows
     * where there is nothing to check against. Null when nothing proved it.
     */
    private fun directionColumns(
        lines: List<ReadLine>,
        columns: List<Int>,
        balanceColumn: Int?,
    ): Pair<Int?, Int?> {
        if (columns.size < 2) return null to null
        val moneyColumns = columns.filter { it != balanceColumn }
        if (balanceColumn == null) return byHowBusy(lines, moneyColumns)

        val votes = mutableMapOf<Int, Int>()
        var previousBalance: Long? = null
        for (line in lines) {
            val balance = line.figures.firstOrNull { near(it.endsAt, balanceColumn) }?.minor
            val others = line.figures.filterNot { near(it.endsAt, balanceColumn) }
            val figure = others.firstOrNull { it.minor != 0L }

            if (balance != null && previousBalance != null && figure != null) {
                val change = balance - previousBalance
                if (change != 0L && kotlin.math.abs(change) == kotlin.math.abs(figure.minor)) {
                    val column = columns.minByOrNull { kotlin.math.abs(it - figure.endsAt) }
                    if (column != null) {
                        votes[column] = (votes[column] ?: 0) + if (change < 0) 1 else -1
                    }
                }
            }
            // Same broken chain as in the main read: a row with no balance
            // leaves the next one nothing to be proved against.
            previousBalance = balance
        }

        // A column with more "money left the account" verdicts than the other
        // way is the paid-out column, and one with more the other way is
        // paid-in.
        val out = votes.entries.filter { it.value > 0 }.maxByOrNull { it.value }?.key
        val inward = votes.entries.filter { it.value < 0 }.minByOrNull { it.value }?.key
        if (out == null && inward == null) return byHowBusy(lines, moneyColumns)

        // Only one of the two usually gets proved. A statement is mostly
        // outgoings, so the paid-out column earns its votes early while the
        // handful of credits may never happen to land next to a printed
        // balance. But once one money column is known, the other one is the
        // opposite by elimination — there are only two — and that is a
        // deduction rather than a guess.
        val other = moneyColumns.singleOrNull { it != out && it != inward }
        return (out ?: other) to (inward ?: other)
    }

    /**
     * Which money column is which, judged by how many rows use each.
     *
     * The busier one is paid-out. Not a guess about layout — a fact about
     * accounts: a month holds dozens of card payments and direct debits
     * against a salary and the odd refund. Where the running balance proves
     * nothing, this is the only evidence that covers a whole file at once.
     *
     * Position was tried here and was a coin toss, because banks disagree
     * about whether paid-out or paid-in comes first. How busy a column is does
     * not depend on that convention.
     *
     * No margin is required beyond one being busier than the other. That would
     * once have been reckless, since losing the call inverts the statement —
     * but "Swap all" on the review screen now undoes an inversion in a single
     * tap, while having no verdict at all leaves every row to be corrected one
     * at a time. Being wrong costs a tap; saying nothing costs two hundred.
     */
    private fun byHowBusy(lines: List<ReadLine>, moneyColumns: List<Int>): Pair<Int?, Int?> {
        if (moneyColumns.size < 2) return null to null
        val uses = moneyColumns.associateWith { column ->
            lines.count { line -> line.figures.any { near(it.endsAt, column) } }
        }
        val ranked = moneyColumns.sortedByDescending { uses[it] ?: 0 }
        // A tie is genuinely no evidence, and inventing one here would be the
        // coin toss again.
        if (uses[ranked[0]] == uses[ranked[1]]) return null to null
        return ranked[0] to ranked[1]
    }

    /**
     * Which way the money went according to what the line calls itself.
     *
     * Weaker than the balance and weaker than a column the balance proved, but
     * far stronger than a guess — a direct debit is not income, whatever
     * column its figure landed in. Outgoings are tested first because "credit
     * card payment" is money leaving, and the words for money arriving are
     * kept narrow for the same reason.
     */
    internal fun directionFromWording(description: String): Boolean? {
        val text = description.lowercase()
        if (OUTGOING_WORDS.containsMatchIn(text)) return true
        if (INCOMING_WORDS.containsMatchIn(text)) return false
        return null
    }

    /** How a statement writes "money left the account". */
    private val OUTGOING_WORDS = Regex(
        """(?i)\b(direct debit|standing order|card payment|contactless|debit card|""" +
            """cash withdrawal|withdrawal|atm|cash machine|bill payment|payment to|""" +
            """purchase|pay at pump|faster payment to|transfer to|charge|fee)\b""",
    )

    /**
     * And "money arrived". Kept to wordings that cannot mean an outgoing:
     * "credit" alone is in half the card payments on a statement.
     */
    private val INCOMING_WORDS = Regex(
        """(?i)\b(salary|wages|payroll|refund|refunded|reimbursement|rebate|cashback|""" +
            """dividend|bacs credit|credit from|credit interest|interest paid|""" +
            """transfer from|paid in|deposit|bgc|giro|payment received|""" +
            """faster payment received|credit transfer)\b""",
    )

    /** Markers meaning the money arrived rather than left. */
    private val CREDIT_MARKERS = setOf("CR", "C")

    private fun near(position: Int, column: Int): Boolean =
        kotlin.math.abs(position - column) <= COLUMN_TOLERANCE

    /** How far apart two figures can end and still be the same column. */
    private const val COLUMN_TOLERANCE = 3

    /**
     * The date a statement line starts with.
     *
     * UK statements write it as `01 Mar 2026`, `01/03/2026`, `1 March 2026` or
     * `01 Mar` with the year implied by the statement period; the last of those
     * is rejected here rather than guessed, since a wrong year files a
     * transaction in the wrong month.
     */
    internal fun leadingDate(line: String, fallbackYear: Int? = null): LocalDate? {
        NUMERIC_DATE.find(line)?.let { match ->
            val (d, m, y) = match.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(year, m.toInt(), d.toInt()) }.getOrNull()
        }
        NAMED_DATE.find(line)?.let { match ->
            val (d, name, y) = match.destructured
            val month = MONTHS[name.lowercase().take(3)] ?: return null
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(year, month, d.toInt()) }.getOrNull()
        }
        // "01 Mar" with the year in the statement heading. Only read when a
        // year was found in the document: guessing one files transactions in
        // the wrong year, which is worse than declining the row.
        if (fallbackYear != null) {
            SHORT_DATE.find(line)?.let { match ->
                val (d, name) = match.destructured
                val month = MONTHS[name.lowercase().take(3)] ?: return null
                return runCatching { LocalDate.of(fallbackYear, month, d.toInt()) }.getOrNull()
            }
        }
        return null
    }

    /**
     * A year for rows that do not carry one, taken from anywhere in the
     * document — the statement period, a printed-on date, a fully dated row.
     *
     * The latest year present is used, since a statement covering a year end
     * mentions both and the later one is where most of its rows sit.
     */
    internal fun inferYear(lines: List<String>): Int? =
        lines.flatMap { YEAR.findAll(it).map { match -> match.value.toInt() } }
            .filter { it in FIRST_PLAUSIBLE_YEAR..LAST_PLAUSIBLE_YEAR }
            .maxOrNull()

    /**
     * The run of figures at the end of a line, in order.
     *
     * Only a trailing run counts. A description can hold digits of its own —
     * `TESCO STORES 3294`, `CARD 1234` — and those are part of the payee, not
     * money. Requiring pence, brackets or a sign keeps them out.
     */
    /**
     * A figure and where it ended on the line.
     *
     * Money columns are right-aligned, so the end position is what stays put
     * from row to row. That position is what says which column a figure is in
     * — the one piece of evidence that survives when a row has no balance to
     * reconcile against.
     */
    internal data class Figure(val endsAt: Int, val minor: Long)

    /** [trailingAmounts], but keeping each figure's column position. */
    internal fun trailingFigures(line: String): List<Figure> {
        val text = line.trimEnd()
        val figures = mutableListOf<Figure>()
        var end = text.length
        while (end > 0) {
            val start = text.lastIndexOf(' ', end - 1) + 1
            val token = text.substring(start, end)
            if (token.isEmpty()) {
                end = start - 1
                continue
            }
            if (token.uppercase() in DIRECTION_MARKERS) {
                end = start - 1
                continue
            }
            if (!looksLikeMoney(token)) break
            val minor = Money.parseOrNull(token) ?: break
            val signed = if (token.startsWith("-") || token.endsWith("-") ||
                (token.startsWith("(") && token.endsWith(")"))
            ) {
                -kotlin.math.abs(minor)
            } else {
                minor
            }
            figures += Figure(endsAt = end, minor = signed)
            end = start - 1
        }
        return figures.asReversed()
    }

    /**
     * The debit/credit letter among a line's trailing figures, if it has one.
     *
     * Banks that mark direction this way state it outright — "42.15 D",
     * "1,862.23 CR" — which is better evidence than any amount of reasoning
     * about columns. It was being stepped over to get at the figures and then
     * thrown away.
     */
    internal fun trailingMarker(line: String): String? {
        val tokens = line.trim().split(WHITESPACE)
        val start = trailingRunStart(tokens)
        return tokens.drop(start).firstOrNull { it.uppercase() in DIRECTION_MARKERS }?.uppercase()
    }

    /**
     * Where a line's trailing run of figures begins.
     *
     * Markers belong to that run only when a figure sits to their left inside
     * it: in "42.15 D 1,957.85" the D marks the 42.15, but in "CARD PAYMENT C
     * 2.10" the C is the last word of the payee. Without that distinction a
     * description ending in a single letter has it eaten off the name and, far
     * worse, is read as a credit — "C" being how some banks write one.
     */
    private fun trailingRunStart(tokens: List<String>): Int {
        var index = tokens.size
        while (index > 0) {
            val token = tokens[index - 1]
            if (!looksLikeMoney(token) && token.uppercase() !in DIRECTION_MARKERS) break
            index--
        }
        // Markers at the left edge of the run have no figure of their own to
        // mark, so they are part of the description after all.
        while (index < tokens.size && tokens[index].uppercase() in DIRECTION_MARKERS) {
            index++
        }
        return index
    }

    internal fun trailingAmounts(line: String): List<Long> {
        val amounts = mutableListOf<Long>()
        for (token in line.trim().split(WHITESPACE).asReversed()) {
            // Some banks mark the direction with a letter beside each figure
            // rather than by column — "42.15 D   1,957.85". The marker sits
            // between the figures, so it is stepped over rather than only
            // trimmed off the end. The balance still decides the direction.
            if (token.uppercase() in DIRECTION_MARKERS) continue
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

    /**
     * Everything between the date and the figures.
     *
     * The tail is dropped by the same rule that read it — figures and any
     * direction markers among them — rather than by counting tokens, so a
     * stray "D" cannot leave part of the payee behind or eat a word of it.
     */
    private fun describe(line: String): String {
        val tokens = line.trim().split(WHITESPACE)
        var text = tokens.take(trailingRunStart(tokens)).joinToString(" ")
        // Then the date from the front, whichever form it took. First match
        // wins: the forms overlap, and "01 Mar 2026" must not be trimmed to
        // "2026" by the short form running afterwards.
        for (pattern in DATE_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                text = text.removeRange(match.range)
                break
            }
        }
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

    /** Letters some banks put after a figure to mean debit or credit. */
    private val DIRECTION_MARKERS = setOf("CR", "DR", "C", "D")

    /** `01 Mar`, `1 March` — no year, which comes from the statement heading. */
    private val SHORT_DATE = Regex("""^(\d{1,2})\s+([A-Za-z]{3,9})\.?(?!\s*\d{2,4})""")

    /** Longest form first, so a fuller date is never mistaken for a shorter one. */
    private val DATE_PATTERNS by lazy { listOf(NUMERIC_DATE, NAMED_DATE, SHORT_DATE) }

    private val YEAR = Regex("""\b(19|20)\d{2}\b""")
    private const val FIRST_PLAUSIBLE_YEAR = 1990
    private const val LAST_PLAUSIBLE_YEAR = 2100

    /** `01/03/2026`, `1-3-26`, `01.03.2026` — at the start of the line. */
    private val NUMERIC_DATE = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})""")

    /** `01 Mar 2026`, `1 March 2026`. */
    private val NAMED_DATE = Regex("""^(\d{1,2})\s+([A-Za-z]{3,9})\.?\s+(\d{4})""")

    /**
     * A money token: optional sign or bracket, digits with two decimal places,
     * with or without thousands separators. A trailing minus is included
     * because some banks print debits that way.
     *
     * Both groupings have to be accepted. Requiring the separator meant
     * "1862.23" was not money at all — so any amount of a thousand or more
     * printed without a comma was not merely misread, it was invisible, and
     * the row carrying it was dropped from the import without a word. Plenty
     * of statements print salaries and transfers exactly that way.
     *
     * Separated groups still have to be groups of three, so "1,23.45" is
     * rejected rather than quietly read as some other number.
     */
    private val MONEY =
        Regex("""^[(\-+]?[£$€]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{2})[)\-]?$""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
}
