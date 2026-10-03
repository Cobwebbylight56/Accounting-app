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
    )

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
        if (bank != null && bank != "Google Wallet") {
            val named = accounts.filter { it.name.contains(bank, ignoreCase = true) }
            val cardCompany = bank == "American Express"
            named.firstOrNull { if (cardCompany) it.isCard else it.isSpending }?.let { return it.id }
            named.firstOrNull()?.let { return it.id }
        }
        defaultAccountId?.let { id -> accounts.firstOrNull { it.id == id }?.let { return it.id } }
        return accounts.firstOrNull { it.isSpending }?.id ?: accounts.first().id
    }
}
