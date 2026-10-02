package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.RecurringDetector.Payment
import com.rhys.financetracker.data.importer.SubscriptionTracker
import com.rhys.financetracker.data.importer.SubscriptionTracker.Status
import com.rhys.financetracker.domain.model.Frequency
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionTrackerTest {

    private fun monthly(name: String, pence: Long, day: Int, months: IntRange, account: Long = 1L) =
        months.map { Payment(name, pence, LocalDate.of(2026, it, day), "Subscriptions", account) }

    /** Something on the account every month to the end of September, so its statements run that far. */
    private val coverage = Payment("TESCO STORES 1234", 3_000, LocalDate.of(2026, 9, 30), "Groceries", 1L)

    private val payments = monthly("NETFLIX.COM 4471", 1_099, 15, 1..9) +
        monthly("SPOTIFY P1234", 1_199, 3, 1..6) +
        monthly("PUREGYM DD", 2_499, 20, 1..8) +
        // The weekly shop: regular, but never the same amount.
        (1..30).map { Payment("TESCO STORES 1234", 2_000L + it * 731 % 5_000, LocalDate.of(2026, 1, 1).plusWeeks(it.toLong()), "Groceries", 1L) } +
        coverage +
        // A card whose statements only reach the end of July.
        monthly("DISNEY PLUS", 799, 10, 3..7, account = 2L) +
        Payment("AMAZON MARKETPLACE", 1_500, LocalDate.of(2026, 7, 28), "Shopping", 2L)

    private val found = SubscriptionTracker.track(payments).associateBy { it.name }

    @Test
    fun `paid every month up to the latest statement is still being paid`() {
        val netflix = found.getValue("Netflix Com")
        assertEquals(Status.ACTIVE, netflix.status)
        assertEquals(Frequency.MONTHLY, netflix.frequency)
        assertEquals(1_099L, netflix.amountMinor)
        assertEquals(1_099L * 12, netflix.yearlyMinor)
        // Paid January to September, so the last nine of the twelve dots.
        assertEquals(List(3) { false } + List(9) { true }, netflix.monthsPaid)
    }

    @Test
    fun `a month missed may have stopped, three missed has stopped`() {
        assertEquals(Status.MISSED, found.getValue("Puregym").status)
        assertEquals(Status.STOPPED, found.getValue("Spotify").status)
    }

    @Test
    fun `a card not yet imported past July is judged on July`() {
        assertEquals(Status.ACTIVE, found.getValue("Disney Plus").status)
    }

    @Test
    fun `the weekly shop is not a subscription`() {
        assertNull(found["Tesco Stores"])
    }

    @Test
    fun `still being paid come first`() {
        val list = SubscriptionTracker.track(payments)
        assertTrue(list.indexOfFirst { it.status == Status.STOPPED } > list.indexOfLast { it.status == Status.ACTIVE })
    }
}
