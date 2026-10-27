package com.rhys.financetracker.domain

import com.rhys.financetracker.domain.tips.TipBook
import com.rhys.financetracker.domain.tips.TipPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tips one at a time; what's new first, and always pointed out when it's newer than "off". */
class TipBookTest {

    @Test
    fun `what's new comes before everyday tips, one at a time`() {
        assertEquals("home-tips", TipBook.next(TipPlace.HOME, emptySet(), tipsOn = true, newOffAt = null)?.id)
        assertEquals("home-left-over", TipBook.next(TipPlace.HOME, setOf("home-tips"), tipsOn = true, newOffAt = null)?.id)
        assertEquals("home-add", TipBook.next(TipPlace.HOME, setOf("home-tips", "home-left-over"), tipsOn = true, newOffAt = null)?.id)
    }

    @Test
    fun `with tips off, new things are still pointed out`() {
        assertEquals("edit-account", TipBook.next(TipPlace.PAYMENT_EDIT, emptySet(), tipsOn = false, newOffAt = null)?.id)
        assertNull(TipBook.next(TipPlace.ACCOUNTS, emptySet(), tipsOn = false, newOffAt = null))
    }

    @Test
    fun `turned off, only what's newer than then is pointed out`() {
        assertNull(TipBook.next(TipPlace.PAYMENT_EDIT, emptySet(), tipsOn = false, newOffAt = "2.5.0"))
        assertEquals("live-once", TipBook.next(TipPlace.LIVE_PAYMENTS, emptySet(), tipsOn = false, newOffAt = "2.4.0")?.id)
    }

    @Test
    fun `a New mark shows until its tip is seen`() {
        assertTrue(TipBook.pointsOut("edit-one-off", emptySet(), newOffAt = null))
        assertFalse(TipBook.pointsOut("edit-one-off", setOf("edit-one-off"), newOffAt = null))
        assertFalse(TipBook.pointsOut("home-add", emptySet(), newOffAt = null))
        assertFalse(TipBook.pointsOut("no-such-tip", emptySet(), newOffAt = null))
    }

    @Test
    fun `every tip has its own id, and every new one a real version`() {
        val ids = TipBook.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        TipBook.ALL.mapNotNull { it.newIn }.forEach { version ->
            assertTrue(version, com.rhys.financetracker.data.releases.ReleaseNotes.ALL.any { it.version == version })
        }
    }
}
