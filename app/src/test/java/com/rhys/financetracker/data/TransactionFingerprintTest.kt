package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The fingerprint decides whether a row from a statement is one already held.
 * Getting it wrong is costly in both directions: too loose and a real second
 * purchase disappears, too tight and every re-import doubles the ledger.
 */
class TransactionFingerprintTest {

    private val date = LocalDate.of(2026, 3, 1)

    private fun fingerprint(
        accountId: Long = 1L,
        on: LocalDate = date,
        amountMinor: Long = 4215L,
        type: TransactionType = TransactionType.EXPENSE,
        description: String = "TESCO STORES 3294",
    ) = TransactionFingerprint.of(accountId, on, amountMinor, type, description)

    @Test
    fun `the same transaction always gives the same fingerprint`() {
        assertEquals(fingerprint(), fingerprint())
    }

    @Test
    fun `spacing and capitals do not change it`() {
        // The same export, downloaded twice, can differ in exactly these ways.
        assertEquals(fingerprint(), fingerprint(description = "Tesco Stores 3294"))
        assertEquals(fingerprint(), fingerprint(description = "TESCO  STORES   3294"))
        assertEquals(fingerprint(), fingerprint(description = " tesco stores 3294 "))
    }

    @Test
    fun `punctuation is ignored but digits are not`() {
        assertEquals(fingerprint(), fingerprint(description = "TESCO STORES, 3294"))
        // Two branches of one chain are different shops, not one entry.
        assertNotEquals(fingerprint(), fingerprint(description = "TESCO STORES 3295"))
    }

    @Test
    fun `each part of the identity changes it`() {
        assertNotEquals(fingerprint(), fingerprint(accountId = 2L))
        assertNotEquals(fingerprint(), fingerprint(on = date.plusDays(1)))
        assertNotEquals(fingerprint(), fingerprint(amountMinor = 4216L))
        assertNotEquals(fingerprint(), fingerprint(type = TransactionType.INCOME))
        assertNotEquals(fingerprint(), fingerprint(description = "SAINSBURYS 3294"))
    }

    @Test
    fun `money in and money out of the same amount stay apart`() {
        // A £50 transfer out and a £50 refund in on one day are two entries.
        assertNotEquals(
            fingerprint(amountMinor = 5000L, type = TransactionType.EXPENSE),
            fingerprint(amountMinor = 5000L, type = TransactionType.INCOME),
        )
    }

    @Test
    fun `it is a fixed length regardless of the description`() {
        assertEquals(32, fingerprint().length)
        assertEquals(32, fingerprint(description = "A").length)
        assertEquals(32, fingerprint(description = "X".repeat(500)).length)
    }

    @Test
    fun `normalising collapses everything that is not a letter or digit`() {
        assertEquals(
            "tesco stores 3294",
            TransactionFingerprint.normaliseDescription("  TESCO*STORES/3294  "),
        )
        assertEquals("", TransactionFingerprint.normaliseDescription("   "))
    }
}
