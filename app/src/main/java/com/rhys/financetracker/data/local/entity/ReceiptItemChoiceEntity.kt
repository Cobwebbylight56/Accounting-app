package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The category the user put a receipt item in, by the item as the receipt
 * words it, so the same item goes there by itself next time.
 */
@Entity(
    tableName = "receipt_item_choices",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("category_id")],
)
data class ReceiptItemChoiceEntity(
    /** The item's words, lower case, without prices, codes or sizes; see ItemCategoriser.keyOf. */
    @PrimaryKey @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
)
