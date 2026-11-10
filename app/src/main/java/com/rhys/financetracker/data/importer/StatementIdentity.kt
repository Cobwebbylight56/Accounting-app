package com.rhys.financetracker.data.importer

/**
 * Which account a statement is for, from what the statement itself prints:
 * its sort code and account number, or a card's last four digits, and the
 * bank whose name heads it.
 *
 * The name a statement is addressed to can't tell Rhys's Lloyds account from
 * the joint Nationwide one — a joint statement is addressed to Rhys too — so
 * statements went to whichever account was his. An account number can. Each
 * statement's numbers are remembered against the account it went to, so the
 * next from the same account goes there by itself.
 */
object StatementIdentity {

    /**
     * What identifies the account in [lines] — "sc:070116:12345678" for a
     * sort code and account number, "card:4321" for a card — or none.
     * Numbers printed as zeros, as in a scrubbed copy, say nothing.
     */
    fun of(lines: List<String>): List<String> {
        val head = lines.take(HEAD_LINES).joinToString("\n")
        val found = mutableListOf<String>()
        val sortCode = SORT_CODE.find(head)?.let { m -> m.groupValues.drop(1).take(3).joinToString("") }
            ?.takeUnless { it.all { c -> c == '0' } }
        val account = ACCOUNT_NUMBER.find(head)?.groupValues?.get(1)
            ?: sortCode?.let { SORT_THEN_NUMBER.find(head)?.groupValues?.get(4) }
        if (sortCode != null && account != null && !account.all { it == '0' }) found += "sc:$sortCode:$account"
        CARD_NUMBER.find(head)?.groupValues?.get(1)?.takeUnless { it == "0000" }?.let { found += "card:$it" }
        return found
    }

    /**
     * The bank or card company named at the head of [lines] — "Nationwide",
     * "Lloyds", "Capital One" — or null. Taken from the heading only, where
     * the bank names itself; a payee further down names someone else.
     */
    fun bankOf(lines: List<String>): String? {
        val head = lines.take(BANK_LINES).joinToString(" ").lowercase()
        return BANKS.firstOrNull { (words, _) -> words.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(head) } }?.second
    }

    /**
     * The sort code and account number a payment line names — "Transfer to
     * 070806 21646684", "FPO 07-08-06 21646684" — as the same key [of]
     * gives the statement for that account, or null.
     */
    fun numbersIn(description: String): String? {
        val m = NUMBERS_IN_LINE.find(description) ?: return null
        val sortCode = m.groupValues[1] + m.groupValues[2] + m.groupValues[3]
        val account = m.groupValues[4]
        if (sortCode.all { it == '0' } || account.all { it == '0' }) return null
        return "sc:$sortCode:$account"
    }

    /** A sort code, written together or in pairs, then an 8-digit account number. */
    private val NUMBERS_IN_LINE = Regex("""\b(\d{2})[- ]?(\d{2})[- ]?(\d{2})\s+(\d{8})\b""")

    private const val HEAD_LINES = 60
    private const val BANK_LINES = 25

    private val SORT_CODE = Regex("""(?i)sort\s*code[:\s]*(\d{2})[-\s](\d{2})[-\s](\d{2})\b""")

    private val ACCOUNT_NUMBER = Regex("""(?i)account\s*(?:number|no\.?)[:\s]*(\d{8})\b""")

    /** "07-01-16 12345678": a sort code with the account number straight after it. */
    private val SORT_THEN_NUMBER = Regex("""\b(\d{2})-(\d{2})-(\d{2})\s+(\d{8})\b""")

    /** "CARD NUMBER ************4321", "Card ending 4321", "XXXX XXXX XXXX 4321". */
    private val CARD_NUMBER = Regex(
        """(?i)(?:card\s*(?:number|no\.?|ending(?:\s*in)?)[:\s]*[*x•\s]*|(?:[*x•]{4}\s?){3})(\d{4})\b""",
    )

    /** How each bank names itself on a statement, and the name an account would carry. */
    private val BANKS: List<Pair<List<String>, String>> = listOf(
        listOf("nationwide") to "Nationwide",
        listOf("lloyds") to "Lloyds",
        listOf("halifax") to "Halifax",
        listOf("barclaycard") to "Barclaycard",
        listOf("barclays") to "Barclays",
        listOf("hsbc") to "HSBC",
        listOf("first direct") to "first direct",
        listOf("natwest") to "NatWest",
        listOf("santander") to "Santander",
        listOf("monzo") to "Monzo",
        listOf("starling") to "Starling",
        listOf("capital one") to "Capital One",
        listOf("zopa") to "Zopa",
        listOf("american express", "amex") to "American Express",
        listOf("moneybox") to "Moneybox",
        listOf("chase") to "Chase",
        listOf("tsb") to "TSB",
        listOf("virgin money") to "Virgin Money",
        listOf("co-operative bank", "co-op bank") to "Co-operative",
        listOf("metro bank") to "Metro Bank",
        listOf("tesco bank") to "Tesco Bank",
        listOf("vanquis") to "Vanquis",
        listOf("mbna") to "MBNA",
        listOf("revolut") to "Revolut",
    )
}
