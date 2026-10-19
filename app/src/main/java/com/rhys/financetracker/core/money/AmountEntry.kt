package com.rhys.financetracker.core.money

/**
 * Typing an amount the way a till takes it: the digits fill in from the
 * pence, and the comma and the point go in by themselves — 5, 0, 0 reads
 * 5.00 and 1, 2, 3, 4, 5, 6 reads 1,234.56. A minus makes it negative, and a
 * second takes it off again.
 *
 * The form keeps plain pounds ("1234.56"), as it always has; only what the
 * field shows is laid out.
 */
object AmountEntry {

    /** How the form's [pounds] read in the field: "1,234.56", "-0.05"; empty for nothing yet. */
    fun shown(pounds: String): String {
        if (pounds.trim() == "-") return "-"
        val minor = Money.parseOrNull(pounds) ?: return ""
        val sign = if (minor < 0) "-" else ""
        val whole = (kotlin.math.abs(minor) / 100).toString()
            .reversed().chunked(3).joinToString(",").reversed()
        val pence = (kotlin.math.abs(minor) % 100).toString().padStart(2, '0')
        return "$sign$whole.$pence"
    }

    /**
     * The plain pounds for the form, from the field's text once a key has
     * been pressed: a digit added at the end, the last one taken off, or
     * something pasted in.
     */
    fun typed(text: String): String {
        val negative = text.count { it == '-' } % 2 == 1
        val digits = text.filter { it.isDigit() }.trimStart('0').take(MAX_DIGITS)
        val sign = if (negative) "-" else ""
        if (digits.isEmpty()) return sign
        return sign + Money.formatPlain(digits.toLong())
    }

    /** Up to £9,999,999,999.99: more than anyone types, and well inside a Long. */
    private const val MAX_DIGITS = 12
}
