package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One part of a payment split across categories: the T-shirt in a Tesco
 * shop, the drink and crisps on a fuel receipt.
 *
 * A split payment's parts always add up to the payment, so totals by
 * category count each part in its own category and the payment's total is
 * unchanged. A part with no category counts under the payment's own — that
 * is the "rest of the shop".
 */
@Entity(
    tableName = "transaction_splits",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("transaction_id"), Index("category_id")],
)
data class TransactionSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    /** Null means the payment's own category. */
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** What it was: an item off the receipt, or "Rest of the shop". */
    val label: String,
    /** Its place in the list, as on the receipt. */
    val position: Int = 0,
)
