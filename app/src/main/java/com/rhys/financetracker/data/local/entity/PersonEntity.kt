package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A member of the household: the user, a partner, a child, or a notional
 * "Joint" person that owns shared accounts.
 */
@Entity(
    tableName = "people",
    indices = [Index(value = ["name"], unique = true)],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** ARGB colour used to tint this person's rows and chart series. */
    @ColumnInfo(name = "color_hex") val colorHex: String,
    /** Set for the "Joint"/household person so it can be treated specially. */
    @ColumnInfo(name = "is_shared") val isShared: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    val notes: String? = null,
    /**
     * Yearly pay before tax, in minor units; null when not given.
     *
     * Kept per person rather than read from the statements because a
     * statement only shows what arrived — after tax, pension and anything
     * else taken at source — and the two together are what say how much of
     * somebody's pay they actually get.
     */
    @ColumnInfo(name = "gross_yearly_income_minor") val grossYearlyIncomeMinor: Long? = null,
    /** Yearly take-home pay after tax, in minor units; null when not given. */
    @ColumnInfo(name = "net_yearly_income_minor") val netYearlyIncomeMinor: Long? = null,
    /**
     * Names their statements have been addressed to, as printed — "MRS H
     * JONES" — separated by `|`. Learned when somebody confirms a statement
     * is theirs, so a name the app could not work out is recognised next
     * time. See [com.rhys.financetracker.data.importer.StatementOwner].
     */
    @ColumnInfo(name = "statement_names") val statementNames: String? = null,
    /** Archived records stay in the database and in history but are hidden from pickers. */
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
) {
    /** The names learned from their statements; see [statementNames]. */
    val knownStatementNames: List<String>
        get() = statementNames?.split(STATEMENT_NAME_SEPARATOR)
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** This person, also answering to [printed] on a statement. */
    fun withStatementName(printed: String): PersonEntity {
        val clean = printed.trim().replace(STATEMENT_NAME_SEPARATOR, " ")
        if (clean.isEmpty() || knownStatementNames.any { it.equals(clean, ignoreCase = true) }) {
            return this
        }
        return copy(
            statementNames = (knownStatementNames + clean).joinToString(STATEMENT_NAME_SEPARATOR),
        )
    }

    companion object {
        const val STATEMENT_NAME_SEPARATOR = "|"
    }
}
