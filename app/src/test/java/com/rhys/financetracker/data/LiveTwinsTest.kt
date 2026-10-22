package com.rhys.financetracker.data

import com.rhys.financetracker.data.live.LiveTwins
import com.rhys.financetracker.domain.model.TransactionType.EXPENSE
import com.rhys.financetracker.domain.model.TransactionType.INCOME
import com.rhys.financetracker.domain.model.TransactionType.TRANSFER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One payment told by two apps is added once; a move told by both banks is one move. */
class LiveTwinsTest {

    private val wallet = "com.google.android.apps.walletnfcrel"
    private val lloyds = "com.grppl.android.shell.CMBlloydsTSB73"
    private val nationwide = "co.uk.Nationwide.Mobile"
    private val cardApp = "com.example.cardcompany"
    private val minute = 60_000L
    private val now = 1_000_000_000L

    private fun row(id: Long, app: String, type: com.rhys.financetracker.domain.model.TransactionType, account: Long, ago: Long) =
        LiveTwins.Row(id, "live:$app:${now - ago}:450:${type.name}", type, account, now - ago)

    @Test
    fun `a wallet tap and the card's own app, seconds apart, are one payment`() {
        val tap = row(1, wallet, EXPENSE, 7, ago = 2_000)
        assertEquals(1L, LiveTwins.sameAs(cardApp, EXPENSE, now, listOf(tap))?.id)
    }

    @Test
    fun `the bank's alert hours after the wallet's is still the same payment`() {
        val tap = row(1, wallet, EXPENSE, 7, ago = 5 * 60 * minute)
        assertEquals(1L, LiveTwins.sameAs(lloyds, EXPENSE, now, listOf(tap))?.id)
    }

    @Test
    fun `two banks spending the same a while apart are two payments`() {
        val coffee = row(1, lloyds, EXPENSE, 1, ago = 10 * minute)
        assertNull(LiveTwins.sameAs(nationwide, EXPENSE, now, listOf(coffee)))
    }

    @Test
    fun `the same app again a while later is a new payment`() {
        val coffee = row(1, lloyds, EXPENSE, 1, ago = 2 * 60 * minute)
        assertNull(LiveTwins.sameAs(lloyds, EXPENSE, now, listOf(coffee)))
    }

    @Test
    fun `money in is never the twin of money out`() {
        val out = row(1, wallet, EXPENSE, 7, ago = minute)
        assertNull(LiveTwins.sameAs(lloyds, INCOME, now, listOf(out)))
    }

    @Test
    fun `a card paid off, told again by the card, is already in`() {
        val paidOff = row(1, lloyds, TRANSFER, 1, ago = minute)
        assertEquals(1L, LiveTwins.sameAs(cardApp, INCOME, now, listOf(paidOff))?.id)
    }

    @Test
    fun `out of Lloyds and into Nationwide minutes later is one move`() {
        val sent = row(1, lloyds, EXPENSE, 1, ago = 4 * minute)
        assertEquals(1L, LiveTwins.otherSide(nationwide, INCOME, 2, now, listOf(sent), own = setOf(1, 2))?.id)
        val received = row(2, nationwide, INCOME, 2, ago = 4 * minute)
        assertEquals(2L, LiveTwins.otherSide(lloyds, EXPENSE, 1, now, listOf(received), own = setOf(1, 2))?.id)
    }

    @Test
    fun `not a move when it's the same app, the same account, a card, or too long apart`() {
        val sent = row(1, lloyds, EXPENSE, 1, ago = 4 * minute)
        assertNull(LiveTwins.otherSide(lloyds, INCOME, 2, now, listOf(sent), own = setOf(1, 2)))
        assertNull(LiveTwins.otherSide(nationwide, INCOME, 1, now, listOf(sent), own = setOf(1, 2)))
        assertNull(LiveTwins.otherSide(nationwide, INCOME, 3, now, listOf(sent), own = setOf(1, 2)))
        val longAgo = row(1, lloyds, EXPENSE, 1, ago = 2 * 60 * minute)
        assertNull(LiveTwins.otherSide(nationwide, INCOME, 2, now, listOf(longAgo), own = setOf(1, 2)))
        val walletTap = row(1, wallet, EXPENSE, 1, ago = minute)
        assertNull(LiveTwins.otherSide(nationwide, INCOME, 2, now, listOf(walletTap), own = setOf(1, 2)))
    }

    private fun added(id: Long, app: String, type: com.rhys.financetracker.domain.model.TransactionType, account: Long, at: Long, pence: Long = 450) =
        LiveTwins.Row(id, "live:$app:$at:$pence:${type.name}", type, account, at, pence)

    @Test
    fun `doubles added before are found, and the bank's kept over the wallet's`() {
        val rows = listOf(
            added(1, wallet, EXPENSE, 7, at = now),
            added(2, cardApp, EXPENSE, 9, at = now + 800),
            added(3, lloyds, EXPENSE, 1, at = now + 5 * 60 * minute, pence = 999),
            added(4, wallet, EXPENSE, 7, at = now + 5 * 60 * minute + 1_000, pence = 999),
        )
        assertEquals(
            listOf(LiveTwins.Doubled(extra = 1, kept = 2), LiveTwins.Doubled(extra = 4, kept = 3)),
            LiveTwins.doubled(rows, own = setOf(1, 7, 9)),
        )
    }

    @Test
    fun `a move told by both banks before is made one`() {
        val rows = listOf(added(1, lloyds, EXPENSE, 1, at = now), added(2, nationwide, INCOME, 2, at = now + 3 * minute))
        assertEquals(listOf(LiveTwins.Doubled(extra = 2, kept = 1, moveFrom = 2)), LiveTwins.doubled(rows, own = setOf(1, 2)))
    }

    @Test
    fun `different payments are left alone`() {
        val rows = listOf(
            added(1, lloyds, EXPENSE, 1, at = now),
            added(2, lloyds, EXPENSE, 1, at = now + 60 * minute),
            added(3, nationwide, EXPENSE, 2, at = now + 61 * minute, pence = 451),
        )
        assertEquals(emptyList<LiveTwins.Doubled>(), LiveTwins.doubled(rows, own = setOf(1, 2)))
    }
}
