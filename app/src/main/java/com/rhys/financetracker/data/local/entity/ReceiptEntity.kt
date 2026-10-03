package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * A photo or screenshot of a receipt, kept with the payment it is for.
 *
 * The picture itself is a file in the app's own storage, named [fileName];
 * this row holds what was read off it. Deleting the payment removes its
 * receipts too.
 */
@Entity(
    tableName = "receipts",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transaction_id")],
)
data class ReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    /** The shop, total and date as read off the picture, when they could be. */
    val shop: String? = null,
    @ColumnInfo(name = "total_minor") val totalMinor: Long? = null,
    @ColumnInfo(name = "receipt_date") val receiptDate: LocalDate? = null,
    /** Everything read off it, so a payment can be found by what was bought. */
    @ColumnInfo(name = "read_text") val readText: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long = Instant.now().toEpochMilli(),
)
