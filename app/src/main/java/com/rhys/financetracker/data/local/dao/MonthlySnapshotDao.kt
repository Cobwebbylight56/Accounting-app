package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhys.financetracker.data.local.entity.MonthlySnapshotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlySnapshotDao {

    @Query("SELECT * FROM monthly_snapshots ORDER BY year_month DESC, account_id ASC")
    fun observeAll(): Flow<List<MonthlySnapshotEntity>>

    @Query("SELECT * FROM monthly_snapshots WHERE year_month = :yearMonth ORDER BY account_id ASC")
    fun observeForMonth(yearMonth: String): Flow<List<MonthlySnapshotEntity>>

    @Query("SELECT * FROM monthly_snapshots WHERE year_month = :yearMonth")
    suspend fun getForMonth(yearMonth: String): List<MonthlySnapshotEntity>

    @Query("SELECT * FROM monthly_snapshots WHERE account_id = :accountId ORDER BY year_month ASC")
    suspend fun getForAccount(accountId: Long): List<MonthlySnapshotEntity>

    @Query("SELECT DISTINCT year_month FROM monthly_snapshots ORDER BY year_month DESC")
    fun observeArchivedMonths(): Flow<List<String>>

    @Query("SELECT COUNT(*) > 0 FROM monthly_snapshots WHERE year_month = :yearMonth")
    suspend fun hasMonth(yearMonth: String): Boolean

    /**
     * Every month already archived, in one go.
     *
     * The rollover walks from the first transaction to this month and used to
     * ask about each one separately. On a ledger holding a few years of
     * imported statements that is dozens of queries at launch, competing with
     * the queries the first screen is waiting on.
     */
    @Query("SELECT DISTINCT year_month FROM monthly_snapshots")
    suspend fun archivedMonths(): List<String>

    @Query("SELECT MAX(year_month) FROM monthly_snapshots")
    suspend fun latestArchivedMonth(): String?

    @Query("SELECT * FROM monthly_snapshots ORDER BY year_month ASC")
    suspend fun getAll(): List<MonthlySnapshotEntity>

    /**
     * Snapshots are written once.  REPLACE is used only so that a manual
     * "rebuild archive" can correct a month that was archived from bad data.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(snapshots: List<MonthlySnapshotEntity>)

    @Query("DELETE FROM monthly_snapshots WHERE year_month = :yearMonth")
    suspend fun deleteMonth(yearMonth: String)

    @Query("DELETE FROM monthly_snapshots")
    suspend fun deleteAll()
}
