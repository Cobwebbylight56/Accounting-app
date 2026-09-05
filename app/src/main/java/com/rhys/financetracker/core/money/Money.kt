package com.rhys.financetracker.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

/**
 * Money is stored throughout the application as a [Long] number of **minor
 * units** (pence for GBP).  Never use [Double] or [Float] for money: binary
 * floating point cannot represent 0.10 exactly and rounding errors accumulate
 * across thousands of transactions.
 *
 * The helpers below are the single place where the conversion between the
 * stored representation and user-facing text happens.
 */
object Money {

    /** Number of minor units in one major unit for the currencies we support. */
    const val MINOR_UNITS_PER_MAJOR: Long = 100L

    /** Default currency; overridable in Settings, never hard-coded at call sites. */
    const val DEFAULT_CURRENCY_CODE: String = "GBP"

    /**
     * Formats [minorUnits] using the supplied currency and locale, e.g.
     * `123456` -> `£1,234.56`.
     *
     * @param showSign when true a positive value is prefixed with `+`.
     */
    fun format(
        minorUnits: Long,
        currencyCode: String = DEFAULT_CURRENCY_CODE,
        locale: Locale = Locale.UK,
        showSign: Boolean = false,
    ): String {
        val formatter = NumberFormat.getCurrencyInstance(locale).apply {
            runCatching { currency = Currency.getInstance(currencyCode) }
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }
        val text = formatter.format(toBigDecimal(minorUnits))
        return if (showSign && minorUnits > 0) "+$text" else text
    }

    /**
     * Formats without the currency symbol, for CSV/Excel export where a raw
     * number is wanted, e.g. `123456` -> `1234.56`.
     */
    fun formatPlain(minorUnits: Long): String =
        toBigDecimal(minorUnits).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** Compact form used on charts and dense cards, e.g. `£1.2k`. */
    fun formatCompact(minorUnits: Long, currencyCode: String = DEFAULT_CURRENCY_CODE): String {
        val symbol = runCatching { Currency.getInstance(currencyCode).getSymbol(Locale.UK) }
            .getOrDefault("")
        val major = minorUnits / MINOR_UNITS_PER_MAJOR
        val sign = if (major < 0) "-" else ""
        val magnitude = abs(major)
        return when {
            magnitude >= 1_000_000 -> "$sign$symbol%.1fm".format(magnitude / 1_000_000.0)
            magnitude >= 1_000 -> "$sign$symbol%.1fk".format(magnitude / 1_000.0)
            else -> "$sign$symbol$magnitude"
        }
    }

    fun toBigDecimal(minorUnits: Long): BigDecimal =
        BigDecimal.valueOf(minorUnits, 2)

    /**
     * Parses free-form user input into minor units.  Accepts values such as
     * `1234.56`, `£1,234.56`, `1 234,56`, `(12.30)` (negative) and `-12.30`.
     *
     * @return the parsed value, or `null` when the text is not a valid amount.
     */
    fun parseOrNull(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()

        // Accountancy style negatives: (12.30)
        var negative = false
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true
            text = text.substring(1, text.length - 1)
        }

        // Strip currency symbols, spaces and non-breaking spaces.
        text = text.filter { it.isDigit() || it == '.' || it == ',' || it == '-' || it == '+' }
        if (text.startsWith("-")) {
            negative = !negative
            text = text.drop(1)
        } else if (text.startsWith("+")) {
            text = text.drop(1)
        }
        if (text.isEmpty()) return null

        // Decide which separator is the decimal point.  Whichever appears last
        // and is followed by one or two digits wins; the other is a grouping mark.
        val lastDot = text.lastIndexOf('.')
        val lastComma = text.lastIndexOf(',')
        val decimalIndex = maxOf(lastDot, lastComma)
        val normalised = if (decimalIndex >= 0 && text.length - decimalIndex - 1 in 1..2) {
            val whole = text.substring(0, decimalIndex).filter { it.isDigit() }
            val fraction = text.substring(decimalIndex + 1).filter { it.isDigit() }
            "${whole.ifEmpty { "0" }}.$fraction"
        } else {
            text.filter { it.isDigit() }.ifEmpty { return null }
        }

        val decimal = runCatching { BigDecimal(normalised) }.getOrNull() ?: return null
        val minor = decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
        return if (negative) -minor else minor
    }

    /** Converts major units (as typed by a user) to minor units. */
    fun fromMajor(major: Double): Long =
        BigDecimal.valueOf(major).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    /**
     * Applies a percentage to an amount, rounding half-up, e.g. VAT or an
     * interest rate.  [percent] is expressed as a normal percentage (5.0 = 5 %).
     */
    fun percentOf(minorUnits: Long, percent: Double): Long =
        BigDecimal.valueOf(minorUnits)
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .toLong()

    /**
     * Splits [minorUnits] into [parts] shares without losing or inventing a
     * penny; the remainder is distributed one penny at a time from the start.
     */
    fun split(minorUnits: Long, parts: Int): List<Long> {
        require(parts > 0) { "parts must be positive" }
        val base = minorUnits / parts
        var remainder = minorUnits - base * parts
        val step = if (remainder < 0) -1L else 1L
        return List(parts) {
            if (remainder != 0L) {
                remainder -= step
                base + step
            } else {
                base
            }
        }
    }
}
