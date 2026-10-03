package com.rhys.financetracker.data.receipts

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Pulls the shop, the total and the date out of the text read off a receipt
 * or a screenshot of one.
 *
 * Receipts are laid out every way there is, and the text comes back from the
 * camera with its share of misreadings, so this looks for what nearly every
 * receipt has — a line saying TOTAL (or "amount due", "to pay", "order
 * total") with an amount on it or just under it, a date, and the shop's name
 * near the top — and leaves anything it cannot find for the user to type.
 */
object ReceiptParser {

    data class Reading(
        val shop: String?,
        val totalMinor: Long?,
        val date: LocalDate?,
        /** The card's last four digits, when the receipt prints them. */
        val cardEnding: String?,
    )

    fun parse(text: String, today: LocalDate = LocalDate.now()): Reading {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return Reading(
            shop = shopIn(lines),
            totalMinor = totalIn(lines),
            date = dateIn(lines, today),
            cardEnding = CARD_ENDING.find(text)?.groupValues?.get(1),
        )
    }

    /**
     * The total: the largest amount on a line that says total (not subtotal,
     * not savings), or on the line after one that says it with nothing on it;
     * failing that, what was paid by card; failing that, the largest amount.
     */
    internal fun totalIn(lines: List<String>): Long? {
        fun amountsAt(index: Int): List<Long> =
            lines.getOrNull(index)?.let { line -> AMOUNT.findAll(line).mapNotNull { toMinor(it) }.toList() }.orEmpty()

        for (keywords in listOf(TOTAL_WORDS, PAID_WORDS)) {
            val found = lines.indices.flatMap { index ->
                val lower = lines[index].lowercase()
                if (!keywords.containsMatchIn(lower) || NOT_TOTAL.containsMatchIn(lower)) return@flatMap emptyList()
                amountsAt(index).ifEmpty { amountsAt(index + 1) }
            }
            found.maxOrNull()?.let { return it }
        }
        return lines
            .filterNot { NOT_TOTAL.containsMatchIn(it.lowercase()) }
            .flatMap { line -> AMOUNT.findAll(line).mapNotNull { toMinor(it) }.toList() }
            .maxOrNull()
    }

    /** The first real date on it, not in the future and not before 2000. */
    internal fun dateIn(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            for (pattern in DATES) {
                for (match in pattern.regex.findAll(line)) {
                    val date = pattern.read(match) ?: continue
                    if (date.year >= 2000 && !date.isAfter(today.plusDays(1))) return date
                }
            }
        }
        return null
    }

    /**
     * The shop: the first of the top lines that reads like a name — letters,
     * not an address, a phone number, a VAT number, a time or a greeting.
     */
    internal fun shopIn(lines: List<String>): String? {
        val candidate = lines.take(TOP_LINES).firstOrNull { line ->
            val lower = line.lowercase()
            val letters = line.count { it.isLetter() }
            letters >= 3 &&
                letters >= line.count { it.isDigit() } &&
                !NOT_A_NAME.containsMatchIn(lower) &&
                !AMOUNT.containsMatchIn(line) &&
                DATES.none { it.regex.containsMatchIn(line) }
        } ?: return null
        // "Amazon.co.uk" is Amazon.
        val cleaned = candidate.trim().replace(WEB_ENDING, "").trim('*', '-', '=', '#', '.', ' ')
        if (cleaned.length < 3) return null
        // "TESCO STORES" reads better as "Tesco Stores".
        return if (cleaned == cleaned.uppercase()) {
            cleaned.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        } else {
            cleaned
        }.take(MAX_SHOP)
    }

    private fun toMinor(match: MatchResult): Long? {
        val text = match.groupValues[1].replace(",", "").replace(" ", "")
        val pounds = text.substringBefore('.').toLongOrNull() ?: return null
        val pence = text.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: return null
        return (pounds * 100 + pence).takeIf { it > 0 }
    }

    /** Receipt amounts always show pence: 23.99, £1,234.50, 4.20 GBP. */
    private val AMOUNT = Regex("""(?<![\d.])£?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d{1,5}\.\d{2})(?![\d])""")

    private val TOTAL_WORDS = Regex(
        """\b(total|amount due|balance due|to pay|amount to pay|grand total|order total|you paid|amount paid|total paid|total charged)\b""",
    )
    private val PAID_WORDS = Regex("""\b(visa|mastercard|maestro|amex|debit card|credit card|card payment|contactless|paid by card|card sale|sale)\b""")

    /** Lines that look like a total but are not the one paid. */
    private val NOT_TOTAL = Regex(
        """\b(sub ?-?total|subtotal|savings|saving|discount|you saved|vat|tax|items?|qty|points|change|cash ?back|tip|before|rrp|was)\b""",
    )

    private val CARD_ENDING = Regex("""(?:\*{2,}|x{3,}|X{3,}|•+|ending(?: in)?)\s?(\d{4})\b""")

    private val NOT_A_NAME = Regex(
        """\b(road|rd|street|st|lane|ln|avenue|ave|way|close|drive|park|retail|tel|telephone|phone|vat|reg|receipt|welcome|thank|www|http|store no|till|cashier|served by|order|orders|ordered|invoice|date|time|details)\b|\d{1,2}:\d{2}|@""",
    )

    private val WEB_ENDING = Regex("""\.(co\.uk|com|uk|org|net)$""", RegexOption.IGNORE_CASE)

    private const val TOP_LINES = 6
    private const val MAX_SHOP = 60

    private class DatePattern(val regex: Regex, val read: (MatchResult) -> LocalDate?)

    private val MONTH_NAME: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("d MMM uuuu")
        .toFormatter(Locale.UK)

    private val DATES = listOf(
        // 03/10/2026, 03-10-26, 3.10.2026 — UK order, day first.
        DatePattern(Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2}|\d{4})\b""")) { m ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        },
        // 3 Oct 2026, 03 October 2026.
        DatePattern(Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})\b""")) { m ->
            val (d, mo, y) = m.destructured
            runCatching { LocalDate.parse("$d ${mo.take(3)} $y", MONTH_NAME) }.getOrNull()
        },
        // 2026-10-03.
        DatePattern(Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")) { m ->
            val (y, mo, d) = m.destructured
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        },
    )
}
