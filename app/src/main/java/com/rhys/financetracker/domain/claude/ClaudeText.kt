package com.rhys.financetracker.domain.claude

/**
 * Text going to and coming from Claude.
 *
 * Going: account and sort-code numbers in a payment's wording ("Transfer to
 * 070806 21646684") are masked, so nothing that identifies an account is
 * ever sent. Coming: Claude writes a little markdown — **bold**, "-"
 * bullets — which is read into plain lines for the screen.
 */
object ClaudeText {

    /** [text] with account numbers and sort codes masked: "Transfer to •••••• ••6684". */
    fun maskNumbers(text: String): String = text
        .replace(SORT_CODE_SPLIT, "••-••-••")
        .replace(SORT_CODE_BEFORE_ACCOUNT) { "••••••" + it.groupValues[1] }
        .replace(LONG_NUMBER) { "••" + it.value.takeLast(4) }

    /** "07-08-06", "07 08 06". */
    private val SORT_CODE_SPLIT = Regex("""\b\d{2}[- ]\d{2}[- ]\d{2}\b""")

    /** "070806" just before an account number. */
    private val SORT_CODE_BEFORE_ACCOUNT = Regex("""\b\d{6}(\s+\d{8}\b)""")

    /** An account or card number: seven digits or more. */
    private val LONG_NUMBER = Regex("""\b\d{7,}\b""")

    /** A line of a reply: a bullet or not, and its parts, each bold or not. */
    data class Line(val bullet: Boolean, val parts: List<Part>)

    data class Part(val text: String, val bold: Boolean)

    /**
     * A reply as lines. "- " and "* " start a bullet, "#" headings become a
     * bold line, and **…** is bold; anything else is shown as written.
     */
    fun lines(reply: String): List<Line> = reply.trim().lines().map { raw ->
        val line = raw.trimEnd()
        val heading = HEADING.find(line)
        when {
            heading != null -> Line(false, listOf(Part(heading.groupValues[1].replace("**", ""), true)))
            BULLET.containsMatchIn(line) -> Line(true, parts(line.replaceFirst(BULLET, "")))
            else -> Line(false, parts(line))
        }
    }

    private fun parts(text: String): List<Part> {
        val pieces = text.split("**")
        // An unmatched ** is shown as it is, not as half a line of bold.
        if (pieces.size % 2 == 0) return listOf(Part(text, false))
        return pieces.mapIndexedNotNull { i, piece -> if (piece.isEmpty()) null else Part(piece, i % 2 == 1) }
    }

    private val HEADING = Regex("""^\s*#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^\s*[-*•]\s+""")
}
