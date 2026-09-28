package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Holding

/**
 * What kind of account a statement is for, read from its heading.
 *
 * ## Why
 *
 * The account picker on import listed every account in the household, so a
 * Start to Save statement could be — and was — filed against a current
 * account, where its £200 counted as money to spend. A statement says what
 * it is at the top: the product name, "Savings account", "Credit card
 * statement". Reading that lets the picker offer only accounts of the same
 * kind, and offer to make one when there is none.
 *
 * ## Where it looks
 *
 * Only the heading lines, and only lines without an amount on them. The
 * transactions are exactly where the words would mislead: a current account
 * statement is full of TRANSFER TO SAVINGS, and a saver's is not.
 */
enum class StatementKind(
    val displayName: String,
    val accountType: AccountType,
) {
    CURRENT("current account", AccountType.CURRENT),
    SAVINGS("savings account", AccountType.SAVINGS),
    CREDIT_CARD("credit card", AccountType.CREDIT_CARD),
    ;

    /** Where an account for this kind of statement counts. */
    val holding: Holding get() = accountType.defaultHolding

    /** What was found: the kind, and the product's own name where it had one. */
    data class Found(val kind: StatementKind, val productName: String?)

    companion object {
        /** How far down the page counts as the heading. */
        private const val HEADING_LINES = 40

        private val AMOUNT = Regex("""\d[\d,]*\.\d{2}""")

        /** Said on a savings statement's heading and never on a current account's. */
        private val SAVINGS_WORDS = listOf(
            "start to save", "instant access", "easy access", "limited access",
            "triple access", "notice saver", "notice account", "fixed term",
            "fixed rate saver", "fixed rate bond", "regular saver", "monthly saver",
            "flex saver", "flexi saver", "online saver", "loyalty saver",
            "help to save", "cash isa", "lifetime isa", "junior isa", "stocks and shares isa",
            "isa", "lisa", "saver", "savings account", "savings statement", "premium bonds",
            "e saver", "esaver", "bonus saver", "member saver",
        )

        private val CARD_WORDS = listOf(
            "credit card", "credit limit", "minimum payment", "card statement",
            "barclaycard", "american express", "amex",
        )

        private val CURRENT_WORDS = listOf(
            "current account", "flexaccount", "flex account", "flexdirect", "flex direct",
            "flexplus", "flexbasic", "classic account", "club lloyds", "select account",
            "reward account", "everyday account", "bank account", "arranged overdraft",
            "overdraft limit",
        )

        /**
         * The kind of account [lines] are a statement for, or null when the
         * heading does not say.
         *
         * [fileName] is read too, since a CSV download has no heading and its
         * name is often the product ("StartToSave_Sept.csv").
         */
        fun detect(lines: List<String>, fileName: String? = null): Found? {
            val heading = lines.take(HEADING_LINES)
                .filterNot { AMOUNT.containsMatchIn(it) }
                .map { it to normalise(it) }
            val name = fileName?.let { normalise(it.replace(Regex("""(?<=[a-z])(?=[A-Z])"""), " ")) }

            fun hit(text: String, raw: String): Found? {
                SAVINGS_WORDS.firstOrNull { " $text ".contains(" $it ") }
                    ?.let { return Found(SAVINGS, productName(raw, it)) }
                if (CARD_WORDS.any { " $text ".contains(" $it ") }) return Found(CREDIT_CARD, null)
                CURRENT_WORDS.firstOrNull { " $text ".contains(" $it ") }
                    ?.let { return Found(CURRENT, productName(raw, it)) }
                return null
            }

            // The first line that says anything wins. A statement names its
            // own product at the top; a mention further down — an advert for
            // a saver on a current account statement — is not what it is.
            heading.forEach { (raw, text) -> hit(text, raw)?.let { return it } }
            name?.let { text -> hit(text, "")?.let { return it } }
            return null
        }

        /**
         * A name for a new account made from this statement: the heading line
         * when it is short and plainly a name, otherwise the phrase found.
         */
        private fun productName(line: String, word: String): String {
            val tidy = line.trim().replace(Regex("""\s+"""), " ")
            val usable = tidy.length in 3..MAX_NAME &&
                tidy.none { it.isDigit() } &&
                !tidy.contains(':')
            return if (usable) tidy else titleCase(word)
        }

        private const val MAX_NAME = 32

        private fun titleCase(text: String) = text.split(' ').joinToString(" ") { part ->
            if (part.length <= 3 && part in setOf("isa", "lisa")) {
                part.uppercase()
            } else {
                part.replaceFirstChar { it.uppercase() }
            }
        }

        private fun normalise(text: String) = TransactionFingerprint.normaliseDescription(text)
    }
}
