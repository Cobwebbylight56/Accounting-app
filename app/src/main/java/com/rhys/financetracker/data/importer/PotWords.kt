package com.rhys.financetracker.data.importer

/**
 * The wordings that mean "this money moved into or out of savings", and the
 * ones that mean cash.
 *
 * ## Why these are a list of their own
 *
 * Every other category is a *place you spent money*, and a shop either
 * matches or it does not. These two are different: they say the money did not
 * leave the household at all — it moved from an account into a saver, or out
 * of the bank into somebody's pocket — and counting either as spending makes
 * a household that saves £200 a month look like one that spends it.
 *
 * ## Both directions, one list
 *
 * A transfer to a saver and a transfer back from it are described in the same
 * words; only the direction differs, and the statement already says which way
 * the money went. So the same words are matched on both sides and the
 * transaction's own direction decides the meaning:
 *
 * | Description                  | Direction | Means                |
 * |------------------------------|-----------|----------------------|
 * | `TRANSFER TO START TO SAVE`  | out       | paid into savings    |
 * | `TRANSFER FROM START TO SAVE`| in        | taken out of savings |
 * | `CASH WITHDRAWAL`            | out       | cash taken out       |
 * | `COUNTER DEPOSIT`            | in        | cash paid back in    |
 *
 * ## The trailing space
 *
 * Keywords match at the start of a word, so `isa` alone reaches ISABELLAS and
 * `saver` reaches SAVERS, the high-street shop. A trailing space makes the
 * match a whole word instead. Anything short enough to begin another word is
 * written that way here.
 */
internal object PotWords {

    /**
     * Money moved between an account and somewhere it is being kept.
     *
     * Grouped by what the wording actually is, because that is how they get
     * added to: a bank renames a product, and only one group changes.
     */
    val SAVINGS: List<String> = listOf(
        // -- saying it plainly -------------------------------------------
        "savings", "savings account", "to savings", "into savings",
        "from savings", "savings pot", "savings goal", "savings transfer",
        "to save", "save into", "set aside", "put away", "nest egg",
        "rainy day", "emergency fund", "sinking fund", "money box",

        // -- what the banks call their savers ----------------------------
        "saver ", "regular saver", "instant saver", "easy saver",
        "easy access", "triple access", "limited access", "double access",
        "online saver", "e saver", "esaver", "monthly saver", "loyalty saver",
        "flex saver", "flexi saver", "future saver", "goal saver",
        "smart saver", "digital saver", "young saver", "junior saver",
        "child saver", "christmas saver", "holiday saver", "bonus saver",
        "member saver", "start to save", "help to save", "first saver",

        // -- ISAs and bonds ----------------------------------------------
        "isa ", "cash isa", "junior isa", "jisa", "lifetime isa",
        "help to buy isa", "stocks and shares", "fixed rate bond",
        "fixed bond", "savings bond", "premium bonds", "ns and i", "nsandi",
        "national savings", "income bonds", "guaranteed growth",
        "guaranteed income", "maturity", "matured",

        // -- where people invest -----------------------------------------
        "vanguard", "hargreaves", "aj bell", "interactive investor",
        "freetrade", "trading 212", "nutmeg", "wealthify", "moneyfarm",
        "moneybox", "plum ", "chip financial", "chip invest", "dodl",
        "investment", "investing", "investec", "sipp", "pension contribution",

        // -- the app-bank names for a pot --------------------------------
        "monzo pot", "starling space", "round up", "roundup",
    )

    /**
     * Money that became notes and coins, or notes and coins that went back in.
     *
     * Cash out of a machine is not spending — it is the same money in a
     * different pocket. What it is then spent on is a separate matter, and one
     * no statement can ever say.
     */
    val CASH: List<String> = listOf(
        // -- out of a machine or a counter -------------------------------
        "cash withdrawal", "cash machine", "cashpoint", "cash point",
        "atm ", "atm withdrawal", "link atm", "link cash", "notemachine",
        "note machine", "cardtronics", "cash advance", "cash at",
        "counter withdrawal", "branch withdrawal", "post office cash",
        "withdrawal", "withdrawn",

        // -- and back in --------------------------------------------------
        "cash deposit", "cash paid in", "counter deposit", "branch deposit",
        "cash in at", "paying in",
    )
}
