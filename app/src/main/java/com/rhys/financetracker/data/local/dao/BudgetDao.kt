package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.BudgetChangeEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budget_lines ORDER BY sort_order ASC, id ASC")
    fun observeLines(): Flow<List<BudgetLineEntity>>

    @Query("SELECT * FROM budget_lines ORDER BY sort_order ASC, id ASC")
    suspend fun getLines(): List<BudgetLineEntity>

    @Query("SELECT * FROM budget_lines WHERE id = :id")
    suspend fun getLine(id: Long): BudgetLineEntity?

    @Insert
    suspend fun insertLine(line: BudgetLineEntity): Long

    @Insert
    suspend fun insertLines(lines: List<BudgetLineEntity>): List<Long>

    @Update
    suspend fun updateLine(line: BudgetLineEntity)

    @Query("DELETE FROM budget_lines WHERE id = :id")
    suspend fun deleteLine(id: Long)

    @Query("DELETE FROM budget_lines")
    suspend fun deleteAllLines()

    /** Swaps the whole budget for [lines] in one go. */
    @Transaction
    suspend fun replaceLines(lines: List<BudgetLineEntity>) {
        deleteAllLines()
        insertLines(lines)
    }

    /** Every line's changes for one month or another, to know any month's budget. */
    @Query("SELECT * FROM budget_changes")
    fun observeChanges(): Flow<List<BudgetChangeEntity>>

    @Query("SELECT * FROM budget_changes")
    suspend fun getChanges(): List<BudgetChangeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setChange(change: BudgetChangeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChanges(changes: List<BudgetChangeEntity>)

    @Query("DELETE FROM budget_changes WHERE line_id = :lineId AND month = :month")
    suspend fun clearChange(lineId: Long, month: String)

    /**
     * Money paid into [accountId] between two days: moved in from another
     * of your accounts, or arriving on its own statement — a saver's
     * standing order, a loan's monthly payment.
     */
    @Query(
        """
        SELECT IFNULL(SUM(amount_minor), 0) FROM transactions
        WHERE is_archived = 0 AND date BETWEEN :start AND :end
          AND ((type = 'TRANSFER' AND transfer_account_id = :accountId)
               OR (type = 'INCOME' AND account_id = :accountId))
        """,
    )
    suspend fun paidInto(accountId: Long, start: LocalDate, end: LocalDate): Long
}
