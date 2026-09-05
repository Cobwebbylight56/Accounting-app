package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.projection.SavingsGoalWithProgress
import kotlinx.coroutines.flow.Flow

/**
 * Savings goal queries.
 *
 * The current balance of a goal is either the balance of its linked account, or
 * the net of every transaction tagged with the goal, plus any manual
 * adjustment.  Both cases are resolved in SQL so the progress bars stay correct
 * without any bookkeeping in Kotlin.
 */
@Dao
interface SavingsGoalDao {

    @Query(
        """
        SELECT g.*, a.name AS account_name,
            g.manual_adjustment_minor
            + CASE WHEN g.account_id IS NOT NULL THEN (
                SELECT a2.opening_balance_minor
                    + IFNULL((
                        SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                        FROM transactions t
                        WHERE t.account_id = a2.id AND t.is_archived = 0
                    ), 0)
                    + IFNULL((
                        SELECT SUM(t2.amount_minor)
                        FROM transactions t2
                        WHERE t2.transfer_account_id = a2.id AND t2.type = 'TRANSFER'
                          AND t2.is_archived = 0
                    ), 0)
                FROM accounts a2 WHERE a2.id = g.account_id
            ) ELSE IFNULL((
                SELECT SUM(CASE WHEN t3.type = 'EXPENSE' THEN -t3.amount_minor ELSE t3.amount_minor END)
                FROM transactions t3
                WHERE t3.savings_goal_id = g.id AND t3.is_archived = 0
            ), 0) END AS current_amount_minor
        FROM savings_goals g
        LEFT JOIN accounts a ON a.id = g.account_id
        WHERE g.is_archived = 0
        ORDER BY g.sort_order ASC, g.name ASC
        """,
    )
    fun observeActiveWithProgress(): Flow<List<SavingsGoalWithProgress>>

    @Query(
        """
        SELECT g.*, a.name AS account_name,
            g.manual_adjustment_minor
            + CASE WHEN g.account_id IS NOT NULL THEN (
                SELECT a2.opening_balance_minor
                    + IFNULL((
                        SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_minor ELSE -t.amount_minor END)
                        FROM transactions t
                        WHERE t.account_id = a2.id AND t.is_archived = 0
                    ), 0)
                    + IFNULL((
                        SELECT SUM(t2.amount_minor)
                        FROM transactions t2
                        WHERE t2.transfer_account_id = a2.id AND t2.type = 'TRANSFER'
                          AND t2.is_archived = 0
                    ), 0)
                FROM accounts a2 WHERE a2.id = g.account_id
            ) ELSE IFNULL((
                SELECT SUM(CASE WHEN t3.type = 'EXPENSE' THEN -t3.amount_minor ELSE t3.amount_minor END)
                FROM transactions t3
                WHERE t3.savings_goal_id = g.id AND t3.is_archived = 0
            ), 0) END AS current_amount_minor
        FROM savings_goals g
        LEFT JOIN accounts a ON a.id = g.account_id
        WHERE g.id = :id
        """,
    )
    fun observeWithProgress(id: Long): Flow<SavingsGoalWithProgress?>

    @Query("SELECT * FROM savings_goals ORDER BY is_archived ASC, sort_order ASC, name ASC")
    fun observeAll(): Flow<List<SavingsGoalEntity>>

    @Query("SELECT * FROM savings_goals WHERE id = :id")
    suspend fun getById(id: Long): SavingsGoalEntity?

    @Query("SELECT * FROM savings_goals ORDER BY sort_order ASC, name ASC")
    suspend fun getAll(): List<SavingsGoalEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(goal: SavingsGoalEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(goals: List<SavingsGoalEntity>): List<Long>

    @Update
    suspend fun update(goal: SavingsGoalEntity)

    @Delete
    suspend fun delete(goal: SavingsGoalEntity)

    @Query("UPDATE savings_goals SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM savings_goals")
    suspend fun deleteAll()
}
