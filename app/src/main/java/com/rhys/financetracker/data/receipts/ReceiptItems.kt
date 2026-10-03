package com.rhys.financetracker.data.receipts

/**
 * The items on a receipt, each with what it cost: what a payment can be
 * split into.
 *
 * Read from the receipt's rows (see ScreenText.rowText) down to the TOTAL.
 * An item is a row with words and a price at the end; a discount under it
 * ("Clubcard Price -0.50") comes off it; a quantity row under a name
 * ("2 @ 1.25  2.50", "32.45L @ 139.9p  45.40") prices the name above. Rows
 * about paying — VAT, card, change — are not items.
 */
object ReceiptItems {

    data class Item(val name: String, val amountMinor: Long)

    fun itemsIn(text: String): List<Item> {
        val items = mutableListOf<Item>()
        var pendingName: String? = null
        for (raw in text.lines()) {
            val row = raw.trim()
            if (row.isEmpty()) continue
            val lower = row.lowercase()
            if (STOP.containsMatchIn(lower) && !SAVING.containsMatchIn(lower)) break

            val price = PRICE.find(row)
            if (price == null) {
                // A name whose price is on the quantity row under it.
                pendingName = row.takeIf { it.count(Char::isLetter) >= 3 && !NOT_ITEM.containsMatchIn(lower) }
                continue
            }
            val minor = toMinor(price.groupValues[2]) ?: continue
            val negative = price.groupValues[1].isNotEmpty() || price.groupValues[3] == "-"
            val name = row.substring(0, price.range.first).trim().trimEnd('£', ' ', '-')

            if (negative || SAVING.containsMatchIn(lower)) {
                // A discount: comes off the item above it.
                val last = items.removeLastOrNull()
                if (last != null) items += last.copy(amountMinor = (last.amountMinor - minor).coerceAtLeast(0L))
                pendingName = null
                continue
            }
            if (NOT_ITEM.containsMatchIn(lower)) {
                pendingName = null
                continue
            }
            val itemName = when {
                QUANTITY.containsMatchIn(name) && pendingName != null -> pendingName
                name.count(Char::isLetter) >= 2 -> name
                pendingName != null -> pendingName
                else -> null
            }
            pendingName = null
            if (itemName != null && minor > 0L) items += Item(clean(itemName), minor)
        }
        return items
    }

    private fun clean(name: String): String =
        name.replace(Regex("""\s{2,}"""), " ").trim().trim('*', '-', '.').trim()

    private fun toMinor(text: String): Long? {
        val clean = text.replace(",", "")
        val pounds = clean.substringBefore('.').toLongOrNull() ?: return null
        val pence = clean.substringAfter('.', "").padEnd(2, '0').take(2).toLongOrNull() ?: return null
        return pounds * 100 + pence
    }

    /** A price at the end of a row: "1.65", "£1.65", "1.65 A" (a VAT code), "-0.50", "0.50-". */
    private val PRICE = Regex("""(-|−)?\s?£?\s?(\d{1,4}(?:,\d{3})*\.\d{2})\s*(-)?\s*(?:[A-Z*]{1,2})?\s*$""")

    /** Where the items end. */
    private val STOP = Regex("""\b(sub ?-?total|total|balance due|amount due|to pay|amount to pay)\b""")

    /** Discounts and savings, which come off the item above. */
    private val SAVING = Regex("""\b(saving|savings|discount|clubcard price|cc price|nectar price|rollback|offer|promo|reduced|multibuy|meal deal saving)\b""")

    /** Rows about paying, not about what was bought. */
    private val NOT_ITEM = Regex(
        """\b(vat|change|cash|card|visa|mastercard|maestro|contactless|amex|tel|phone|points|clubcard|nectar|balance|auth|merchant|terminal|aid|pan|receipt|store|till|op|served|www)\b""",
    )

    /** "2 @ 1.25", "3 x", "32.45L @ 139.9p". */
    private val QUANTITY = Regex("""^\d+(?:\.\d+)?\s*(?:l|ltr|litres?|kg|g)?\s*(?:@|x)""", RegexOption.IGNORE_CASE)
}
