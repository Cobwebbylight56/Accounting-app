package com.rhys.financetracker.data.importer

/**
 * A regular payment that has just gone up: Netflix at £10.99 month after
 * month, then £12.99. Worth knowing — it's money every month from now on,
 * and the moment to ask whether it's still worth it.
 */
object PriceRise {

    /**
     * The amount before the rise, from each payment's [amounts] oldest
     * first: a steady run of at least two, then the latest payments — no
     * more than a few — at a higher amount. Null when there's no such rise:
     * a bill that varies every month isn't a rise, and one from long ago is
     * old news.
     */
    fun from(amounts: List<Long>): Long? {
        if (amounts.size < STEADY + 1) return null
        val now = amounts.last()
        val atNow = amounts.takeLastWhile { it == now }.size
        if (atNow > RECENT) return null
        val before = amounts.dropLast(atNow)
        val was = before.lastOrNull() ?: return null
        val steady = before.takeLastWhile { it == was }.size
        return was.takeIf { steady >= STEADY && it < now }
    }

    /** How many payments at the old price make it a price, not a one-off amount. */
    private const val STEADY = 2

    /** How many payments at the new price it stays news for. */
    private const val RECENT = 3
}
