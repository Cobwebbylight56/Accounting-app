package com.rhys.financetracker.data.local.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate

/**
 * Every way the transaction list can be narrowed down.  A null or empty field
 * means "do not filter on this".
 */
data class TransactionFilter(
    /** Matched against description, notes and tags. */
    val text: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val accountIds: Set<Long> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val personIds: Set<Long> = emptySet(),
    val types: Set<TransactionType> = emptySet(),
    val minAmountMinor: Long? = null,
    val maxAmountMinor: Long? = null,
    val savingsGoalId: Long? = null,
    val recurringRuleId: Long? = null,
    val onlyUnconfirmed: Boolean = false,
    val onlyUncleared: Boolean = false,
    val includeArchived: Boolean = false,
    val sort: TransactionSort = TransactionSort.DATE_DESC,
    /** 0 means "no limit". */
    val limit: Int = 0,
) {
    /** True when nothing is being filtered, so the UI can hide the "clear" action. */
    val isEmpty: Boolean
        get() = text.isNullOrBlank() && dateFrom == null && dateTo == null &&
            accountIds.isEmpty() && categoryIds.isEmpty() && personIds.isEmpty() &&
            types.isEmpty() && minAmountMinor == null && maxAmountMinor == null &&
            savingsGoalId == null && recurringRuleId == null &&
            !onlyUnconfirmed && !onlyUncleared && !includeArchived

    /** How many distinct filters are active, for the badge on the filter button. */
    val activeFilterCount: Int
        get() = listOf(
            !text.isNullOrBlank(),
            dateFrom != null || dateTo != null,
            accountIds.isNotEmpty(),
            categoryIds.isNotEmpty(),
            personIds.isNotEmpty(),
            types.isNotEmpty(),
            minAmountMinor != null || maxAmountMinor != null,
            savingsGoalId != null,
            onlyUnconfirmed,
            onlyUncleared,
            includeArchived,
        ).count { it }
}

enum class TransactionSort(val displayName: String, internal val orderBy: String) {
    DATE_DESC("Newest first", "t.date DESC, t.id DESC"),
    DATE_ASC("Oldest first", "t.date ASC, t.id ASC"),
    AMOUNT_DESC("Largest first", "t.amount_minor DESC, t.date DESC"),
    AMOUNT_ASC("Smallest first", "t.amount_minor ASC, t.date DESC"),
    NAME_ASC("Name A-Z", "t.description COLLATE NOCASE ASC"),
    NAME_DESC("Name Z-A", "t.description COLLATE NOCASE DESC"),
}

/**
 * Builds the parameterised SQL for [TransactionDao.searchRaw].
 *
 * All user input is bound as an argument — never concatenated — so a
 * description containing a quote or a `--` cannot break or subvert the query.
 * Only the identifiers this file controls (column names, sort order) are
 * interpolated.
 */
object TransactionQuery {

    fun build(filter: TransactionFilter): SupportSQLiteQuery {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!filter.includeArchived) {
            conditions += "t.is_archived = 0"
        }

        filter.text?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            val pattern = "%$text%"
            conditions += """
                (t.description LIKE ? COLLATE NOCASE
                 OR IFNULL(t.notes, '') LIKE ? COLLATE NOCASE
                 OR IFNULL(t.tags, '') LIKE ? COLLATE NOCASE
                 OR IFNULL(a.name, '') LIKE ? COLLATE NOCASE
                 OR IFNULL(c.name, '') LIKE ? COLLATE NOCASE
                 OR IFNULL(p.name, '') LIKE ? COLLATE NOCASE)
            """.trimIndent()
            repeat(6) { args += pattern }
        }

        filter.dateFrom?.let {
            conditions += "t.date >= ?"
            args += it.toString()
        }
        filter.dateTo?.let {
            conditions += "t.date <= ?"
            args += it.toString()
        }

        if (filter.accountIds.isNotEmpty()) {
            val placeholders = filter.accountIds.joinToString(", ") { "?" }
            conditions += "(t.account_id IN ($placeholders) OR t.transfer_account_id IN ($placeholders))"
            args += filter.accountIds
            args += filter.accountIds
        }

        if (filter.categoryIds.isNotEmpty()) {
            conditions += "t.category_id IN (${filter.categoryIds.joinToString(", ") { "?" }})"
            args += filter.categoryIds
        }

        if (filter.personIds.isNotEmpty()) {
            conditions += "COALESCE(t.person_id, a.person_id) IN " +
                "(${filter.personIds.joinToString(", ") { "?" }})"
            args += filter.personIds
        }

        if (filter.types.isNotEmpty()) {
            conditions += "t.type IN (${filter.types.joinToString(", ") { "?" }})"
            args += filter.types.map { it.name }
        }

        filter.minAmountMinor?.let {
            conditions += "t.amount_minor >= ?"
            args += it
        }
        filter.maxAmountMinor?.let {
            conditions += "t.amount_minor <= ?"
            args += it
        }
        filter.savingsGoalId?.let {
            conditions += "t.savings_goal_id = ?"
            args += it
        }
        filter.recurringRuleId?.let {
            conditions += "t.recurring_rule_id = ?"
            args += it
        }
        if (filter.onlyUnconfirmed) conditions += "t.is_confirmed = 0"
        if (filter.onlyUncleared) conditions += "t.is_cleared = 0"

        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        val limit = if (filter.limit > 0) "LIMIT ${filter.limit}" else ""

        val sql = """
            SELECT ${TransactionDao.DETAIL_COLUMNS}
            ${TransactionDao.DETAIL_JOINS}
            $where
            ORDER BY ${filter.sort.orderBy}
            $limit
        """.trimIndent()

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
