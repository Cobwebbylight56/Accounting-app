package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * Finds the regular payments in a run of statements — the bills.
 *
 * ## What counts
 *
 * A bank marks a Direct Debit or standing order as one ("DD", "S/O"), and
 * those are bills by definition: Utility Warehouse, Samsung Finance, DVLA
 * car tax. One is enough to go on, and it is taken as monthly.
 *
 * Anything else has to show itself: the same payee in more than one month,
 * at a steady interval. A payee seen every week at wildly different amounts
 * is the supermarket, not a bill, so a repeating payee also needs a bill-like
 * category or a steady amount.
 *
 * ## What it does not do
 *
 * It never makes anything. It suggests, with how it knows, and the person
 * ticks what is right.
 */
object RecurringDetector {

    /** One payment as the detector sees it. */
    data class Payment(
        val description: String,
        val amountMinor: Long,
        val date: LocalDate,
        val categoryName: String?,
        val accountId: Long,
        /** The entry it came from, when there is one. */
        val id: Long = 0L,
    )

    /** A bill that is already set up, so it is not suggested again. */
    data class KnownBill(val name: String, val amountMinor: Long, val accountId: Long)

    /** A regular payment found. */
    data class RegularPayment(
        /** A tidy name for the bill: the payee with the bank's reference numbers taken off. */
        val name: String,
        val amountMinor: Long,
        val frequency: Frequency,
        val lastDate: LocalDate,
        val occurrences: Int,
        /** True when the amount moves about — energy, a card bill. */
        val isVariable: Boolean,
        val categoryName: String?,
        val accountId: Long,
        /** Why it is thought to be a bill, for the person to judge. */
        val reason: String,
    ) {
        /** True when it has been seen more than once — worth ticking by default. */
        val isConfirmed: Boolean get() = occurrences >= 2

        /** The next time it should be paid, on or after [today]. */
        fun nextDue(today: LocalDate): LocalDate {
            var next = step(lastDate)
            while (next.isBefore(today)) next = step(next)
            return next
        }

        private fun step(date: LocalDate): LocalDate = when (frequency) {
            Frequency.WEEKLY -> date.plusWeeks(1)
            Frequency.FORTNIGHTLY -> date.plusWeeks(2)
            Frequency.FOUR_WEEKLY -> date.plusWeeks(4)
            Frequency.QUARTERLY -> date.plusMonths(3)
            Frequency.YEARLY -> date.plusYears(1)
            else -> date.plusMonths(1)
        }
    }

    /** Words that are the bank's paperwork rather than the payee's name. */
    private val NOISE = setOf(
        "dd", "d", "direct", "debit", "so", "s", "o", "standing", "order", "ref", "reference",
        "card", "payment", "payments", "to", "fp", "bp", "bgc", "tfr", "via", "online",
        "mobile", "bank", "on", "ltd", "limited", "plc", "uk", "gb", "the",
    )

    /** Categories that are bills whatever the amount does. */
    private val BILL_CATEGORIES = listOf(
        "utilit", "energy", "gas", "electric", "water", "council", "insurance", "phone",
        "mobile", "broadband", "internet", "tv", "subscription", "rent", "mortgage",
        "finance", "loan", "car tax", "tax", "childcare", "gym", "fitness", "breakdown",
    )

    private const val STEADY_SPREAD = 0.05
    private const val MOST_SPREAD = 0.5

    /**
     * Words that mean a payment came back or was undone — "RETURNED DD",
     * "UNPAID D/D", "REFUND". They carry the Direct Debit marker without
     * being a bill, and one of them was set up as a £71 monthly "Returned".
     */
    private val NOT_A_BILL = setOf(
        "returned", "return", "refund", "refunded", "reversal", "reversed", "unpaid",
        "recalled", "recall", "cancelled", "rejected", "reclaim", "indemnity", "bounced",
    )

    fun find(
        payments: List<Payment>,
        known: List<KnownBill>,
    ): List<RegularPayment> {
        val kept = payments.filterNot { payment ->
            TransactionFingerprint.normaliseDescription(payment.description)
                .split(' ').any { it in NOT_A_BILL }
        }
        // A bill belongs to an account, so each account's payments are gathered on their own.
        return kept.groupBy { it.accountId }.values
            .flatMap { series(it) }
            .mapNotNull { judge(it.key, it.payments) }
            .filterNot { found -> known.any { isSameBill(found, it) } }
            .sortedByDescending { it.amountMinor }
    }

    /** One thing paid for again and again: its payments, and the payee name it goes by now. */
    data class Series(val key: String, val payments: List<Payment>)

    /**
     * [payments] gathered into one list per thing paid for.
     *
     * By payee name first — but a name is not always one thing, nor one
     * thing always one name:
     *
     * * **One payee, two things.** Two policies with the same insurer, each
     *   paid every month at its own amount, are two payments. Steady amounts
     *   paid side by side in the same months are kept apart; a price rise,
     *   where one amount follows the other, stays one.
     * * **One thing, two names.** The bank calls it "VIRGIN MEDIA" for a
     *   while and "VIRGIN MEDIA PYMTS" after, or "SKY UK LTD" and then "SKY
     *   DIGITAL". Names of the same payee ([samePayee]) at much the same
     *   amount, one after the other rather than both in the same months, are
     *   one payment — under the name it goes by now.
     */
    fun series(payments: List<Payment>): List<Series> {
        val pieces = payments
            .groupBy { keyOf(it.description) }
            .filterKeys { it.isNotBlank() }
            .flatMap { (key, group) -> splitSideBySide(group).map { Series(key, it) } }
            .toMutableList()
        // Join names of one payee, until there is nothing left to join.
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in pieces.indices) {
                for (j in i + 1 until pieces.size) {
                    val a = pieces[i]
                    val b = pieces[j]
                    if (a.key == b.key || !samePayee(a.key, b.key)) continue
                    if (!similarAmounts(a.payments, b.payments) || monthsInCommon(a.payments, b.payments) > 1) continue
                    val all = (a.payments + b.payments).sortedBy { it.date }
                    pieces[i] = Series(keyOf(all.last().description), all)
                    pieces.removeAt(j)
                    joined = true
                    break@loop
                }
            }
        }
        return pieces
    }

    /**
     * A payee's payments as one list, or as several when it is paid for
     * more than one thing at once: steady amounts, each paid in the same
     * months as the other. A shop paid all sorts of amounts stays one list.
     */
    private fun splitSideBySide(group: List<Payment>): List<List<Payment>> {
        val amounts = group.map { it.amountMinor }.sorted()
        if (amounts.last() <= amounts.first() * (1 + SAME_AMOUNT)) return listOf(group)
        // The same amount each time, give or take pennies: a policy, a plan.
        val clusters = mutableListOf<MutableList<Payment>>()
        for (payment in group.sortedBy { it.amountMinor }) {
            val cluster = clusters.lastOrNull()
            if (cluster != null && payment.amountMinor <= cluster.first().amountMinor * (1 + EXACT_AMOUNT)) {
                cluster += payment
            } else {
                clusters += mutableListOf(payment)
            }
        }
        val steady = clusters.filter { cluster -> cluster.map { YearMonth.from(it.date) }.distinct().size >= 2 }
        // Most of it at a few steady amounts, or it is a shop, not two bills.
        if (steady.sumOf { it.size } < group.size * MOSTLY_STEADY) return listOf(group)
        // A price rise: the new amount follows the old, so it is still one.
        val joined = mutableListOf<MutableList<Payment>>()
        for (cluster in steady.sortedBy { c -> c.minOf { it.date } }) {
            val median = cluster.map { it.amountMinor }.sorted()[cluster.size / 2]
            val follows = joined
                .filter { monthsInCommon(it, cluster) <= 1 }
                .minByOrNull { other -> kotlin.math.abs(other.map { it.amountMinor }.sorted()[other.size / 2] - median) }
            if (follows != null) follows += cluster else joined += cluster.toMutableList()
        }
        return joined.map { list -> list.sortedBy { it.date } }
    }

    /**
     * Whether two payee names are one payee: one is the other with more
     * added ("sky" and "sky digital", "virgin media" and "virgin media
     * pymts"), or they start with the same name that isn't a card
     * processor's ("PAYPAL *NETFLIX" and "PAYPAL *SPOTIFY" are not one).
     */
    internal fun samePayee(one: String, other: String): Boolean {
        val a = one.split(' ').filter { it.isNotBlank() }
        val b = other.split(' ').filter { it.isNotBlank() }
        if (a.isEmpty() || b.isEmpty()) return false
        val (small, big) = if (a.size <= b.size) a to b else b to a
        if (small.all { it in big } && small.any { it !in PROCESSORS }) return true
        val first = a.first()
        return first == b.first() && first.length >= 3 && first !in PROCESSORS
    }

    private fun similarAmounts(one: List<Payment>, other: List<Payment>): Boolean {
        val a = one.map { it.amountMinor }.sorted()[one.size / 2].coerceAtLeast(1L)
        val b = other.map { it.amountMinor }.sorted()[other.size / 2].coerceAtLeast(1L)
        return maxOf(a, b).toDouble() / minOf(a, b) <= 1 + SIMILAR_AMOUNT
    }

    private fun monthsInCommon(one: List<Payment>, other: List<Payment>): Int =
        one.map { YearMonth.from(it.date) }.toSet().intersect(other.map { YearMonth.from(it.date) }.toSet()).size

    /** Names that only say how it was paid, so sharing one says nothing about the payee. */
    private val PROCESSORS = setOf(
        "paypal", "pp", "amazon", "amzn", "google", "apple", "klarna", "clearpay", "sumup", "zettle",
        "izettle", "sq", "square", "stripe", "sp", "uber", "www", "com", "co", "net", "and",
    )

    /** Amounts this close are one amount: a few pence of rounding, or VAT. */
    private const val SAME_AMOUNT = 0.10

    /** Paid at this amount every time, near enough: what a policy or a plan costs. */
    private const val EXACT_AMOUNT = 0.03

    /** Amounts this close may be one thing under two names, with a price rise between. */
    private const val SIMILAR_AMOUNT = 0.30

    /** How much of a payee's payments must be at steady amounts to be read as separate bills. */
    private const val MOSTLY_STEADY = 0.8

    private fun judge(key: String, group: List<Payment>): RegularPayment? {
        val sorted = group.sortedBy { it.date }
        val last = sorted.last()
        val amounts = sorted.map { it.amountMinor }.sorted()
        val median = amounts[amounts.size / 2].coerceAtLeast(1L)
        val spread = (amounts.last() - amounts.first()).toDouble() / median
        val marked = sorted.any { isMarkedRegular(it.description) }
        val category = last.categoryName
        val billCategory = category != null &&
            BILL_CATEGORIES.any { category.lowercase().contains(it) }
        val months = sorted.map { it.date.withDayOfMonth(1) }.distinct().size

        val frequency = if (sorted.size >= 2) intervalOf(sorted.map { it.date }) else null

        val reason = when {
            sorted.size >= 2 && frequency != null && (marked || billCategory || spread <= STEADY_SPREAD) &&
                spread <= MOST_SPREAD && months >= 2 ->
                "Paid ${sorted.size} times, ${frequency.displayName.lowercase()}" +
                    if (marked) ", by Direct Debit or standing order" else ""
            marked -> "a Direct Debit or standing order, seen once — check it's a bill"
            else -> return null
        }
        return RegularPayment(
            name = titleCase(key),
            amountMinor = last.amountMinor,
            frequency = frequency ?: Frequency.MONTHLY,
            lastDate = last.date,
            occurrences = sorted.size,
            isVariable = spread > STEADY_SPREAD,
            categoryName = category,
            accountId = last.accountId,
            reason = reason,
        )
    }

    /** The payee's name without the bank's references: "UTILITY WAREHOUSE DD 1234" → "utility warehouse". */
    internal fun keyOf(description: String): String =
        TransactionFingerprint.normaliseDescription(description)
            .split(' ')
            .filter { word -> word.isNotBlank() && word.none { it.isDigit() } && word !in NOISE }
            .take(3)
            .joinToString(" ")

    internal fun isMarkedRegular(description: String): Boolean {
        val text = " ${TransactionFingerprint.normaliseDescription(description)} "
        return listOf(" dd ", " d d ", " direct debit", " so ", " s o ", " standing order")
            .any { text.contains(it) }
    }

    /** How often, from the gaps between payments; null when there is no steady rhythm. */
    internal fun intervalOf(dates: List<LocalDate>): Frequency? {
        val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }
        if (gaps.isEmpty()) return null
        val gap = gaps.sorted()[gaps.size / 2]
        return when (gap) {
            in 6L..8L -> Frequency.WEEKLY
            in 13L..16L -> Frequency.FORTNIGHTLY
            in 26L..27L -> Frequency.FOUR_WEEKLY
            in 28L..35L -> Frequency.MONTHLY
            in 84L..98L -> Frequency.QUARTERLY
            in 350L..380L -> Frequency.YEARLY
            else -> null
        }
    }

    /**
     * Whether [found] is a bill already set up: the same name, or the same
     * amount from the same account — or the same payee under a slightly
     * different name ("Virgin Media" set up, "VIRGIN MEDIA PYMTS" found) at
     * much the same amount.
     */
    private fun isSameBill(found: RegularPayment, known: KnownBill): Boolean {
        val knownKey = keyOf(known.name)
        val foundKey = keyOf(found.name)
        val sameName = knownKey == foundKey && knownKey.isNotBlank()
        val sameAmount = known.accountId == found.accountId &&
            known.amountMinor == found.amountMinor
        val ratio = maxOf(known.amountMinor, found.amountMinor).toDouble() / minOf(known.amountMinor, found.amountMinor).coerceAtLeast(1L)
        val samePayeeNearAmount = knownKey.isNotBlank() && foundKey.isNotBlank() &&
            samePayee(knownKey, foundKey) && ratio <= 1 + SIMILAR_AMOUNT
        return sameName || sameAmount || samePayeeNearAmount
    }

    private fun titleCase(text: String) =
        text.split(' ').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
