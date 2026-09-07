package com.rhys.financetracker.data

import com.rhys.financetracker.data.importer.StatementOwner
import com.rhys.financetracker.data.local.entity.PersonEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whose statement is this? The account holder is printed on every one, and it
 * is the only thing in the file that says which person's money it is.
 */
class StatementOwnerTest {

    private fun person(id: Long, name: String) =
        PersonEntity(id = id, name = name, colorHex = "#455A64")

    private val household = listOf(
        person(1L, "Rhys Evans"),
        person(2L, "Hannah Evans"),
        person(3L, "Joint"),
    )

    private fun nationwide(addressee: String) = listOf(
        "Nationwide Building Society",
        "Nationwide House, Pipers Way, Swindon SN38 1NW",
        addressee,
        "1 Somewhere Street",
        "Cardiff",
        "Date       Details                    Payments   Receipts   Balance",
    )

    @Test
    fun `initials and a surname name the person`() {
        // How a statement actually addresses somebody: never a full first name.
        assertEquals("Rhys Evans", StatementOwner.detect(nationwide("MR R M W EVANS"), household)?.name)
        assertEquals("Hannah Evans", StatementOwner.detect(nationwide("MISS H EVANS"), household)?.name)
    }

    @Test
    fun `a full first name works too`() {
        assertEquals(
            "Rhys Evans",
            StatementOwner.detect(nationwide("Mr Rhys Evans"), household)?.name,
        )
    }

    @Test
    fun `a surname alone settles nothing in a household that shares one`() {
        // Both people match, so there is no answer — and reporting the first
        // would file a statement against a coin toss.
        assertNull(StatementOwner.detect(nationwide("THE EVANS FAMILY"), household))
    }

    @Test
    fun `a joint account naming both people is left to the user`() {
        assertNull(StatementOwner.detect(nationwide("MR R M W EVANS & MISS H EVANS"), household))
    }

    @Test
    fun `a name nobody in the app has is no answer`() {
        assertNull(StatementOwner.detect(nationwide("MRS J SMITH"), household))
    }

    @Test
    fun `a payee further down the statement is not the account holder`() {
        // "EVANS" appearing as a payment on row 200 says nothing about whose
        // account it is, so only the address block is read.
        val lines = List(40) { "01 Jan 2026 CARD PAYMENT $it 4.00" } + "MR R M W EVANS"
        assertNull(StatementOwner.detect(lines, household))
    }

    @Test
    fun `a one word name has to appear in full`() {
        // "Joint" must never be picked up from somebody's post.
        assertNull(StatementOwner.detect(nationwide("MR R M W EVANS"), listOf(person(3L, "Joint"))))
        assertEquals(
            "Joint",
            StatementOwner.detect(nationwide("JOINT ACCOUNT"), listOf(person(3L, "Joint")))?.name,
        )
    }

    @Test
    fun `nothing to go on is no answer rather than a guess`() {
        assertNull(StatementOwner.detect(emptyList(), household))
        assertNull(StatementOwner.detect(nationwide("MR R M W EVANS"), emptyList()))
    }
}
