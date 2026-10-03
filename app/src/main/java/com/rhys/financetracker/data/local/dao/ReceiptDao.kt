package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.rhys.financetracker.data.local.entity.ReceiptEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface ReceiptDao {

    @Insert
    suspend fun insert(receipt: ReceiptEntity): Long

    @Delete
    suspend fun delete(receipt: ReceiptEntity)

    @Query("SELECT * FROM receipts WHERE transaction_id = :transactionId ORDER BY added_at ASC")
    fun observeFor(transactionId: Long): Flow<List<ReceiptEntity>>

    /** What was read off the newest receipt on [transactionId] that had any writing on it. */
    @Query(
        """
        SELECT read_text FROM receipts
        WHERE transaction_id = :transactionId AND read_text IS NOT NULL AND read_text != ''
        ORDER BY added_at DESC LIMIT 1
        """,
    )
    suspend fun latestTextFor(transactionId: Long): String?

    /** Every picture still in use, to tidy away any left behind. */
    @Query("SELECT file_name FROM receipts")
    suspend fun fileNames(): List<String>

    /**
     * Payments of [amountMinor] between two dates that a receipt for that
     * amount could be for, nearest to [around] first.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE is_archived = 0 AND amount_minor = :amountMinor AND type IN ('EXPENSE', 'TRANSFER')
          AND date BETWEEN :from AND :to
        ORDER BY ABS(julianday(date) - julianday(:around)) ASC, id DESC
        LIMIT 10
        """,
    )
    suspend fun paymentsFor(amountMinor: Long, from: LocalDate, to: LocalDate, around: LocalDate): List<TransactionEntity>
}
