package com.rhys.financetracker.data

import com.rhys.financetracker.data.local.dao.TransactionFilter
import com.rhys.financetracker.data.local.dao.TransactionQuery
import com.rhys.financetracker.data.local.dao.TransactionSort
import com.rhys.financetracker.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The search query is assembled at runtime, so these tests check the two things
 * that matter: that it is built correctly, and that user input is always bound
 * as an argument rather than pasted into the SQL.
 */
class TransactionQueryTest {

    @Test
    fun `an empty filter still hides archived rows`() {
        val query = TransactionQuery.build(TransactionFilter())
        assertTrue(query.sql.contains("t.is_archived = 0"))
    }

    @Test
    fun `including archived rows drops the archived condition`() {
        val query = TransactionQuery.build(TransactionFilter(includeArchived = true))
        assertFalse(query.sql.contains("t.is_archived = 0"))
    }

    @Test
    fun `search text is bound, never concatenated`() {
        // A description containing a quote and a comment marker must not be
        // able to change the shape of the statement.
        val hostile = "'; DROP TABLE transactions; --"
        val query = TransactionQuery.build(TransactionFilter(text = hostile))
        assertFalse(query.sql.contains("DROP TABLE"))
        assertTrue(query.sql.contains("LIKE ?"))
    }

    @Test
    fun `an id list produces one placeholder per id`() {
        val query = TransactionQuery.build(TransactionFilter(categoryIds = setOf(1L, 2L, 3L)))
        assertTrue(query.sql.contains("t.category_id IN (?, ?, ?)"))
    }

    @Test
    fun `an empty id set produces no IN clause at all`() {
        // SQLite rejects "IN ()", which is exactly why this is built by hand
        // rather than as one fixed statement.
        val query = TransactionQuery.build(TransactionFilter(accountIds = emptySet()))
        assertFalse(query.sql.contains("IN ()"))
    }

    @Test
    fun `dates and amounts become conditions`() {
        val query = TransactionQuery.build(
            TransactionFilter(
                dateFrom = LocalDate.of(2026, 1, 1),
                dateTo = LocalDate.of(2026, 3, 31),
                minAmountMinor = 1_000L,
                maxAmountMinor = 50_000L,
            ),
        )
        assertTrue(query.sql.contains("t.date >= ?"))
        assertTrue(query.sql.contains("t.date <= ?"))
        assertTrue(query.sql.contains("t.amount_minor >= ?"))
        assertTrue(query.sql.contains("t.amount_minor <= ?"))
    }

    @Test
    fun `the sort order is applied`() {
        val query = TransactionQuery.build(
            TransactionFilter(sort = TransactionSort.AMOUNT_DESC),
        )
        assertTrue(query.sql.contains("ORDER BY t.amount_minor DESC"))
    }

    @Test
    fun `a limit is applied when asked for`() {
        val query = TransactionQuery.build(TransactionFilter(limit = 25))
        assertTrue(query.sql.contains("LIMIT 25"))
    }

    @Test
    fun `an empty filter reports itself as empty`() {
        assertTrue(TransactionFilter().isEmpty)
        assertEquals(0, TransactionFilter().activeFilterCount)
    }

    @Test
    fun `the active filter count reflects what is set`() {
        val filter = TransactionFilter(
            text = "fuel",
            types = setOf(TransactionType.EXPENSE),
            dateFrom = LocalDate.of(2026, 1, 1),
        )
        assertFalse(filter.isEmpty)
        assertEquals(3, filter.activeFilterCount)
    }
}
