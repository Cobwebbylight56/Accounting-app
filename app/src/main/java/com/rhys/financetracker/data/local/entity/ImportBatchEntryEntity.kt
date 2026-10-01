package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A payment an import added. Deleting the payment removes its line here too. */
@Entity(
    tableName = "import_batch_entries",
    primaryKeys = ["batch_id", "transaction_id"],
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batch_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transaction_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("transaction_id")],
)
data class ImportBatchEntryEntity(
    @ColumnInfo(name = "batch_id") val batchId: Long,
    @ColumnInfo(name = "transaction_id") val transactionId: Long,
)
