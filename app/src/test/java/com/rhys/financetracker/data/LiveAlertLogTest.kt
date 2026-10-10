package com.rhys.financetracker.data

import com.rhys.financetracker.data.prefs.LiveAlertLogEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAlertLogTest {

    @Test
    fun `what was heard is kept as it was, with tabs and new lines flattened`() {
        val entries = listOf(
            LiveAlertLogEntry(2_000L, "Nationwide", "Added", "£12.50 · TESCO STORES · Groceries"),
            LiveAlertLogEntry(1_000L, "Lloyds", "Not read as a payment", "You've\tspent\n£4.20"),
        )
        val back = LiveAlertLogEntry.parse(LiveAlertLogEntry.write(entries))
        assertEquals(2, back.size)
        assertEquals(entries[0], back[0])
        assertEquals("You've spent £4.20", back[1].detail)
    }

    @Test
    fun `nothing stored is an empty list`() {
        assertEquals(emptyList<LiveAlertLogEntry>(), LiveAlertLogEntry.parse(null))
        assertEquals(emptyList<LiveAlertLogEntry>(), LiveAlertLogEntry.parse("not a log"))
    }
}
