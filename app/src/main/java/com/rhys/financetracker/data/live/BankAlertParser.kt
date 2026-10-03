package com.rhys.financetracker.data.live

import com.rhys.financetracker.domain.model.TransactionType

/**
 * Reads a banking app's alert — "You spent £12.50 at TESCO STORES 3012",
 * "£250.00 received from H PAYNE" — into a payment.
 *
 * Every bank words its alerts differently and changes the wording now and
 * then, so nothing here is tied to one bank's sentence. It looks for the
 * pieces any payment alert has: an amount in pounds, a word that says which
 * way the money went, and who it went to or came from.
 *
 * It errs towards reading nothing. A missed alert is filled in by the
 * statement later; a wrongly read one — a security code taken for a payment,
 * a bill reminder taken for the bill — puts money on Home that never moved.
 */
object BankAlertParser {

    data class Alert(
        val amountMinor: Long,
        val type: TransactionType,
        /** Who it went to or came from, as the alert names them; never blank. */
        val payee: String,
        /** The last four digits of the card or account, when the alert gives them. */
        val ending: String?,
        /**
         * What sort of borrowing the money went to, when the alert says it
         * went to one — "mortgage", "loan" or "credit card" — as with an
         * overpayment. Null for an ordinary payment.
         */
        val toBorrowing: String? = null,
    )

    /**
     * The payment in an alert with this [title] and [text], or null when it
     * is not one.
     *
     * [assumeSpending] is for Google Wallet and the like, whose alert for a
     * tap is only the shop and "£12.50 with Visa •••• 1234": every alert from
     * them with an amount is a card payment unless it says otherwise.
     */
    fun parse(title: String?, text: String?, assumeSpending: Boolean = false): Alert? {
        val body = text.orEmpty().trim()
        val heading = title.orEmpty().trim()
        val all = listOf(heading, body).filter { it.isNotEmpty() }.joinToString(". ").replace(SPACES, " ")
        if (all.isEmpty()) return null
        val lower = all.lowercase()
        if (NOT_A_PAYMENT.any { it.containsMatchIn(lower) }) return null

        val amount = amountIn(all) ?: return null
        val type = directionOf(lower) ?: if (assumeSpending) TransactionType.EXPENSE else return null
        val payee = payeeIn(body, type) ?: payeeIn(all, type) ?: titleAsPayee(heading)
            ?: if (type == TransactionType.INCOME) MONEY_IN_NAME else CARD_PAYMENT_NAME
        val ending = ENDING.find(all)?.groupValues?.get(1)
        val borrowing = if (type == TransactionType.EXPENSE) borrowingIn(lower) else null
        val named = if (borrowing != null && payee in FALLBACK_NAMES) {
            (if (OVERPAY.containsMatchIn(lower)) "Overpayment to " else "Payment to ") + borrowing
        } else {
            payee
        }
        return Alert(amount, type, named, ending, borrowing)
    }

    /**
     * True for an alert saying a statement is ready to look at — no money
     * moved, but it is the moment to import it.
     */
    fun isStatementReady(title: String?, text: String?): Boolean {
        val lower = listOfNotNull(title, text).joinToString(". ").lowercase()
        return STATEMENT_READY.containsMatchIn(lower)
    }

    /** "mortgage", "loan" or "credit card" when the money went to paying one off. */
    internal fun borrowingIn(lower: String): String? {
        val overpaid = OVERPAY.containsMatchIn(lower)
        val toIt = Regex("""\b(to|towards|into|off) (your|the) (\w+ )?(mortgage|loan|credit card|card balance)\b""").containsMatchIn(lower)
        if (!overpaid && !toIt) return null
        return when {
            "mortgage" in lower -> "mortgage"
            "credit card" in lower || "card balance" in lower -> "credit card"
            "loan" in lower -> "loan"
            else -> if (overpaid) "loan" else null
        }
    }

    /**
     * The payment's amount: the first sum of money that is not a balance, an
     * available figure or a limit — "£12.50 spent. Your balance is £840.10"
     * is a £12.50 payment.
     */
    internal fun amountIn(text: String): Long? {
        for (match in MONEY.findAll(text)) {
            val before = text.substring(maxOf(0, match.range.first - LOOK_BACK), match.range.first).lowercase()
            if (NOT_THE_PAYMENT.any { it in before }) continue
            val digits = (match.groups[1]?.value ?: match.groups[2]?.value ?: continue).replace(",", "")
            val pounds = digits.substringBefore('.')
            val pence = digits.substringAfter('.', "").padEnd(2, '0').take(2)
            val minor = (pounds.toLongOrNull() ?: continue) * 100 + (pence.toLongOrNull() ?: 0L)
            if (minor > 0L) return minor
        }
        return null
    }

    /** Which way the money went, or null when the alert does not say. */
    internal fun directionOf(lower: String): TransactionType? = when {
        MONEY_IN.any { it.containsMatchIn(lower) } -> TransactionType.INCOME
        MONEY_OUT.any { it.containsMatchIn(lower) } -> TransactionType.EXPENSE
        else -> null
    }

    /** The name after "at", "to" or "from" — whichever fits the direction. */
    private fun payeeIn(text: String, type: TransactionType): String? {
        if (type == TransactionType.INCOME) {
            // "H PAYNE paid you £20.00"
            PAID_YOU.find(text)?.let { match -> cleanPayee(match.groupValues[1].substringAfterLast(". "))?.let { return it } }
        }
        val words = if (type == TransactionType.INCOME) listOf("from", "by") else listOf("at", "to", "with", "from")
        for (word in words) {
            for (match in Regex("""\b$word\s+""", RegexOption.IGNORE_CASE).findAll(text)) {
                cleanPayee(text.substring(match.range.last + 1))?.let { return it }
            }
        }
        return null
    }

    /** Up to the end of the name: a full stop, a comma, or words that start the next part. */
    private fun cleanPayee(raw: String): String? {
        var name = raw
        STOPS.forEach { stop -> stop.find(name)?.let { name = name.substring(0, it.range.first) } }
        name = name.trim().trimEnd('.', ',', '!', ':', ';', '-', '—').trim()
        if (name.isEmpty() || name.length > MAX_PAYEE) return null
        val lower = name.lowercase()
        if (lower.startsWith("your ") || lower == "you" || lower == "your" || !lower.first().isLetter()) return null
        // "with Visa •••• 1234", "with Apple Pay": how it was paid, not who to.
        if (lower.split(' ').first() in HOW_PAID) return null
        return name
    }

    /**
     * Google Wallet and some banks put the shop in the title and only the
     * amount in the text. A title that is just the bank or "Payment" names
     * nobody.
     */
    private fun titleAsPayee(title: String): String? {
        if (title.isBlank() || title.length > MAX_PAYEE) return null
        val words = title.lowercase().split(Regex("""[^a-z0-9£]+""")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.all { it in GENERIC_TITLE_WORDS || it.startsWith("£") }) return null
        if (MONEY.containsMatchIn(title)) return null
        return title.trim()
    }

    private val SPACES = Regex("""\s+""")

    /** £12.50, £1,250, GBP 12.50, 12.50 GBP. */
    private val MONEY = Regex("""£\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)|\bGBP\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)""")

    private val PAID_YOU = Regex("""^(.+?)\s+(?:has\s+)?(?:paid|sent)\s+you\b""", RegexOption.IGNORE_CASE)

    private const val LOOK_BACK = 28

    /** Words just before a sum that make it something other than the payment. */
    private val NOT_THE_PAYMENT = listOf("balance", "available", "limit", "overdraft", "remaining", "left to spend")

    /** Alerts that mention money but are not a payment that has happened. */
    private val NOT_A_PAYMENT = listOf(
        Regex("""\b(declined|unsuccessful|failed|rejected|couldn't|could not|wasn't|was not|not been|cancelled)\b"""),
        Regex("""\b(code|passcode|one-time|otp|verify|verification|confirm it's you|log ?in|logged in|sign in|approve)\b"""),
        Regex("""\b(will be|is due|are due|due on|due tomorrow|upcoming|scheduled|reminder|going to|tomorrow|requested|requesting|request)\b"""),
        Regex("""\b(statement is ready|balance update|daily balance|low balance)\b"""),
    )

    private val MONEY_IN = listOf(
        Regex("""\b(received|paid in|paid into|credited|money in|came in|deposit(ed)?|refund(ed)?|has arrived|added to your account)\b"""),
        Regex("""\b(paid you|sent you)\b"""),
    )

    private val MONEY_OUT = listOf(
        Regex("""\b(spent|spend|paid|payment|purchase|went out|gone out|left your account|debited|sent|taken|withdraw(al|n)?|cash machine|charged|transaction)\b"""),
        // Confirmations from shops and payment apps, and overpayments.
        Regex("""\b(order|ordered|bought|booked|booking|receipt|overpa(y|id|yment|yments))\b"""),
    )

    private val OVERPAY = Regex("""\bover-?pa(y|id|ying|yment|yments)\b""")

    private val STATEMENT_READY = Regex(
        """\b(statement|e-?statement)s? (is |are )?(now )?(ready|available)|\bnew (e-?)?statement\b|\bstatement has arrived\b""",
    )

    private const val MONEY_IN_NAME = "Money in"
    private const val CARD_PAYMENT_NAME = "Card payment"
    private val FALLBACK_NAMES = setOf(MONEY_IN_NAME, CARD_PAYMENT_NAME)

    /** First words of "with …" that say how something was paid. */
    private val HOW_PAID = setOf(
        "visa", "mastercard", "debit", "credit", "card", "apple", "google", "samsung", "contactless",
        "your", "klarna", "clearpay", "paypal", "amex", "maestro",
    )

    /** Where a payee's name ends. */
    private val STOPS = listOf(
        Regex("""[.,;!](\s|$)"""),
        Regex("""\s(on|using|with|from your|to your|via|by card|by contactless|for £|was|has|have|is)\b.*""", RegexOption.IGNORE_CASE),
        Regex("""\s(card|account)\s+ending\b.*""", RegexOption.IGNORE_CASE),
        Regex("""\s£.*"""),
        Regex("""\sat\s+\d.*"""),
    )

    private val ENDING = Regex("""(?:ending(?: in)?|\*{2,}|•+|x{2,}|\.{3})\s?(\d{4})\b""", RegexOption.IGNORE_CASE)

    private val GENERIC_TITLE_WORDS = setOf(
        "payment", "payments", "card", "debit", "credit", "transaction", "transactions", "money", "in", "out",
        "spent", "spending", "purchase", "alert", "alerts", "notification", "new", "your", "account", "you",
        "nationwide", "lloyds", "halifax", "barclays", "hsbc", "natwest", "santander", "monzo", "starling",
        "revolut", "chase", "bank", "banking", "received", "sent", "contactless", "online",
        "order", "orders", "confirmed", "placed", "thanks", "thank", "receipt", "booking", "update",
        "google", "wallet", "samsung", "pay", "gpay",
    )

    private const val MAX_PAYEE = 60
}
