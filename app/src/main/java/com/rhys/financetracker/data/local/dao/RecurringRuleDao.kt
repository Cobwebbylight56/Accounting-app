package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.projection.RecurringRuleWithDetails
import com.rhys.financetracker.domain.model.TransactionType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface RecurringRuleDao {

    companion object {
        const val DETAIL_COLUMNS = """
            r.*,
            a.name AS account_name,
            c.name AS category_name,
            c.color_hex AS category_color,
            p.name AS person_name
        """

        const val DETAIL_JOINS = """
            FROM recurring_rules r
            LEFT JOIN accounts a ON a.id = r.account_id
            LEFT JOIN categories c ON c.id = r.category_id
            LEFT JOIN people p ON p.id = COALESCE(r.person_id, a.person_id)
        """
    }

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE r.is_archived = 0 ORDER BY r.next_due_date ASC, r.name ASC",
    )
    fun observeActiveWithDetails(): Flow<List<RecurringRuleWithDetails>>

    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE r.is_archived = 0 AND r.type = :type ORDER BY r.next_due_date ASC",
    )
    fun observeByType(type: TransactionType): Flow<List<RecurringRuleWithDetails>>

    /** Bills that fall due between today and [until], for the dashboard. */
    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE r.is_archived = 0 AND r.is_paused = 0 AND r.type = 'EXPENSE' " +
            "AND r.next_due_date BETWEEN :from AND :until " +
            "ORDER BY r.next_due_date ASC",
    )
    fun observeUpcoming(from: LocalDate, until: LocalDate): Flow<List<RecurringRuleWithDetails>>

    /** Bills whose due date has passed without the payment being generated. */
    @Query(
        "SELECT $DETAIL_COLUMNS $DETAIL_JOINS " +
            "WHERE r.is_archived = 0 AND r.is_paused = 0 AND r.next_due_date < :today " +
            "ORDER BY r.next_due_date ASC",
    )
    fun observeOverdue(today: LocalDate): Flow<List<RecurringRuleWithDetails>>

    @Query("SELECT $DETAIL_COLUMNS $DETAIL_JOINS WHERE r.id = :id")
    fun observeWithDetails(id: Long): Flow<RecurringRuleWithDetails?>

    @Query("SELECT * FROM recurring_rules WHERE id = :id")
    suspend fun getById(id: Long): RecurringRuleEntity?

    /** Rules the generator must catch up on. */
    @Query(
        "SELECT * FROM recurring_rules " +
            "WHERE is_archived = 0 AND is_paused = 0 AND next_due_date <= :through " +
            "ORDER BY next_due_date ASC",
    )
    suspend fun getDueThrough(through: LocalDate): List<RecurringRuleEntity>

    @Query(
        "SELECT * FROM recurring_rules " +
            "WHERE is_archived = 0 AND is_paused = 0 AND reminder_days_before IS NOT NULL " +
            "AND next_due_date BETWEEN :from AND :until ORDER BY next_due_date ASC",
    )
    suspend fun getNeedingReminder(from: LocalDate, until: LocalDate): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules ORDER BY name ASC")
    suspend fun getAll(): List<RecurringRuleEntity>

    @Query("SELECT * FROM recurring_rules WHERE is_archived = 0 AND is_paused = 0")
    suspend fun getAllActive(): List<RecurringRuleEntity>

    @Query("SELECT COUNT(*) FROM recurring_rules")
    suspend fun count(): Int

    /**
     * Monthly-equivalent totals for budgeting: each rule's amount scaled by how
     * often it occurs in a year.  Custom frequencies are excluded here and
     * added in Kotlin, where the interval is known.
     */
    @Query(
        "SELECT * FROM recurring_rules WHERE is_archived = 0 AND is_paused = 0 AND type = :type",
    )
    suspend fun getActiveByType(type: TransactionType): List<RecurringRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(rule: RecurringRuleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<RecurringRuleEntity>): List<Long>

    @Update
    suspend fun update(rule: RecurringRuleEntity)

    @Delete
    suspend fun delete(rule: RecurringRuleEntity)

    @Query(
        "UPDATE recurring_rules SET next_due_date = :nextDueDate, " +
            "last_generated_date = :lastGenerated, occurrences_generated = :occurrences, " +
            "updated_at = :updatedAt WHERE id = :id",
    )
    suspend fun updateSchedule(
        id: Long,
        nextDueDate: LocalDate,
        lastGenerated: LocalDate?,
        occurrences: Int,
        updatedAt: Long,
    )

    @Query("UPDATE recurring_rules SET is_paused = :paused, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPaused(id: Long, paused: Boolean, updatedAt: Long)

    @Query("UPDATE recurring_rules SET is_archived = :archived, updated_at = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("DELETE FROM recurring_rules")
    suspend fun deleteAll()
}
