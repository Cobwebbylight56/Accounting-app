package com.rhys.financetracker.data.importer

import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.abs

/**
 * Reads a statement whatever its layout, for when the usual reader — dates
 * down the left, figures down the right — can't make sense of it.
 *
 * Every statement, however it is drawn, has the same three things on each
 * transaction: a date, an amount, and who it was with. This looks for them
 * wherever they are on the line, in whatever form:
 *
 * * **Dates** in any common shape — `27/01/2026`, `2026-01-27`, `27 Jan`,
 *   `27th January 2026`, `27-Jan-26`, or month first as Revolut and Amex
 *   write it, `Jan 27, 2026` — at the start, the end or in the middle.
 * * **Amounts** with or without £, a minus, brackets, or CR/DR beside them.
 * * **Rows spread over lines**: a date printed once with several payments
 *   under it, a payee running onto the next line, an amount on a line of
 *   its own.
 *
 * What a statement says about itself — the due date, the limit, the
 * minimum payment, the totals, the interest rates — is left out, and so is
 * anything dated far from the rest.
 *
 * Which way the money went is settled by the strongest evidence there is:
 * a sign or CR/DR on the amount, then the running balance, then the words
 * ("Payment received", "Refund", "Direct debit"), then — as on most lines of
 * most statements — money out. On a card statement it is the other way
 * round wherever the balance or a sign is concerned: the balance is what is
 * owed, and a minus is a payment or refund.
 */
object FlexibleStatementReader {

    fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): List<PdfStatementParser.Row> {
        val year = PdfStatementParser.inferYear(lines, today.year)
        val card = CARD.containsMatchIn(lines.joinToString(" "))
        val pieces = lines.map { piece(it, year, today) }
        val (opening, records) = assemble(pieces)
        return direct(near(records), opening, card)
    }

    // ------------------------------------------------------------- one line

    /** How an amount is marked: a minus or brackets, a plus, CR or DR, or nothing. */
    internal enum class Mark { NONE, MINUS, PLUS, CR, DR }

    /** An amount on a line, and how it was marked. */
    internal data class Amount(val minor: Long, val mark: Mark) {
        /** As a balance: below nothing when marked so. */
        val signedMinor: Long get() = if (mark == Mark.MINUS || mark == Mark.DR) -minor else minor
    }

    private data class Piece(
        val date: LocalDate?,
        val amounts: List<Amount>,
        val text: String,
        val summary: Boolean,
        val opening: Boolean,
    )

    private fun piece(raw: String, year: Int?, today: LocalDate): Piece {
        var line = raw.replace(SPACES, " ").trim()
        val found = dateIn(line, year, today)
        if (found != null) {
            // "Monday 27 September 2026": the day's name goes with the date.
            line = line.removeRange(found.second).replace(SPACES, " ").trim().replace(WEEKDAY_EDGE, "").trim()
        }
        // A second date straight after — the day it was posted, or bought.
        dateIn(line, year, today)?.takeIf { it.second.first <= 1 }?.let {
            line = line.removeRange(it.second).trim()
        }
        val amounts = mutableListOf<Amount>()
        val text = StringBuilder()
        var at = 0
        for (match in AMOUNT.findAll(line)) {
            text.append(line, at, match.range.first).append(' ')
            at = match.range.last + 1
            amounts += amountOf(match)
        }
        text.append(line.substring(at))
        val words = text.toString()
            .replace(TRAILING_ON_DATE, "")
            .replace(SPACES, " ")
            .trim(' ', '-', '–', '|', ':', '*', '£')
        val lower = words.lowercase()
        return Piece(
            date = found?.first,
            amounts = amounts,
            text = words,
            summary = SUMMARY.containsMatchIn(lower) || TOTALS.matches(lower) || '%' in line,
            opening = OPENING.containsMatchIn(lower),
        )
    }

    private fun amountOf(match: MatchResult): Amount {
        val (before, open, pounds, pence, close, after) = match.destructured
        val minor = pounds.replace(",", "").toLong() * 100 + pence.toLong()
        val marker = after.trim().lowercase()
        val mark = when {
            marker == "cr" -> Mark.CR
            marker == "dr" -> Mark.DR
            before == "-" || before == "−" || (open == "(" && close == ")") || marker == "-" -> Mark.MINUS
            before == "+" -> Mark.PLUS
            else -> Mark.NONE
        }
        return Amount(minor, mark)
    }

    /** The first date on [line] and where it sits, or null. */
    internal fun dateIn(line: String, year: Int?, today: LocalDate): Pair<LocalDate, IntRange>? {
        val candidates = mutableListOf<Pair<LocalDate, IntRange>>()
        ISO.findAll(line).forEach { m ->
            val (y, mo, d) = m.destructured
            dateOf(y.toInt(), mo.toInt(), d.toInt())?.let { candidates += it to m.range }
        }
        NUMERIC.findAll(line).forEach { m ->
            val (d, mo, y) = m.destructured
            val fullYear = y.toInt().let { if (it < 100) 2000 + it else it }
            dateOf(fullYear, mo.toInt(), d.toInt())?.let { candidates += it to m.range }
        }
        DAY_MONTH.findAll(line).forEach { m ->
            val month = MONTHS[m.groupValues[3].lowercase().take(3)] ?: return@forEach
            val day = m.groupValues[2].toInt()
            val written = m.groups[4]?.value
            // "27 Jan 26 Jan": a second date, not the year 2026 — and a
            // two-digit "year" far from the statement's is its next word.
            val rest = line.substring(m.range.last + 1)
            val shortYear = written != null && written.length == 2 &&
                !MONTH_NEXT.containsMatchIn(rest) &&
                (year == null || abs(2000 + written.toInt() - year) <= 1)
            val explicit = when {
                written == null -> null
                written.length == 4 -> written.toInt()
                shortYear -> 2000 + written.toInt()
                else -> null
            }
            // A year not taken stays in the line, as the start of what follows.
            val range = if (written != null && explicit == null) m.groups[1]!!.range else m.range
            resolve(day, month, explicit, year, today)?.let { candidates += it to range }
        }
        MONTH_DAY.findAll(line).forEach { m ->
            val month = MONTHS[m.groupValues[1].lowercase().take(3)] ?: return@forEach
            val day = m.groupValues[2].toInt()
            val explicit = m.groups[3]?.value?.toInt()
            resolve(day, month, explicit, year, today)?.let { candidates += it to m.range }
        }
        return candidates.minWithOrNull(compareBy<Pair<LocalDate, IntRange>> { it.second.first }.thenByDescending { it.second.last })
    }

    private fun resolve(day: Int, month: Int, explicit: Int?, year: Int?, today: LocalDate): LocalDate? {
        if (explicit != null) return dateOf(explicit, month, day)
        if (year != null) return dateOf(year, month, day)
        // No year anywhere: the latest one that isn't in the future.
        val monthDay = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
        val thisYear = monthDay.atYear(today.year)
        return if (thisYear.isAfter(today.plusDays(1))) monthDay.atYear(today.year - 1) else thisYear
    }

    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? =
        if (year in FIRST_YEAR..LAST_YEAR) runCatching { LocalDate.of(year, month, day) }.getOrNull() else null

    // -------------------------------------------------------------- rows

    private data class Record(val date: LocalDate, val text: String, val amounts: List<Amount>)

    /**
     * The lines put back together into transactions, and the balance the
     * statement opens with if it says.
     */
    private fun assemble(pieces: List<Piece>): Pair<Long?, List<Record>> {
        var opening: Long? = null
        val records = mutableListOf<Record>()
        var lastDate: LocalDate? = null
        // A dated line still waiting for its amount, on the line or lines after.
        var waiting: Pair<LocalDate, StringBuilder>? = null

        for (piece in pieces) {
            if (piece.summary || piece.opening) {
                // The balance it starts from, when it says so before any row.
                if (piece.opening && records.isEmpty() && piece.amounts.size == 1) {
                    opening = piece.amounts.single().signedMinor
                }
                waiting = null
                continue
            }
            val date = piece.date
            when {
                date != null && piece.amounts.isNotEmpty() -> {
                    records += Record(date, piece.text, piece.amounts)
                    waiting = null
                    lastDate = date
                }
                date != null -> {
                    // A date heading the rows under it, or a row whose amount is below.
                    waiting = date to StringBuilder(piece.text)
                    lastDate = date
                }
                piece.amounts.isNotEmpty() -> {
                    val held = waiting
                    if (held != null) {
                        val text = listOf(held.second.toString(), piece.text).filter { it.isNotBlank() }.joinToString(" ")
                        if (text.isNotBlank()) records += Record(held.first, text, piece.amounts)
                        // The date may head more rows yet.
                        waiting = held.first to StringBuilder()
                    } else if (lastDate != null && piece.text.isNotBlank() && piece.text.any { it.isLetter() }) {
                        // One of several payments under a date printed once.
                        records += Record(lastDate, piece.text, piece.amounts)
                    }
                }
                piece.text.isNotBlank() -> {
                    // The payee carried onto the next line, before its amount.
                    waiting?.let { held ->
                        if (held.second.length + piece.text.length <= MAX_DESCRIPTION) {
                            if (held.second.isNotEmpty()) held.second.append(' ')
                            held.second.append(piece.text)
                        }
                    }
                }
            }
        }
        return opening to records.filter { it.text.any(Char::isLetter) }
    }

    /**
     * The rows dated near the rest: a statement covers a month or so, a
     * year at most. A due date, a date of birth or a "since 1998" is not a
     * transaction.
     */
    private fun near(records: List<Record>): List<Record> {
        if (records.size < 3) return records
        val middle = records.map { it.date }.sorted()[records.size / 2]
        return records.filter { abs(java.time.temporal.ChronoUnit.DAYS.between(middle, it.date)) <= MAX_SPREAD_DAYS }
    }

    /** Each row's amount, balance and direction. */
    private fun direct(records: List<Record>, opening: Long?, card: Boolean): List<PdfStatementParser.Row> {
        var previous = opening
        return records.map { record ->
            val amounts = record.amounts
            // One figure is the amount; with more, the last is the balance.
            val amount = if (amounts.size == 1) amounts.single() else amounts.dropLast(1).firstOrNull { it.minor != 0L } ?: amounts.first()
            val balance = if (amounts.size >= 2) amounts.last().signedMinor else null
            val size = amount.minor
            val before = previous
            val proved = if (balance != null && before != null) {
                val change = balance - before
                // On a card the balance is what is owed: up is spending.
                if (change != 0L && abs(change) == size) (if (card) change > 0 else change < 0) else null
            } else {
                null
            }
            val out = when (amount.mark) {
                Mark.CR -> false
                Mark.DR -> true
                // A minus on a card is a payment to it or a refund; on an account, money out.
                Mark.MINUS -> !card
                Mark.PLUS -> card
                Mark.NONE -> proved ?: PdfStatementParser.directionFromWording(record.text) ?: true
            }
            // Spending adds to what's owed on a card and takes from an account.
            previous = balance ?: before?.let { if (out == card) it + size else it - size }
            PdfStatementParser.Row(
                date = record.date,
                description = record.text.take(MAX_DESCRIPTION),
                moneyOutMinor = if (out) size else null,
                moneyInMinor = if (out) null else size,
                balanceMinor = balance,
                problem = if (balance != null && before != null && proved == null) {
                    "The balance on this line does not match the amount; check it"
                } else {
                    null
                },
            )
        }
    }

    // ----------------------------------------------------------- patterns

    private val SPACES = Regex("""\s+""")

    /** A day's name left at either end of a line once its date is taken out. */
    private val WEEKDAY_EDGE = Regex(
        """(?i)^(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\.?,?(\s+|$)|(\s+|^)(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\.?,?$""",
    )

    /**
     * An amount: always with pence, so a reference or a card number is
     * never taken for one. Never followed by a % (a rate) or more digits.
     */
    private val AMOUNT = Regex(
        """(?<![\w.,/])([-−+]?)\s?(\(?)\s?£?\s?(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(\)?)((?:\s?(?:CR|DR|Cr|Dr|cr|dr)\b)|-)?(?![\d%])""",
    )

    private val ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val NUMERIC = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})\b(?![.,]\d)""")
    private val DAY_MONTH = Regex(
        """\b((\d{1,2})(?:st|nd|rd|th)?[\s-]+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?)(?:[\s,-]+(\d{4}|\d{2})(?![\d]|[.,]\d))?""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTH_DAY = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b(?![.,]\d|:)(?:,?\s+(\d{4})\b)?""",
        RegexOption.IGNORE_CASE,
    )

    /** A month's name straight after a two-digit number: it was the day of a second date. */
    private val MONTH_NEXT = Regex("""^\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)""", RegexOption.IGNORE_CASE)

    private val TRAILING_ON_DATE = Regex("""(?i)\s+on\s+\d{1,2}\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?$""")

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    /** What a statement says about itself, rather than a transaction. */
    private val SUMMARY = Regex(
        """\b(balance (brought|carried) forward|(previous|opening|closing|new|current|available|statement|account) balance|your (new )?balance|available (to spend|credit|funds)|credit limit|minimum (payment|repayment)|payment due|due (date|on|by)|repayment due|statement (date|period|for|number)|interest rate|estimated interest|annual rate|apr|aer|page \d+|issued on|date of birth|sort code|account (number|no)|iban|bic|period|summary|overdraft limit|arranged overdraft)\b""",
    )

    /** A line that is only a column's total. */
    private val TOTALS = Regex("""^(statement )?totals?:?$|^total (paid|money|payments|debits|credits|in|out)\b.*""")

    /** The balance the statement starts from. */
    private val OPENING = Regex("""\b(previous (statement )?balance|opening balance|brought forward|b/f|balance from previous)\b""")

    /** A credit card's statement: a limit, and a minimum to pay. */
    private val CARD = Regex("""(?i)\b(credit\s+limit|minimum\s+payment)\b""")

    private const val MAX_DESCRIPTION = 80
    private const val MAX_SPREAD_DAYS = 400L
    private const val FIRST_YEAR = 1990
    private const val LAST_YEAR = 2100
}
