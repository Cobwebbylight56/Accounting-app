package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.RecurringDetector
import com.rhys.financetracker.data.importer.RecurringDetector.Payment
import com.rhys.financetracker.data.importer.SubscriptionTracker
import com.rhys.financetracker.data.importer.SubscriptionTracker.Status
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One regular payment shows once, whatever the bank called it along the
 * way — and two payments to one payee stay two.
 */
class RegularSeriesTest {

    private fun monthly(name: String, pence: Long, day: Int, months: IntRange, category: String = "Utilities") =
        months.map { Payment(name, pence, LocalDate.of(2026, it, day), category, 1L) }

    /** Statements on the account to the end of September. */
    private val coverage = Payment("TESCO STORES 1234", 3_000, LocalDate.of(2026, 9, 30), "Groceries", 1L)

    @Test
    fun `a payee the bank renamed is one regular payment, still being paid`() {
        val payments = monthly("VIRGIN MEDIA", 4_650, 5, 1..5) + monthly("VIRGIN MEDIA PYMTS", 4_650, 5, 6..9) + coverage
        val found = SubscriptionTracker.track(payments).filter { "Virgin" in it.name }
        assertEquals(1, found.size)
        assertEquals("Virgin Media Pymts", found.single().name)
        assertEquals(Status.ACTIVE, found.single().status)
        assertEquals(9, found.single().timesPaid)
    }

    @Test
    fun `a short name and a longer one for the same payee`() {
        val payments = monthly("SKY UK LIMITED", 3_200, 12, 1..4) + monthly("SKY DIGITAL 88231", 3_500, 12, 5..9) + coverage
        assertEquals(1, SubscriptionTracker.track(payments).count { "Sky" in it.name })
    }

    @Test
    fun `a price rise is the same payment`() {
        val payments = monthly("NETFLIX.COM", 1_099, 15, 1..6, "Subscriptions") +
            monthly("NETFLIX.COM", 1_299, 15, 7..9, "Subscriptions") + coverage
        val netflix = SubscriptionTracker.track(payments).filter { "Netflix" in it.name }
        assertEquals(1, netflix.size)
        assertEquals(1_299L, netflix.single().amountMinor)
        assertEquals(9, netflix.single().timesPaid)
    }

    @Test
    fun `two policies with one insurer stay two`() {
        val payments = monthly("ADMIRAL INSURANCE", 2_510, 1, 1..9, "Insurance") +
            monthly("ADMIRAL INSURANCE", 4_020, 3, 1..9, "Insurance") + coverage
        val admiral = SubscriptionTracker.track(payments).filter { "Admiral" in it.name }
        assertEquals(listOf(2_510L, 4_020L), admiral.map { it.amountMinor }.sorted())
        assertTrue(admiral.all { it.status == Status.ACTIVE && it.timesPaid == 9 })
    }

    @Test
    fun `two policies under two names stay two`() {
        val payments = monthly("AVIVA INSURANCE", 3_000, 1, 1..9, "Insurance") +
            monthly("AVIVA LIFE", 2_800, 8, 1..9, "Insurance") + coverage
        assertEquals(2, SubscriptionTracker.track(payments).count { "Aviva" in it.name })
    }

    @Test
    fun `the weekly shop at all sorts of amounts is still not split into bills`() {
        val shop = (1..30).map { Payment("TESCO STORES 1234", 2_000L + it * 731 % 5_000, LocalDate.of(2026, 1, 1).plusWeeks(it.toLong()), "Groceries", 1L) }
        assertTrue(SubscriptionTracker.track(shop).none { "Tesco" in it.name })
    }

    @Test
    fun `paypal in front of two different services does not make them one`() {
        val payments = monthly("PAYPAL *NETFLIX", 1_099, 2, 1..4, "Subscriptions") +
            monthly("PAYPAL *SPOTIFY", 1_199, 2, 5..9, "Subscriptions") + coverage
        assertEquals(2, SubscriptionTracker.track(payments).count { "Paypal" in it.name })
    }

    @Test
    fun `a bill set up under a slightly different name is not suggested again`() {
        val payments = monthly("VIRGIN MEDIA PYMTS DD", 4_890, 5, 1..9)
        val known = listOf(RecurringDetector.KnownBill("Virgin Media", 4_650, 1L))
        assertTrue(RecurringDetector.find(payments, known).none { "Virgin" in it.name })
    }

    @Test
    fun `names of one payee`() {
        assertTrue(RecurringDetector.samePayee("sky", "sky digital"))
        assertTrue(RecurringDetector.samePayee("virgin media", "virgin media pymts"))
        assertTrue(RecurringDetector.samePayee("ee", "ee limited mobile"))
        assertFalse(RecurringDetector.samePayee("paypal netflix", "paypal spotify"))
        assertFalse(RecurringDetector.samePayee("anthropic", "netflix com"))
    }
}
