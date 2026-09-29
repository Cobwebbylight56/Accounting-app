package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.IncomeChangeEntity
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Pay rises and other changes to yearly pay; see [IncomeChangeEntity]. */
@Dao
interface IncomeChangeDao {

    /** One person's pay history, newest first. */
    @Query(
        "SELECT * FROM income_changes WHERE person_id = :personId " +
            "ORDER BY effective_date DESC, id DESC",
    )
    fun observeForPerson(personId: Long): Flow<List<IncomeChangeEntity>>

    /** Changes whose day has come but which have not been applied yet, oldest first. */
    @Query(
        "SELECT * FROM income_changes WHERE is_applied = 0 AND effective_date <= :today " +
            "ORDER BY effective_date ASC, id ASC",
    )
    suspend fun getDue(today: LocalDate): List<IncomeChangeEntity>

    /** One person's changes that are still waiting for their day. */
    @Query("SELECT * FROM income_changes WHERE is_applied = 0 AND person_id = :personId")
    suspend fun getPending(personId: Long): List<IncomeChangeEntity>

    @Query("SELECT * FROM income_changes WHERE id = :id")
    suspend fun getById(id: Long): IncomeChangeEntity?

    @Query("SELECT * FROM income_changes ORDER BY id ASC")
    suspend fun getAll(): List<IncomeChangeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(change: IncomeChangeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(changes: List<IncomeChangeEntity>): List<Long>

    @Update
    suspend fun update(change: IncomeChangeEntity)

    @Delete
    suspend fun delete(change: IncomeChangeEntity)

    @Query("DELETE FROM income_changes")
    suspend fun deleteAll()
}
