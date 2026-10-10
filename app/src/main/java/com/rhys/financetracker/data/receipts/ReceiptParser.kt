package com.rhys.financetracker.data.receipts

import java.time.LocalDate

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
            totalMinor = totalIn(lines, text),
            date = dateIn(lines, today),
            cardEnding = CARD_ENDING.find(text)?.groupValues?.get(1),
        )
    }

    /**
     * The total, by the best evidence there is:
     *
     * 1. a line that says total (not subtotal, not savings);
     * 2. what was paid by card;
     * 3. the subtotal, when nothing came off after it;
     * 4. what the items add up to;
     * 5. the largest amount.
     *
     * A word with no amount beside it takes the amount on its own just under
     * it — or, for the card line, just over it: on a crumpled receipt the
     * amount printed beside "Visa" can come back a row higher.
     */
    internal fun totalIn(lines: List<String>, text: String = lines.joinToString("\n")): Long? {
        fun amountsAt(index: Int): List<Long> =
            lines.getOrNull(index)?.let { line -> AMOUNT.findAll(line).mapNotNull { toMinor(it) }.toList() }.orEmpty()

        fun bareAmountAt(index: Int): List<Long> =
            lines.getOrNull(index)?.takeIf { BARE_AMOUNT.matches(it) }?.let { amountsAt(index) }.orEmpty()

        fun largestBy(keywords: Regex, notThese: Regex?, lookAbove: Boolean): Long? =
            lines.indices.flatMap { index ->
                val lower = lines[index].lowercase()
                if (!keywords.containsMatchIn(lower) || notThese?.containsMatchIn(lower) == true) return@flatMap emptyList()
                amountsAt(index)
                    .ifEmpty { amountsAt(index + 1) }
                    .ifEmpty { if (lookAbove) bareAmountAt(index - 1) else emptyList() }
            }.maxOrNull()

        largestBy(TOTAL_WORDS, NOT_TOTAL, lookAbove = false)?.let { return it }
        largestBy(PAID_WORDS, NOT_TOTAL, lookAbove = true)?.let { return it }
        largestBy(SUBTOTAL_WORDS, null, lookAbove = false)?.let { return it }
        ReceiptItems.itemsIn(text).takeIf { it.size >= 2 }?.sumOf { it.amountMinor }?.takeIf { it > 0 }?.let { return it }
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
                    val date = pattern.read(match, today) ?: continue
                    if (date.year >= 2000 && !date.isAfter(today.plusDays(1))) return date
                }
            }
        }
        return null
    }

    /**
     * The shop. A shop the app knows, named anywhere a price isn't — the logo
     * is a picture, but "B&M Retail Ltd" under it or "Thank you for shopping
     * at Tesco" at the bottom says who it was. Failing that, the company the
     * top lines name ("Smith & Sons Ltd"), then the first top line that reads
     * like a name: not a slogan, an address, a phone number, a time or a
     * greeting.
     */
    internal fun shopIn(lines: List<String>): String? {
        for (line in lines.filterNot { AMOUNT.containsMatchIn(it) }) {
            val known = KnownShops.find(line) ?: continue
            // "ASDA PETROL 4275" says more than "Asda" — and is filed as fuel.
            val plain = line.replace(COMPANY, "").trim()
            val words = plain.split(Regex("""\s+""")).size
            return if (KnownShops.startsWith(plain, known) && words in 2..4 && nameLike(plain, minLetters = 2)) {
                tidy(plain)
            } else {
                known.name
            }
        }
        val top = lines.take(TOP_LINES)
        val candidate = top.firstOrNull { COMPANY.containsMatchIn(it) && nameLike(it.replace(COMPANY, ""), minLetters = 2) }
            ?.replace(COMPANY, "")
            ?: top.firstOrNull { nameLike(it, minLetters = 3) }
            ?: return null
        return tidy(candidate)
    }

    private fun nameLike(line: String, minLetters: Int): Boolean {
        val lower = line.lowercase()
        val letters = line.count { it.isLetter() }
        return letters >= minLetters &&
            letters >= line.count { it.isDigit() } &&
            !NOT_A_NAME.containsMatchIn(lower) &&
            !SLOGAN.containsMatchIn(lower) &&
            !AMOUNT.containsMatchIn(line) &&
            DATES.none { it.regex.containsMatchIn(line) }
    }

    private fun tidy(name: String): String? {
        // "Amazon.co.uk" is Amazon.
        val cleaned = name.trim().replace(WEB_ENDING, "").trim('*', '-', '=', '#', '.', ',', ' ')
        if (cleaned.length < 2) return null
        // "TESCO STORES" and "costa coffee" read better as "Tesco Stores" and "Costa Coffee".
        return if (cleaned == cleaned.uppercase() || cleaned == cleaned.lowercase()) {
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

    /**
     * Receipt amounts always show pence: 23.99, £1,234.50, 4.20 GBP. A total
     * printed big and spaced out can come back as "£35. 13".
     */
    private val AMOUNT = Regex("""(?<![\d.])£?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d{1,5}\.\s?\d{2})(?![\d])""")

    /** A line that is only an amount: "£35.13", "35.13 GBP". */
    private val BARE_AMOUNT = Regex("""^[£E]?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d{1,5}\.\s?\d{2})\s*(gbp)?$""", RegexOption.IGNORE_CASE)

    private val SUBTOTAL_WORDS = Regex("""\b(sub ?-?total|subtotal)\b""")

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
        """\b(road|rd|street|st|lane|ln|avenue|ave|way|close|drive|park|unit|tel|telephone|phone|vat|reg|receipt|welcome|thank|www|http|store no|till|cashier|served by|order|orders|ordered|invoice|date|time|details|voucher)\b|\d{1,2}:\d{2}|@""",
    )

    /** The line a shop prints under its logo: "Big Brands Big Savings", "Every little helps". */
    private val SLOGAN = Regex("""\b(savings|prices|brands|helps|for less)\b""")

    /** A company's name as receipts print it: "B&M Retail Ltd", "Smith & Sons Limited". */
    private val COMPANY = Regex("""\s+(retail\s+)?(stores\s+)?(uk\s+)?(ltd|limited|plc)\.?\s*$""", RegexOption.IGNORE_CASE)

    private val WEB_ENDING = Regex("""\.(co\.uk|com|uk|org|net)$""", RegexOption.IGNORE_CASE)

    private const val TOP_LINES = 6
    private const val MAX_SHOP = 60

    private class DatePattern(val regex: Regex, val read: (MatchResult, LocalDate) -> LocalDate?)

    private val DATES = listOf(
        // 03/10/2026, 03-10-26, 3.10.2026 — UK order, day first.
        DatePattern(Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2}|\d{4})\b""")) { m, _ ->
            val (d, mo, y) = m.destructured
            val year = y.toInt().let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull()
        },
        // 3 Oct 2026, 30 Sept 2026, 2 Oct (this year, or last if that is ahead).
        DatePattern(Regex("""\b\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]{3,9}\.?(?:,?\s+\d{4})?\b""")) { m, today ->
            ScreenText.shortDate(m.value, today)
        },
        // 2026-10-03.
        DatePattern(Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")) { m, _ ->
            val (y, mo, d) = m.destructured
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        },
    )
}
