package com.rhys.financetracker.data.importer

import java.time.LocalDate

/**
 * What you told the app about one subscription: when it started and how
 * long it runs for, so it knows when it ends — or that you cancelled it.
 *
 * The statements only show what has been paid. A twelve-month membership
 * looks exactly like one that runs for ever until the payments stop, and a
 * cancelled one looks like a missed payment for a month or two first.
 */
data class SubscriptionPlan(
    /** The subscription's name, lowercase: see [keyOf]. */
    val key: String,
    val startedOn: LocalDate? = null,
    /** How many months it runs for; null when it runs until it is cancelled. */
    val months: Int? = null,
    val cancelledOn: LocalDate? = null,
) {
    /** The day it ends: when it was cancelled, or its start plus its length. */
    val endsOn: LocalDate?
        get() = cancelledOn ?: if (startedOn != null && months != null) startedOn.plusMonths(months.toLong()) else null

    fun hasEnded(today: LocalDate): Boolean = endsOn?.let { !it.isAfter(today) } == true

    companion object {
        /** The lengths offered: a month, a quarter, half a year, a year, eighteen months, two years. */
        val LENGTHS = listOf(1, 3, 6, 12, 18, 24)

        fun keyOf(name: String): String = name.trim().lowercase().replace(Regex("""\s+"""), " ")

        /** Plans as stored: one a line, "key|started|months|cancelled", blanks for none. */
        fun parse(stored: String?): Map<String, SubscriptionPlan> =
            stored.orEmpty().lines().mapNotNull { line ->
                val parts = line.split(SEPARATOR)
                if (parts.size != FIELDS || parts[0].isBlank()) return@mapNotNull null
                SubscriptionPlan(
                    key = parts[0],
                    startedOn = parts[1].takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    months = parts[2].toIntOrNull()?.takeIf { it > 0 },
                    cancelledOn = parts[3].takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                )
            }.associateBy { it.key }

        fun write(plans: Collection<SubscriptionPlan>): String =
            plans.filter { it.startedOn != null || it.months != null || it.cancelledOn != null }
                .joinToString("\n") { plan ->
                    listOf(
                        plan.key.replace(SEPARATOR, " ").replace('\n', ' '),
                        plan.startedOn?.toString().orEmpty(),
                        plan.months?.toString().orEmpty(),
                        plan.cancelledOn?.toString().orEmpty(),
                    ).joinToString(SEPARATOR)
                }

        private const val SEPARATOR = "|"
        private const val FIELDS = 4
    }
}
