package com.rhys.financetracker.data.importer

/**
 * Lines of a card statement's own writing that an older version took for
 * payments: "New transactions", "Payments received", "Mr Rhys Evans at",
 * "Dear Mr Rhys Evans, Your Direct Debit…", "Minimum Payments will be
 * taken…", "Protect Your Identity", "is".
 *
 * Only ever asked of rows on a credit card that came from a statement: a
 * shop's name never reads like the statement talking about itself.
 */
object CardStatementJunk {

    fun isJunk(description: String): Boolean {
        val text = description.lowercase().replace(Regex("""[^a-z0-9 ]+"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return true
        val words = text.split(' ')
        if (SUMMARY.containsMatchIn(text)) return true
        if (SMALL_PRINT.any { it in text }) return true
        // How a letter starts: "Dear …"; and a name broken off mid-sentence: "Mr Rhys Evans at".
        if (words.first() in GREETINGS) return true
        if (words.first() in TITLES && words.last() in DANGLING) return true
        // A piece of a sentence: ends mid-way, or is mostly sentence words.
        if (words.size <= 2 && words.last() in DANGLING) return true
        if (words.count { it in PROSE } >= 2) return true
        return false
    }

    private val SUMMARY = Regex(
        """\b(new transactions|payments received|payments and credits|minimum (payments?|repayments?)|credit limit|available to spend|your new balance|new closing balance|previous balance|statement (date|totals?)|payment due|account summary|transaction details|payment details|interest rates?)\b""",
    )

    private val SMALL_PRINT = listOf(
        "protect your identity", "going abroad", "will be taken", "if you make", "foreign transactions",
        "allocation of payments", "detach", "payment slip", "bank giro", "cashier",
    )

    private val GREETINGS = setOf("dear", "hello", "hi")
    private val TITLES = setOf("mr", "mrs", "miss", "ms", "dr")
    private val DANGLING = setOf(
        "at", "on", "in", "is", "be", "of", "to", "by", "for", "the", "and", "or", "will", "your", "as", "a", "an", "if",
    )
    private val PROSE = setOf(
        "will", "be", "you", "your", "we", "our", "if", "is", "are", "was", "have", "has", "this", "that",
        "taken", "make", "made", "please", "may", "can", "should", "would", "when", "which", "it",
    )
}
