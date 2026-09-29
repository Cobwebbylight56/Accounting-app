package com.rhys.financetracker.data.importer

import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
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

    fun find(
        payments: List<Payment>,
        known: List<KnownBill>,
    ): List<RegularPayment> {
        val groups = payments.groupBy { keyOf(it.description) to it.accountId }
            .filterKeys { it.first.isNotBlank() }
        return groups.mapNotNull { (key, group) -> judge(key.first, group) }
            .filterNot { found -> known.any { isSameBill(found, it) } }
            .sortedByDescending { it.amountMinor }
    }

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
            marked -> "A Direct Debit or standing order"
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

    private fun isSameBill(found: RegularPayment, known: KnownBill): Boolean {
        val sameName = keyOf(known.name) == keyOf(found.name) && keyOf(known.name).isNotBlank()
        val sameAmount = known.accountId == found.accountId &&
            known.amountMinor == found.amountMinor
        return sameName || sameAmount
    }

    private fun titleCase(text: String) =
        text.split(' ').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}
