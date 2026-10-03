package com.rhys.financetracker.data.receipts

import java.time.LocalDate
import java.time.MonthDay
import kotlin.math.abs

/**
 * Text read off a picture with where each line sat, and what can be made of
 * it from the layout: rows as the eye reads them, and a list of payments.
 *
 * Text recognition hands back a screen in blocks, and a row like
 * "ASDA PETROL 4275 ...... £30.00" usually comes back as two — the name on
 * the left and the amount on the right — often far apart in the text. Put
 * back into rows by where they sat, the amount is beside its name again.
 */
object ScreenText {

    /** One line of text and its box on the picture, in pixels. */
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centreY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /** A payment in a list on a screenshot: Google Wallet, a banking app's list of payments. */
    data class ListedPayment(
        val payee: String,
        val amountMinor: Long,
        val date: LocalDate?,
        /** Money back: shown with a plus, or called a refund. */
        val isMoneyIn: Boolean,
    )

    /** The lines put back into rows, top to bottom, each read left to right. */
    fun rows(lines: List<Line>): List<List<Line>> {
        val rows = mutableListOf<MutableList<Line>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centreY }) {
            val row = rows.lastOrNull()
            val sameRow = row != null && row.any { other ->
                abs(other.centreY - line.centreY) <= minOf(other.height, line.height) / 2
            }
            if (sameRow) row!!.add(line) else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    /** The rows as plain text, for reading a receipt. */
    fun rowText(lines: List<Line>): String =
        rows(lines).joinToString("\n") { row -> row.joinToString("  ") { it.text.trim() } }

    /**
     * The payments listed on a screenshot like Google Wallet's: each a name
     * with an amount on its row and a date under it (the name sometimes
     * running onto a second line). Empty unless it really is a list — at
     * least two payments, most of them dated, and no TOTAL anywhere, which
     * would make it a receipt.
     */
    fun listedPayments(lines: List<Line>, today: LocalDate = LocalDate.now()): List<ListedPayment> {
        val rows = rows(lines).map { row -> row.joinToString("  ") { it.text.trim() } }
        if (rows.any { TOTAL.containsMatchIn(it.lowercase()) }) return emptyList()

        class Building(val payee: StringBuilder, val amountMinor: Long, val isMoneyIn: Boolean) {
            var date: LocalDate? = null
        }
        val found = mutableListOf<Building>()
        for (row in rows) {
            val amount = TRAILING_AMOUNT.find(row)
            if (amount != null) {
                val name = row.substring(0, amount.range.first).trim().trimEnd('-', '·', '•').trim()
                val minor = toMinor(amount.groupValues[2]) ?: continue
                val sign = amount.groupValues[1]
                // An amount with no name beside it belongs to nothing.
                if (name.count { it.isLetter() } < 2) continue
                val dateInName = shortDate(name, today)
                found += Building(
                    payee = StringBuilder(if (dateInName != null) stripDate(name) else name),
                    amountMinor = minor,
                    isMoneyIn = sign == "+" || "refund" in row.lowercase(),
                ).also { it.date = dateInName }
                continue
            }
            val current = found.lastOrNull() ?: continue
            if (current.date != null) continue
            val date = shortDate(row, today)
            if (date != null) {
                current.date = date
            } else if (row.count { it.isLetter() } >= 2 && current.payee.length + row.length <= MAX_PAYEE) {
                // The name running onto a second line: "TRANSPT WALES" / "RAIL".
                current.payee.append(' ').append(row.trim())
            }
        }
        val dated = found.count { it.date != null }
        if (found.size < 2 || dated * 10 < found.size * 6) return emptyList()
        // One cut off at the bottom of the screen has no date; leave it out.
        return found.filter { it.date != null }.map {
            ListedPayment(it.payee.toString().trim(), it.amountMinor, it.date, it.isMoneyIn)
        }
    }

    /**
     * A date as lists show it — "2 Oct", "30 Sept", "Mon 2 Oct", "2 October
     * 2026", "Today", "Yesterday" — with the year worked out when it is left
     * off: the latest one that is not in the future.
     */
    fun shortDate(text: String, today: LocalDate): LocalDate? {
        val lower = text.lowercase().trim()
        if (lower == "today" || lower.startsWith("today,") || lower.startsWith("today ")) return today
        if (lower == "yesterday" || lower.startsWith("yesterday")) return today.minusDays(1)
        val match = SHORT_DATE.find(text) ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val month = MONTHS[match.groupValues[2].lowercase().take(3)] ?: return null
        val year = match.groupValues[3].toIntOrNull()
        if (year != null) return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        val monthDay = runCatching { MonthDay.of(month, day) }.getOrNull() ?: return null
        val thisYear = runCatching { monthDay.atYear(today.year) }.getOrNull() ?: return null
        return if (thisYear.isAfter(today.plusDays(1))) monthDay.atYear(today.year - 1) else thisYear
    }

    private fun stripDate(text: String): String = SHORT_DATE.replace(text, "").trim().trimEnd(',', '·').trim()

    private fun toMinor(text: String): Long? {
        val clean = text.replace(",", "").replace(" ", "")
        val pounds = clean.substringBefore('.').toLongOrNull() ?: return null
        val pence = clean.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: return null
        return (pounds * 100 + pence).takeIf { it > 0 }
    }

    /** An amount at the end of a row: "£30.00", "-£4.20", "+£5.00", "£1,249.99". */
    private val TRAILING_AMOUNT =
        Regex("""([+\-−]?)\s?£\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d{1,6}\.\d{2})\s*$""")

    private val TOTAL = Regex("""\b(total|amount due|balance due|to pay|subtotal)\b""")

    private val SHORT_DATE = Regex(
        """\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:,?\s+(\d{4}))?\b""",
        RegexOption.IGNORE_CASE,
    )

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private const val MAX_PAYEE = 60
}
