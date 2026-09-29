package com.rhys.financetracker.data.importer

/**
 * A payee's name without the bank's paperwork: "FASTER PAYMENT TO J SMITH
 * REF 88213" → "J Smith". Used to add up payments by who they went to.
 */
object PayeeNames {

    private val NOISE = setOf(
        "to", "from", "payment", "payments", "transfer", "faster", "fps", "fp", "bank",
        "standing", "order", "so", "s", "o", "mobile", "online", "bill", "ref", "reference",
        "bacs", "dd", "d", "direct", "debit", "credit", "card", "contactless", "via", "tfr",
        "paid", "sent", "on", "the", "gbp", "uk",
    )

    /** A tidy name, or blank when nothing but paperwork was left. */
    fun of(description: String): String =
        TransactionFingerprint.normaliseDescription(description)
            .split(' ')
            .filter { word -> word.isNotBlank() && word.none { it.isDigit() } && word !in NOISE }
            .take(3)
            .joinToString(" ") { part ->
                if (part.length == 1) part.uppercase() else part.replaceFirstChar { it.uppercase() }
            }

    private val TITLES = setOf("mr", "mrs", "ms", "miss", "dr", "mx")

    /**
     * A key that the same person's name gives however the bank writes it:
     * "John Smith", "J Smith", "Mr J Smith" and "Smith J" all give "j smith".
     * Used to put money sent to someone next to money they sent back.
     */
    fun personKey(name: String): String {
        val words = name.lowercase().split(' ').filter { it.isNotBlank() && it !in TITLES }
        if (words.isEmpty()) return name.lowercase().trim()
        if (words.size == 1) return words[0]
        val (first, second) = if (words[1].length == 1 && words[0].length > 1) {
            words[1] to words[0]
        } else {
            words[0] to words[1]
        }
        return "${first.first()} $second"
    }

    /** True when money in reads like it came from somebody: a transfer, or "from" a name. */
    fun looksLikeFromAPerson(description: String): Boolean {
        val text = " ${TransactionFingerprint.normaliseDescription(description)} "
        return looksLikeAPerson(description) ||
            listOf(" from ", " fp ", " received ", " paym ").any { text.contains(it) }
    }

    /**
     * True when a description reads like money sent to somebody rather than
     * spent in a shop: a bank transfer, a standing order, "payment to".
     */
    fun looksLikeAPerson(description: String): Boolean {
        val text = " ${TransactionFingerprint.normaliseDescription(description)} "
        return listOf(
            " to ", " faster payment", " fps ", " bank transfer", " transfer to", " payment to",
            " standing order", " so ", " mobile payment", " sent to", " paym ",
        ).any { text.contains(it) }
    }
}
