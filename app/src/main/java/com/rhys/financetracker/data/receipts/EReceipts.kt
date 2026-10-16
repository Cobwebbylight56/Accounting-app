package com.rhys.financetracker.data.receipts

/**
 * Receipts that arrive as words rather than paper: an order confirmation
 * from an email, a website's receipt page, an invoice saved as a PDF.
 *
 * They come in through the share sheet — the words selected in Gmail or
 * Chrome, or the PDF itself — and go the same way as a photo of a till
 * receipt: kept with the payment they were for, or added as a new one.
 */
object EReceipts {

    /**
     * True when a PDF's words read like a receipt, an order or an invoice
     * rather than a bank statement — so it is kept as a receipt instead of
     * being read for rows of payments.
     */
    fun looksLikeReceipt(text: String): Boolean {
        val lower = " " + text.lowercase().replace(SPACES, " ") + " "
        if (STATEMENT.count { it.containsMatchIn(lower) } >= 1) return false
        val receiptSigns = RECEIPT.count { it.containsMatchIn(lower) }
        return receiptSigns >= 2 && ReceiptParser.totalIn(text.lines().map { it.trim() }.filter { it.isNotEmpty() }) != null
    }

    /**
     * A web address, perhaps under the page's title, with no amount: the
     * page was shared, not its words.
     */
    fun isJustALink(text: String): Boolean {
        val words = text.trim().split(SPACES).filter { it.isNotEmpty() }
        if (words.none { LINK.matches(it) }) return false
        return !AMOUNT.containsMatchIn(words.filterNot { LINK.matches(it) }.joinToString(" "))
    }

    /**
     * What is kept and read for a receipt shared as words: the email's
     * subject first, when the app sharing it gives one — "Your Amazon.co.uk
     * order" names the shop when the words selected don't.
     */
    fun compose(subject: String?, text: String): String =
        listOfNotNull(subject?.trim()?.takeIf { it.isNotEmpty() && it !in text }, text.trim())
            .joinToString("\n")
            .take(MAX_CHARS)

    /** As much of a shared receipt as is kept: plenty for any order, and a picture that stays readable. */
    const val MAX_CHARS = 6_000

    private val SPACES = Regex("""\s+""")
    private val LINK = Regex("""(?i)^(https?://|www\.)\S+$""")
    private val AMOUNT = Regex("""\d\.\d{2}\b""")

    /** What only a bank statement says. */
    private val STATEMENT = listOf(
        Regex("""\bsort code\b"""),
        Regex("""\b(balance brought forward|brought forward|carried forward)\b"""),
        Regex("""\b(opening|closing|previous|new) balance\b"""),
        Regex("""\bbalance on \d"""),
        Regex("""\bstatement (date|period|number)\b"""),
        Regex("""\b(money in|money out|paid in|paid out)\b"""),
        Regex("""\b(minimum payment|credit limit)\b"""),
        Regex("""\byour transactions\b"""),
    )

    /** What a receipt, an order or an invoice says. */
    private val RECEIPT = listOf(
        Regex("""\b(order total|grand total|total paid|amount paid|you paid|total charged|amount charged)\b"""),
        Regex("""\b(sub ?-?total)\b"""),
        Regex("""\b(order (number|no|ref|reference|id|#|confirmation|summary|details)|your order|thank(s| you) for (your )?(order|shopping|your purchase))\b"""),
        Regex("""\b(invoice|receipt|tax invoice|vat invoice)\b"""),
        Regex("""\b(qty|quantity)\b"""),
        Regex("""\b(delivery|dispatch(ed)?|shipping|postage|collection)\b"""),
        Regex("""\b(payment method|billing address|paid with|paid by)\b"""),
    )
}
