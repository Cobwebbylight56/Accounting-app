package com.rhys.financetracker.data.importer

import java.time.LocalDate

/**
 * Statements that print the column headings again above every payment —
 * Lloyds' statements downloaded from the app, as read from the PDF:
 *
 *     Date Description Type Money In (£) Balance (£)
 *     05 Feb 26 .
 *     . ACME LTD ey Obulat n(£k)
 *     . BGC. 1,862.23 Mon
 *     . 1,972.23.
 *
 * Each heading names the one money column its payment uses — Money In or
 * Money Out — so which way it went is read, not guessed, and the running
 * balance checks it. The page also carries hidden words for screen readers
 * ("blank.", "Column"), and where they sit on a heading the two come out
 * jumbled together: "ey Obulat n(£k)" is "Out" mixed with "blank". Those
 * scraps are left out of the payee.
 */
object RepeatedHeadingsReader {

    /** Null when the statement isn't laid out this way; otherwise its payments, oldest first. */
    fun parse(lines: List<String>): List<PdfStatementParser.Row>? {
        val starts = lines.indices.filter { HEADING.matches(lines[it].trim()) }
        if (starts.isEmpty()) return null
        val last = lines.indices.firstOrNull { it > starts.last() && END.containsMatchIn(lines[it]) } ?: lines.size
        val read = starts.zip(starts.drop(1) + last).mapNotNull { (start, end) ->
            payment(lines[start].trim(), lines.subList(start + 1, end).joinToString(" "))
        }
        if (read.isEmpty()) return emptyList()
        val ordered = if (read.first().date.isAfter(read.last().date)) read.reversed() else read
        return reconcile(ordered, opening(lines))
    }

    private class ReadPayment(
        val date: LocalDate,
        val description: String,
        val amountMinor: Long,
        val balanceMinor: Long?,
        /** As the heading says: true for Money In, false for Money Out. */
        val moneyIn: Boolean,
    )

    private fun payment(heading: String, text: String): ReadPayment? {
        val date = DATE.find(text) ?: return null
        val day = date.groupValues[1].toInt()
        val month = MONTHS.indexOf(date.groupValues[2].lowercase().take(3)) + 1
        val year = date.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        val on = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null

        val words = text.substring(date.range.last + 1)
            .split(SPACES)
            .map { it.trim('.') }
            .filter { it.isNotEmpty() && !isScrap(it) }
        val firstFigure = words.indexOfFirst { MONEY.matches(it) }
        if (firstFigure < 0) return null
        val figures = words.drop(firstFigure).filter { MONEY.matches(it) }.map { minorOf(it) }
        return ReadPayment(
            date = on,
            description = describe(words.take(firstFigure)),
            amountMinor = figures.first(),
            balanceMinor = figures.drop(1).lastOrNull(),
            moneyIn = heading.contains("Money In", ignoreCase = true),
        )
    }

    /**
     * The payee, and the bank's code for the kind of payment — which comes
     * straight after it — in words: "A CUSTOMER FASTER PAYMENT".
     */
    private fun describe(words: List<String>): String {
        val code = words.indexOfLast { it in KINDS }
        if (code < 0) return words.joinToString(" ")
        return (words.subList(0, code) + KINDS.getValue(words[code])).joinToString(" ")
    }

    /**
     * A statement's summary that an older version took for a payment:
     * "Money In. . Balance on . .".
     */
    fun isSummary(description: String): Boolean = SUMMARY_ROW.containsMatchIn(description)

    /**
     * [description] as this reader would have written it, when an older
     * version kept heading scraps in it — ". A CUSTOMER. FPO Moneyb Ilna
     * n(k£.)" — or null when it has none. Put right, the same payment read
     * again is recognised as already there.
     */
    fun tidy(description: String): String? {
        val words = description.split(SPACES).map { it.trim('.') }.filter { it.isNotEmpty() }
        // Only the jumble of a heading's "(£)" — never a payee's own "(uk)".
        if (words.none { isScrap(it) && '(' in it && '£' in it }) return null
        return describe(words.filterNot { isScrap(it) }).takeIf { it.isNotBlank() }
    }

    /** Which way each went, checked against the balance; the balance wins where it proves otherwise. */
    private fun reconcile(payments: List<ReadPayment>, opening: Long?): List<PdfStatementParser.Row> {
        var before = opening
        return payments.map { p ->
            val change = if (p.balanceMinor != null && before != null) p.balanceMinor - before!! else null
            val proved = change != null && kotlin.math.abs(change) == p.amountMinor
            val moneyIn = if (proved) change!! > 0 else p.moneyIn
            before = p.balanceMinor ?: before?.let { if (moneyIn) it + p.amountMinor else it - p.amountMinor }
            PdfStatementParser.Row(
                date = p.date,
                description = p.description,
                moneyOutMinor = if (moneyIn) null else p.amountMinor,
                moneyInMinor = if (moneyIn) p.amountMinor else null,
                balanceMinor = p.balanceMinor,
                problem = if (change != null && !proved) MISMATCH else null,
            )
        }
    }

    /** "Balance on 01 February 2026. £110.00.": the earliest one is what it opened at. */
    private fun opening(lines: List<String>): Long? =
        OPENING.findAll(lines.joinToString(" "))
            .mapNotNull { match ->
                val on = DATE.find(match.groupValues[1]) ?: return@mapNotNull null
                val day = on.groupValues[1].toInt()
                val month = MONTHS.indexOf(on.groupValues[2].lowercase().take(3)) + 1
                val year = on.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
                runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { it to minorOf(match.groupValues[2]) }
            }
            .minByOrNull { it.first }
            ?.second

    /**
     * A scrap of a heading jumbled with the hidden "blank": every letter
     * from "Money In", "Money Out" or "blank", with some in lower case. A
     * payee on these statements is in capitals, so it is never one.
     */
    private fun isScrap(word: String): Boolean =
        word.any { it.isLowerCase() } && word.all { it in SCRAP_LETTERS }

    private fun minorOf(figure: String): Long =
        figure.replace(",", "").replace(".", "").toLong()

    private val HEADING = Regex("""(?i)^date\s+description\s+type\s+money\s+(in|out)\s*\(£\)\s+balance\s*\(£\)$""")

    /** The key to the bank's codes, after the last payment. */
    private val END = Regex("""(?i)^transaction types\b""")

    private val DATE = Regex(
        """\b(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+(\d{4}|\d{2})\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val OPENING = Regex("""(?i)balance on\s+(\d{1,2}\s+[a-z]+\s+\d{4})\.?\s*£?(\d{1,3}(?:,\d{3})*\.\d{2})""")
    private val SUMMARY_ROW = Regex("""(?i)^\W*money\s+(in|out)\b.*\bbalance on\b""")
    private val MONEY = Regex("""^\d{1,3}(?:,\d{3})*\.\d{2}$""")
    private val SPACES = Regex("""\s+""")
    private const val SCRAP_LETTERS = "MoneyInOutblak()£."

    /**
     * Lloyds' codes, in the words the rest of the app understands: a Direct
     * Debit or standing order is marked as one, and money sent to or from
     * someone reads as a payment to a person.
     */
    private val KINDS = mapOf(
        "BGC" to "BANK CREDIT",
        "BP" to "BILL PAYMENT",
        "CHG" to "CHARGE",
        "CHQ" to "CHEQUE",
        "COR" to "CORRECTION",
        "CPT" to "CASHPOINT",
        "DD" to "DD",
        "DEB" to "DEBIT CARD",
        "DEP" to "DEPOSIT",
        "FEE" to "FEE",
        "FPI" to "FASTER PAYMENT",
        "FPO" to "FASTER PAYMENT",
        "MPI" to "MOBILE PAYMENT",
        "MPO" to "MOBILE PAYMENT",
        "PAY" to "PAYMENT",
        "SO" to "SO",
        "TFR" to "TFR",
    )

    private const val MISMATCH = "The balance on this line does not match the amount; check it"
}
