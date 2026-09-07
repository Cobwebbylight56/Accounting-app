package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.AccountType

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
 * practice. Nothing in its name says so. That is what the per-account "Money
 * set aside" switch is for, and why nothing here is ever forced.
 */
object AccountNaming {

    /**
     * Money you have put somewhere you are not going to spend from.
     *
     * Ordered longest-first where phrases overlap, so "cash isa" is read
     * before "cash" ever gets a chance to make it a tin of notes.
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
     * contain: a cash ISA holds the word cash, and a savings card holds the
     * word card.
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
            says(listOf("cash", "coin", "wallet", "petty")) -> AccountType.CASH
            says(CARDS) -> AccountType.CREDIT_CARD
            else -> AccountType.CURRENT
        }
    }

    /**
     * True when the name says savings but the account is not being counted as
     * money set aside.
     *
     * The reason the Accounts screen can offer to put it right: fixing how the
     * type is guessed only ever helps accounts made afterwards, and the ones
     * already there are exactly the ones that are wrong.
     */
    fun looksMistyped(name: String, type: AccountType, countsAsSavings: Boolean?): Boolean {
        // Somebody who has already said either way is not being second-guessed.
        if (countsAsSavings != null) return false
        if (type.isSavings || type.isLiability) return false
        return typeFor(name).isSavings
    }
}
