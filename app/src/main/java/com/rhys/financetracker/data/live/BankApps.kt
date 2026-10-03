package com.rhys.financetracker.data.live

/**
 * Which apps' alerts are read, and which account a payment from one goes to.
 *
 * Only the banking apps below are read without asking. Anything else whose
 * alert happens to read like a payment is offered on the Live payments page,
 * where it can be allowed — but never read until it is.
 */
object BankApps {

    /** Banking and card apps, by package name, with the bank's name. */
    val KNOWN: Map<String, String> = mapOf(
        "co.uk.Nationwide.Mobile" to "Nationwide",
        "com.grppl.android.shell.CMBlloydsTSB73" to "Lloyds",
        "com.grppl.android.shell.halifax" to "Halifax",
        "com.grppl.android.shell.BOS" to "Bank of Scotland",
        "com.barclays.android.barclaysmobilebanking" to "Barclays",
        "uk.co.hsbc.hsbcukmobilebanking" to "HSBC",
        "com.firstdirect.bankingonthego" to "first direct",
        "com.rbs.mobile.android.natwest" to "NatWest",
        "com.rbs.mobile.android.rbs" to "RBS",
        "uk.co.santander.santanderUK" to "Santander",
        "co.uk.getmondo" to "Monzo",
        "com.starlingbank.android" to "Starling",
        "com.revolut.revolut" to "Revolut",
        "com.chase.intl" to "Chase",
        "com.americanexpress.android.acctsvcs.uk" to "American Express",
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.samsung.android.spay" to "Samsung Wallet",
        // Payment apps: their confirmations are payments too.
        "com.paypal.android.p2pmobile" to "PayPal",
        "com.myklarnamobile" to "Klarna",
        "com.imaginecurve.curve.prd" to "Curve",
    )

    /**
     * Apps that tell of a payment the bank may tell of too: a PayPal or
     * Klarna purchase, a Google Wallet tap. Within a day, the same amount
     * from one of these and from another app is taken as one payment.
     */
    val ALSO_TOLD_BY_BANK: Set<String> = setOf(
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.paypal.android.p2pmobile",
        "com.myklarnamobile", "com.imaginecurve.curve.prd",
    )

    /**
     * Phone wallets: an alert from one is a card tapped in a shop, worded as
     * little as "£12.50 with Visa •••• 1234" under the shop's name. They say
     * which card, not which bank, so the card's last four digits decide the
     * account.
     */
    val CARD_TAP_APPS: Set<String> = setOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay")

    /**
     * Never read, even if allowed: messages and email can mention money
     * ("I sent you £20") without any having moved.
     */
    val NEVER: Set<String> = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.facebook.orca", "org.telegram.messenger",
        "org.thoughtcrime.securesms", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.google.android.gm", "com.microsoft.office.outlook", "com.snapchat.android",
        "com.instagram.android", "com.discord", "com.facebook.katana",
    )

    /** A loan, mortgage, card or pay-later account, for paying off. */
    data class BorrowingOption(
        val id: Long,
        val name: String,
        /** "mortgage", "loan", "credit card" or "pay later". */
        val kind: String,
    )

    /**
     * The borrowing a payment pays off, or null when it is ordinary spending:
     * the one of [kind] the alert named ("overpayment to your mortgage"), or
     * one named after the lender the payee names ("KLARNA", "AMERICAN
     * EXPRESS"). Only lenders count: spending at Tesco is not paying off a
     * Tesco credit card. Never [fromAccountId] itself.
     */
    fun pickBorrowing(
        kind: String?,
        payee: String,
        borrowing: List<BorrowingOption>,
        fromAccountId: Long?,
    ): Long? {
        val options = borrowing.filter { it.id != fromAccountId }
        if (kind != null) {
            options.firstOrNull { it.kind == kind && kind in it.name.lowercase() }?.let { return it.id }
            options.firstOrNull { it.kind == kind }?.let { return it.id }
        }
        val lenders = wordsOf(payee).filter { it in LENDERS }
        if (lenders.isEmpty()) return null
        return options.firstOrNull { option -> wordsOf(option.name).any { it in lenders } }?.id
    }

    private fun wordsOf(text: String): Set<String> =
        text.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }.toSet()

    /** Card, pay-later and loan companies, as they appear in a payee or account name. */
    private val LENDERS = setOf(
        "klarna", "clearpay", "laybuy", "zilch", "barclaycard", "amex", "american", "capital", "vanquis",
        "aqua", "mbna", "tymit", "newday", "marbles", "fluid", "jaja", "zopa", "novuna", "creation",
        "moneybarn", "blackhorse", "santanderconsumer",
    )

    /** The bank behind [app], when it is one of the known ones. */
    fun bankName(app: String): String? = KNOWN[app]

    /** What an account offers for choosing where a payment goes. */
    data class AccountOption(
        val id: Long,
        val name: String,
        val notes: String?,
        /** Day-to-day money: a current account rather than a saver or a loan. */
        val isSpending: Boolean,
        val isCard: Boolean,
    )

    /**
     * The account a payment from [bank] belongs in, when nothing has been
     * chosen for it: one that names the card's last four digits, then one
     * that names the bank (a card for a card company), then the default
     * account, then the first day-to-day one.
     */
    fun pickAccount(
        bank: String?,
        ending: String?,
        accounts: List<AccountOption>,
        defaultAccountId: Long?,
    ): Long? {
        if (accounts.isEmpty()) return null
        ending?.let { digits ->
            accounts.firstOrNull { digits in it.name || it.notes.orEmpty().contains(digits) }?.let { return it.id }
        }
        if (bank != null) {
            val named = accounts.filter { it.name.contains(bank, ignoreCase = true) }
            val cardCompany = bank == "American Express"
            named.firstOrNull { if (cardCompany) it.isCard else it.isSpending }?.let { return it.id }
            named.firstOrNull()?.let { return it.id }
        }
        defaultAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.let { return it.id } }
        return accounts.firstOrNull { it.isSpending }?.id ?: accounts.first().id
    }
}
