package com.rhys.financetracker.data.importer

import com.rhys.financetracker.data.local.entity.PersonEntity

/**
 * Works out whose statement this is from the name printed on it.
 *
 * Every statement carries the account holder in its first few lines, and it is
 * the one piece of the file that says which person's money this is:
 *
 * ```
 * Nationwide Building Society
 * MR R M W EVANS
 * 1 Somewhere Street
 * ```
 *
 * Without reading it the app has to ask, and being asked the same question on
 * every import is how an account ends up filed against the wrong person — or
 * against nobody, which is worse, because then it is invisible to every
 * per-person view in the app.
 *
 * ## What counts as a match
 *
 * A surname and a first initial together. Statements almost never print a full
 * first name — "MR R M W EVANS", "MISS H EVANS" — so a surname alone would
 * name both people in a household and settle nothing. The initial is what
 * separates Rhys from Hannah when they share a surname, which is the case this
 * exists for.
 *
 * ## Why it never decides on its own
 *
 * A joint account carries both names, a statement can be addressed to somebody
 * who is not in the app at all, and post gets forwarded. So this reports what
 * it found and the import screen offers it; the choice stays with the user.
 * Two people matching is reported as no answer rather than the first one.
 */
object StatementOwner {

    /**
     * The person this statement is addressed to, or null when the name on it
     * settles nothing.
     *
     * @param lines the extracted text, in order.
     * @param people everybody the app knows about.
     */
    /**
     * The name the statement is addressed to, whether or not the app knows it.
     *
     * Reported separately from [detect] because "we read MR R M W EVANS and
     * nobody here is called that" is worth saying. Silence looked exactly like
     * not having read the statement at all.
     */
    fun nameOnStatement(lines: List<String>): String? =
        lines.take(HEADING_LINES)
            .map { it.trim() }
            .firstOrNull { line ->
                TITLE.containsMatchIn(line) && line.length <= LONGEST_NAME &&
                    line.none { it.isDigit() }
            }
            ?.replace(WHITESPACE, " ")

    /** How a statement addresses somebody, at the start of the line. */
    private val TITLE = Regex("""^(mr|mrs|miss|ms|dr|mx|sir|prof)[. ]""", RegexOption.IGNORE_CASE)

    /** Longer than this is an address line, not a name. */
    private const val LONGEST_NAME = 48

    private val WHITESPACE = Regex("\\s+")

    fun detect(lines: List<String>, people: List<PersonEntity>): PersonEntity? {
        if (people.isEmpty()) return null
        val heading = lines.take(HEADING_LINES)
            .joinToString(" ") { TransactionFingerprint.normaliseDescription(it) }
        if (heading.isBlank()) return null
        val words = heading.split(' ').filter { it.isNotBlank() }.toSet()

        val matched = people.filter { person -> names(person, words) }
        return matched.singleOrNull()
    }

    /** True when both the surname and the first initial are on the page. */
    private fun names(person: PersonEntity, words: Set<String>): Boolean {
        val parts = TransactionFingerprint.normaliseDescription(person.name)
            .split(' ')
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        val surname = parts.last()
        // A one-word name has no surname to speak of, so the whole of it has
        // to appear. "Joint" is exactly this, and it should never match a
        // person's post.
        if (parts.size == 1) return surname.length >= MIN_NAME && surname in words

        if (surname.length < MIN_NAME || surname !in words) return false
        val initial = parts.first().take(1)
        // The initial as its own word — "R" in "MR R M W EVANS". Or the first
        // name spelled out, for the statements that do print it.
        return initial in words || parts.first() in words
    }

    /**
     * How far into the document to look. The address block is at the top of
     * every statement, and a payee further down is not the account holder.
     */
    private const val HEADING_LINES = 25

    /** Shorter than this is not a surname worth acting on. */
    private const val MIN_NAME = 3
}
