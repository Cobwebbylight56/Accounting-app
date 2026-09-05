package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * A single movement of money — the atom of the whole application.
 *
 * Sign convention: [amountMinor] is always **positive**.  The direction is
 * carried by [type], which keeps reports unambiguous and stops a mistyped minus
 * sign from silently turning an expense into income.
 *
 * A `TRANSFER` uses [accountId] as the source and [transferAccountId] as the
 * destination; it is excluded from income and expense totals.
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("transfer_account_id"),
        Index("category_id"),
        Index("person_id"),
        Index("date"),
        Index("recurring_rule_id"),
        Index("savings_goal_id"),
        // The dashboard and reports always filter by date and type together.
        Index(value = ["date", "type"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** Never negative; see the class documentation. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: TransactionType,
    val date: LocalDate,
    val description: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "transfer_account_id") val transferAccountId: Long? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    /** Who is responsible; defaults to the account owner when left null. */
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    /** Set when this entry was generated from a [RecurringRuleEntity]. */
    @ColumnInfo(name = "recurring_rule_id") val recurringRuleId: Long? = null,
    /** Set when the entry contributes to a savings goal. */
    @ColumnInfo(name = "savings_goal_id") val savingsGoalId: Long? = null,
    val notes: String? = null,
    /** False for auto-generated entries awaiting confirmation of a variable amount. */
    @ColumnInfo(name = "is_confirmed") val isConfirmed: Boolean = true,
    /** True once the money has actually left/entered the account. */
    @ColumnInfo(name = "is_cleared") val isCleared: Boolean = true,
    /** Free-text tags, comma separated, searchable. */
    val tags: String? = null,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
) {
    /**
     * The effect this transaction has on [accountId]'s balance, in minor units.
     * Income adds, expenses and outgoing transfers subtract.
     */
    val signedAmountForSourceAccount: Long
        get() = when (type) {
            TransactionType.INCOME -> amountMinor
            TransactionType.EXPENSE, TransactionType.TRANSFER -> -amountMinor
        }
}
