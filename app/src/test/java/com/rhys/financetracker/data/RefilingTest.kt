package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where an entry the app filed by itself goes when the app re-sorts. */
class RefilingTest {

    private fun better(text: String, current: String?, learned: Map<String, String> = emptyMap()) =
        Refiling.better(text, TransactionType.EXPENSE, current, learned)

    @Test
    fun `the improved list moves what the old one got wrong`() {
        assertEquals("Fuel", better("CONTACTLESS PAYMENT TESCO PFS 3839", "Groceries"))
        assertEquals("Fuel", better("TESCO PAY AT PUMP 4471", "Groceries"))
        assertEquals("Shopping", better("ASDA LIVING", "Groceries"))
        assertEquals("Hobbies", better("HOBBYCRAFT CARDIFF", "Shopping"))
    }

    @Test
    fun `what the user filed the payee under wins over the list`() {
        val learned = mapOf("tesco pfs 3012" to "Groceries")
        assertNull(better("TESCO PFS 4471", "Groceries", learned))
        assertEquals("Groceries", better("TESCO PFS 4471", "Fuel", learned))
    }

    @Test
    fun `nothing moves without a reason`() {
        // Already right.
        assertNull(better("SHELL NEWPORT", "Fuel"))
        // A shop the list does not know.
        assertNull(better("ACME WIDGETS LTD", "Groceries"))
        // No payee at all.
        assertNull(better("CONTACTLESS PAYMENT", "Groceries"))
        // Income is never re-sorted by a list of shops.
        assertNull(Refiling.better("TESCO PFS 1", TransactionType.INCOME, "Refunds", emptyMap()))
    }

    @Test
    fun `the list never takes something out of a specific category into a loose one`() {
        val loose = better("FASTER PAYMENT TO J SMITH", null)
        if (loose != null && loose in Refiling.WEAK) {
            assertNull(better("FASTER PAYMENT TO J SMITH", "Gifts"))
        }
    }
}
