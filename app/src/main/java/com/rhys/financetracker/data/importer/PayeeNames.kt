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

    /** Words that are never part of a person's name. */
    private val NOT_A_NAME = setOf(
        "paypal", "ltd", "limited", "plc", "llp", "inc", "co", "uk", "services", "service", "shop",
        "store", "stores", "finance", "insurance", "energy", "council", "club", "group", "trading",
        "centre", "center", "school", "college", "pharmacy", "garage", "motors", "cars", "taxi",
        "taxis", "cafe", "restaurant", "bar", "pub", "hotel", "travel", "pay", "loan", "loans",
        "mortgage", "water", "gas", "electric", "broadband", "gym", "fitness", "market",
        "marketplace", "amazon", "ebay", "vinted", "depop", "apple", "google", "uber", "trust",
        "charity", "society", "church", "savings", "saver", "isa", "account", "cash", "atm",
        "withdrawal", "interest", "fee", "fees", "charge", "charges", "refund", "deposit",
        "salary", "wages", "wage", "hmrc", "dvla", "tv", "licence", "returned", "reversal",
        "purchase", "revolut", "monzo", "starling", "wise", "klarna", "clearpay", "laybuy",
        "sumup", "zettle", "square", "stripe", "www", "com", "net", "org", "international",
        "solutions", "holdings", "company", "and", "of", "the", "for",
    )

    /**
     * True when a tidy payee name (see [of]) looks like a person's rather
     * than a business's: a few plain words, none of them a business word,
     * and not a shop or service the app knows by name. PayPal, "Tesco
     * Stores" and "Sky Digital" are not people; "J Smith" and "Hannah" are.
     */
    fun isPersonName(name: String): Boolean {
        val words = name.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return false
        if (words.any { word -> word.any { !it.isLetter() && it != '\'' && it != '-' } }) return false
        if (words.any { it in NOT_A_NAME }) return false
        if (words.none { it.length >= 2 }) return false
        // The list alone: asking categoryFor would ask this again.
        return MerchantCategoriser.ruleCategory(name) == null
    }

    /**
     * True when [description] is nothing but the name of one of [names] and
     * the bank's paperwork — "EVANS RHYS", "R M W EVANS", "Faster payment to
     * Hannah Evans" — however the bank writes it: the surname, and the first
     * name or its initial, in either order. Money to or from one of the
     * household is a move between your own money, not spending.
     *
     * A one-word name — "Hannah" — is tried with each surname the other
     * names carry. Any other word — "Evans Garage", "R Evans Ltd" — and it
     * is somebody else.
     *
     * When no surname is known at all — everyone is down by first name only
     * and no statement has taught one — a first name with one other
     * name-like word beside it ("EVANS RHYS") is taken to be that person.
     * A namesake would land in the same place: money with people is filed
     * under Transfers & payments either way.
     */
    fun isOnlyName(description: String, names: List<String>): Boolean {
        val words = TransactionFingerprint.normaliseDescription(description).split(' ')
            // An initial is kept even where it is paperwork elsewhere: "S" in "S T JONES".
            .filter { word -> word.isNotBlank() && word.none { it.isDigit() } && (word.length == 1 || word !in NOISE) && word !in TITLES }
        if (words.isEmpty() || words.size > MAX_NAME_WORDS) return false
        val split = names.map { name ->
            TransactionFingerprint.normaliseDescription(name).split(' ').filter { it.isNotBlank() && it !in TITLES }
        }.filter { it.isNotEmpty() }
        val surnames = split.filter { it.size >= 2 }.map { it.last() }.filter { it.length >= MIN_SURNAME }.toSet()
        val full = split.flatMap { parts -> if (parts.size >= 2) listOf(parts) else surnames.map { parts + it } }
        if (full.any { parts -> namedBy(words, parts) }) return true
        if (surnames.isNotEmpty()) return false
        val firstNames = split.filter { it.size == 1 }.map { it.single() }.filter { it.length >= MIN_SURNAME }
        return words.size == 2 && words.any { it in firstNames } && words.all { it.length >= 2 } &&
            isPersonName(words.joinToString(" "))
    }

    /** True when [words] are [parts] — a first name, initials, a surname — and nothing else. */
    private fun namedBy(words: List<String>, parts: List<String>): Boolean {
        val surname = parts.last()
        val first = parts.first()
        if (surname.length < MIN_SURNAME || surname !in words) return false
        val initials = parts.dropLast(1).filter { it.length == 1 }.toSet()
        val firstThere = first in words || first.take(1) in words ||
            (first.length == 1 && words.any { it.length > 1 && it != surname && it.startsWith(first) })
        if (!firstThere) return false
        return words.all { word ->
            word == surname || word in parts || word.length == 1 || initials.any { word.startsWith(it) }
        }
    }

    /** A name and its initials: more than this is an address or a business. */
    private const val MAX_NAME_WORDS = 5

    /** Shorter than this is not a surname worth acting on. */
    private const val MIN_SURNAME = 3

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

    /**
     * True when a payment recorded as sent reads like money that came in —
     * "Bank credit H Payne", "From J Smith", "Received" — the kind an older
     * reading of a statement put on the wrong side.
     */
    fun readsLikeMoneyIn(description: String): Boolean {
        val text = " ${TransactionFingerprint.normaliseDescription(description)} "
        return listOf(" credit ", " from ", " received ", " paid in ", " receipt ", " refund ").any { text.contains(it) }
    }
}
