package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.RecurringDetector.Payment
import com.rhys.financetracker.data.importer.SubscriptionPlan
import com.rhys.financetracker.data.importer.SubscriptionTracker
import com.rhys.financetracker.data.importer.SubscriptionTracker.Status
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a subscription started and how long it runs, so the app knows when it ends. */
class SubscriptionPlanTest {

    private val today = LocalDate.of(2026, 10, 11)

    private fun monthly(name: String, pence: Long, day: Int, months: IntRange, account: Long = 1L) =
        months.map { Payment(name, pence, LocalDate.of(2026, it, day), "Subscriptions", account, id = it.toLong() * 100 + day) }

    private val coverage = Payment("TESCO STORES 1234", 3_000, LocalDate.of(2026, 10, 8), "Groceries", 1L)

    @Test
    fun `cancelled, it has ended`() {
        val payments = monthly("UBER ONE MEMBERSHIP", 499, 22, 1..9) + coverage
        val plans = mapOf("uber one membership" to SubscriptionPlan("uber one membership", cancelledOn = LocalDate.of(2026, 9, 25)))
        val uber = SubscriptionTracker.track(payments, plans, today).single { it.name == "Uber One Membership" }
        assertEquals(Status.ENDED, uber.status)
        assertFalse(uber.status.isGoing)
    }

    @Test
    fun `a twelve month membership ends a year after it started`() {
        val payments = monthly("GYM MEMBERSHIP", 2_500, 5, 1..10) + coverage
        val started = LocalDate.of(2026, 1, 5)
        val plans = mapOf("gym membership" to SubscriptionPlan("gym membership", startedOn = started, months = 12))
        val gym = SubscriptionTracker.track(payments, plans, today).single { it.name == "Gym Membership" }
        assertEquals(Status.ACTIVE, gym.status)
        assertEquals(LocalDate.of(2027, 1, 5), gym.endsOn)
        // Paid 5 October; still to come 5 November and 5 December.
        assertEquals(2, gym.paymentsLeft)
    }

    @Test
    fun `one whose length has run out has ended`() {
        val payments = monthly("GYM MEMBERSHIP", 2_500, 5, 1..10) + coverage
        val plans = mapOf("gym membership" to SubscriptionPlan("gym membership", startedOn = LocalDate.of(2025, 10, 5), months = 12))
        val gym = SubscriptionTracker.track(payments, plans, today).single { it.name == "Gym Membership" }
        assertEquals(Status.ENDED, gym.status)
    }

    @Test
    fun `until cancelled, it has no end`() {
        val payments = monthly("AUDIBLE UK", 899, 2, 1..10) + coverage
        val plans = mapOf("audible" to SubscriptionPlan("audible", startedOn = LocalDate.of(2024, 3, 2)))
        val audible = SubscriptionTracker.track(payments, plans, today).single { it.name == "Audible" }
        assertEquals(Status.ACTIVE, audible.status)
        assertNull(audible.endsOn)
        assertNull(audible.paymentsLeft)
    }

    @Test
    fun `a row dated in the future doesn't make the rest look stopped`() {
        // A December row read a year ahead: the card's statements seemed to
        // reach December, and Audible, last paid in September, had "stopped".
        val payments = monthly("AUDIBLE UK", 899, 2, 1..9, account = 2L) +
            Payment("AMAZON", 2_450, LocalDate.of(2026, 9, 28), "Shopping", 2L) +
            Payment("AMAZON", 1_200, LocalDate.of(2026, 12, 15), "Shopping", 2L)
        val audible = SubscriptionTracker.track(payments, today = today).single { it.name == "Audible" }
        assertTrue(audible.status.isGoing)
        assertEquals(YearMonth.of(2026, 10), audible.months.last())
    }

    @Test
    fun `what was really paid each month, and which entries`() {
        val payments = monthly("AUDIBLE UK", 899, 2, 7..9) + coverage
        val audible = SubscriptionTracker.track(payments, today = today).single { it.name == "Audible" }
        assertEquals(899L, audible.paidByMonth[YearMonth.of(2026, 8)])
        assertEquals(setOf(702L, 802L, 902L), audible.paymentIds)
        assertEquals(LocalDate.of(2026, 7, 2), audible.firstDate)
    }

    @Test
    fun `plans are kept and read back`() {
        val plans = listOf(
            SubscriptionPlan("audible uk", startedOn = LocalDate.of(2024, 3, 2)),
            SubscriptionPlan("gym membership", startedOn = LocalDate.of(2026, 1, 5), months = 12),
            SubscriptionPlan("uber one membership", cancelledOn = LocalDate.of(2026, 9, 25)),
        )
        assertEquals(plans.associateBy { it.key }, SubscriptionPlan.parse(SubscriptionPlan.write(plans)))
        assertTrue(SubscriptionPlan.parse(null).isEmpty())
        assertTrue(SubscriptionPlan.parse("rubbish").isEmpty())
    }
}
