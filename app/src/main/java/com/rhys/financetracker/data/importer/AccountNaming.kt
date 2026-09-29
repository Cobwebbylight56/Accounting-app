package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding

/**
 * What the banks call an account, and what that makes it.
 *
 * ## Why this is separate from [PotWords]
 *
 * [PotWords] reads payment descriptions, where a word has to survive meeting
 * every shop in Britain — "isa" cannot be trusted there because it is the
 * start of ISABELLAS, and "lisa" is somebody's name.
 *
 * An account name is a different kind of text. Nobody calls their current
 * account ISABELLAS, and an account called LISA is a Lifetime ISA. So this
 * list can be far more direct, and needs to be: "instant access", "limited
 * access" and "fixed term" say savings as plainly as the word savings does,
 * and none of them contains it.
 *
 * ## The one that cannot be read from the name
 *
 * A current account somebody keeps a little money in, in case they lose access
 * to their main one, is a current account by name and a stagnant pot in
 * practice. Nothing in its name says so. That is what each account's
 * [Holding] is for, and why nothing here is more than a suggestion.
 */
object AccountNaming {

    /**
     * Money you have put somewhere you are not going to spend from.
     *
     * Matched at the start of a word, so "saver" finds FLEX SAVER and "isa"
     * finds CASH ISA without either reaching inside another word.
     */
    val SAVINGS: List<String> = listOf(
        // -- said plainly ------------------------------------------------
        "savings", "saving", "saver", "nest egg", "rainy day", "emergency fund",

        // -- ISAs, in all the ways they are written ----------------------
        "isa", "cash isa", "stocks and shares isa", "junior isa", "jisa",
        "lifetime isa", "lisa", "help to buy isa", "innovative finance isa",

        // -- how the accounts themselves are named -----------------------
        "instant access", "easy access", "limited access", "triple access",
        "double access", "notice account", "notice saver", "fixed term",
        "fixed rate", "fixed bond", "term deposit", "regular saver",
        "monthly saver", "start to save", "help to save", "loyalty",
        "flex saver", "flexi saver", "online saver", "e saver", "esaver",
        "bonus saver", "member saver", "future saver", "goal saver",
        "smart saver", "digital saver", "young saver", "junior saver",
        "child saver", "christmas", "holiday fund", "first saver",

        // -- bonds and national savings ----------------------------------
        "bond", "premium bonds", "income bonds", "national savings",
        "ns and i", "nsandi", "guaranteed growth", "guaranteed income",
    )

    /** Where money is held to be invested rather than simply kept. */
    private val INVESTMENT = listOf(
        "invest", "shares", "stocks", "vanguard", "hargreaves", "aj bell",
        "trading 212", "freetrade", "nutmeg", "wealthify", "moneyfarm",
        "sipp", "portfolio", "fund",
    )

    private val LIABILITY = listOf("mortgage", "loan", "finance", "overdraft")

    /** Buy now, pay later. Before cards, so "PayPal Pay in 3" is not read as a card. */
    private val PAY_LATER = listOf(
        "pay in 3", "payin3", "pay in 4", "pay later", "klarna", "clearpay", "laybuy",
        "zilch", "paypal pay", "monzo flex", "afterpay",
    )

    /**
     * Cards, named rather than matched on the word.
     *
     * Matching is at the start of a word, so "card" alone never reaches
     * BARCLAYCARD — and loosening it to a plain substring would put every
     * CARD PAYMENT and CASH ISA CARD here instead.
     */
    private val CARDS = listOf(
        "credit", "card", "barclaycard", "mastercard", "visa", "amex",
        "american express", "capital one", "vanquis", "aqua", "tymit",
    )

    /**
     * The type an account with this name most likely is.
     *
     * Liabilities first because they are unambiguous, then savings, because
     * everything else in the list is a word a savings product can also
     * contain: a savings card holds the word card.
     *
     * There is no cash type to guess: notes and coins are the cash pot, not
     * an account.
     */
    fun typeFor(name: String): AccountType {
        val text = " ${TransactionFingerprint.normaliseDescription(name)} "
        fun says(words: List<String>) = words.any { text.contains(" $it") }
        return when {
            text.contains(" mortgage") -> AccountType.MORTGAGE
            says(LIABILITY) -> AccountType.LOAN
            text.contains(" pension") -> AccountType.PENSION
            says(INVESTMENT) -> AccountType.INVESTMENT
            says(SAVINGS) -> AccountType.SAVINGS
            says(PAY_LATER) -> AccountType.PAY_LATER
            says(CARDS) -> AccountType.CREDIT_CARD
            else -> AccountType.CURRENT
        }
    }

    /**
     * True when this "account" is really notes and coins — a spreadsheet row
     * called Cash, or £1 coins — which belong in the cash pot rather than
     * being made into an account.
     *
     * Only when nothing else claims the name: a Cash ISA is savings.
     */
    fun isCashPot(name: String): Boolean {
        val text = " ${TransactionFingerprint.normaliseDescription(name)} "
        return CASH_WORDS.any { text.contains(" $it") } && typeFor(name) == AccountType.CURRENT
    }

    private val CASH_WORDS = listOf("cash", "coin", "£1 coin", "wallet", "petty", "tin ")

    /** Where an account with this name most likely counts. */
    fun holdingFor(name: String): Holding = typeFor(name).defaultHolding

    /**
     * True when the name says savings but the account is counted as money
     * to spend.
     *
     * The reason the Accounts screen can offer to put it right. It asks the
     * account's one stored answer, so unlike the old override there is no
     * earlier choice that can quietly silence it.
     */
    fun looksMistyped(name: String, holding: Holding): Boolean =
        holding == Holding.SPEND && holdingFor(name) == Holding.SET_ASIDE
}
