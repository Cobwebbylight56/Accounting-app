package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
import kotlinx.coroutines.flow.Flow

/** The household cash pot; see [CashPotEntryEntity]. */
@Dao
interface CashPotDao {

    /** What is in the pot: everything put in less everything taken out. */
    @Query(
        "SELECT IFNULL(SUM(CASE WHEN is_in = 1 THEN amount_minor ELSE -amount_minor END), 0) " +
            "FROM cash_pot_entries",
    )
    fun observeTotal(): Flow<Long>

    @Query("SELECT COUNT(*) FROM cash_pot_entries")
    fun observeCount(): Flow<Int>

    /** The newest entries first, for the pot's log. */
    @Query("SELECT * FROM cash_pot_entries ORDER BY date DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CashPotEntryEntity>>

    @Query("SELECT IFNULL(SUM(CASE WHEN is_in = 1 THEN amount_minor ELSE -amount_minor END), 0) FROM cash_pot_entries")
    suspend fun getTotal(): Long

    @Query("SELECT * FROM cash_pot_entries ORDER BY date ASC, id ASC")
    suspend fun getAll(): List<CashPotEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: CashPotEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CashPotEntryEntity>): List<Long>

    /** Clears what an earlier import put in under this note, so a re-import replaces it. */
    @Query("DELETE FROM cash_pot_entries WHERE note = :note")
    suspend fun deleteByNote(note: String)

    @Delete
    suspend fun delete(entry: CashPotEntryEntity)

    @Query("DELETE FROM cash_pot_entries")
    suspend fun deleteAll()
}
