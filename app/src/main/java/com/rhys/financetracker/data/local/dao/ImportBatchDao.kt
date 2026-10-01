package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhys.financetracker.data.local.entity.ImportBatchEntity
import com.rhys.financetracker.data.local.entity.ImportBatchEntryEntity
import com.rhys.financetracker.data.local.projection.ImportBatchWithRemaining
import kotlinx.coroutines.flow.Flow

/** The statements imported into each account, and the payments each added. */
@Dao
interface ImportBatchDao {

    /** An account's imports, newest first, with how many of their payments are still there. */
    @Query(
        """
        SELECT b.*, (SELECT COUNT(*) FROM import_batch_entries e WHERE e.batch_id = b.id) AS remaining_rows
        FROM import_batches b
        WHERE b.account_id = :accountId
        ORDER BY b.imported_at DESC
        """,
    )
    fun observeForAccount(accountId: Long): Flow<List<ImportBatchWithRemaining>>

    @Query("SELECT * FROM import_batches WHERE id = :id")
    suspend fun getById(id: Long): ImportBatchEntity?

    @Query("SELECT transaction_id FROM import_batch_entries WHERE batch_id = :batchId")
    suspend fun transactionIds(batchId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(batch: ImportBatchEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<ImportBatchEntryEntity>)

    @Query("DELETE FROM import_batches WHERE id = :id")
    suspend fun delete(id: Long)
}
