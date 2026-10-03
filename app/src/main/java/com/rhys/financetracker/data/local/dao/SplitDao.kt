package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhys.financetracker.data.local.entity.ReceiptItemChoiceEntity
import com.rhys.financetracker.data.local.entity.TransactionSplitEntity
import kotlinx.coroutines.flow.Flow

/** A split part with its category's name and colour, for showing. */
data class SplitPart(
    val id: Long,
    @androidx.room.ColumnInfo(name = "transaction_id") val transactionId: Long,
    @androidx.room.ColumnInfo(name = "category_id") val categoryId: Long?,
    @androidx.room.ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val label: String,
    val position: Int,
    @androidx.room.ColumnInfo(name = "category_name") val categoryName: String?,
    @androidx.room.ColumnInfo(name = "category_color") val categoryColor: String?,
)

@Dao
interface SplitDao {

    @Query(
        """
        SELECT s.id, s.transaction_id, s.category_id, s.amount_minor, s.label, s.position,
               c.name AS category_name, c.color_hex AS category_color
        FROM transaction_splits s
        LEFT JOIN categories c ON c.id = s.category_id
        WHERE s.transaction_id = :transactionId
        ORDER BY s.position ASC, s.id ASC
        """,
    )
    fun observeParts(transactionId: Long): Flow<List<SplitPart>>

    @Query("SELECT * FROM transaction_splits WHERE transaction_id = :transactionId ORDER BY position ASC, id ASC")
    suspend fun getFor(transactionId: Long): List<TransactionSplitEntity>

    @Query("SELECT * FROM transaction_splits ORDER BY transaction_id ASC, position ASC")
    suspend fun getAll(): List<TransactionSplitEntity>

    @Query("DELETE FROM transaction_splits")
    suspend fun deleteAll()

    @Query("DELETE FROM transaction_splits WHERE transaction_id = :transactionId")
    suspend fun deleteFor(transactionId: Long)

    @Insert
    suspend fun insertAll(parts: List<TransactionSplitEntity>)

    @Query("SELECT * FROM receipt_item_choices")
    suspend fun itemChoices(): List<ReceiptItemChoiceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberItems(choices: List<ReceiptItemChoiceEntity>)
}
