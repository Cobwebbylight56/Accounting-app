package com.rhys.financetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.rhys.financetracker.data.local.entity.DashboardWidgetEntity
import com.rhys.financetracker.data.local.entity.ExternalDataEntity
import com.rhys.financetracker.data.local.entity.ImportProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DashboardWidgetDao {

    @Query("SELECT * FROM dashboard_widgets ORDER BY position ASC")
    fun observeAll(): Flow<List<DashboardWidgetEntity>>

    @Query("SELECT * FROM dashboard_widgets ORDER BY position ASC")
    suspend fun getAll(): List<DashboardWidgetEntity>

    @Query("SELECT COUNT(*) FROM dashboard_widgets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(widgets: List<DashboardWidgetEntity>)

    @Query("UPDATE dashboard_widgets SET is_visible = :visible WHERE widget_key = :key")
    suspend fun setVisible(key: String, visible: Boolean)

    @Query("DELETE FROM dashboard_widgets")
    suspend fun deleteAll()
}

@Dao
interface ExternalDataDao {

    @Query("SELECT * FROM external_data ORDER BY key ASC")
    fun observeAll(): Flow<List<ExternalDataEntity>>

    @Query("SELECT * FROM external_data WHERE key = :key")
    suspend fun getByKey(key: String): ExternalDataEntity?

    @Query("SELECT * FROM external_data")
    suspend fun getAll(): List<ExternalDataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ExternalDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<ExternalDataEntity>)

    @Query("DELETE FROM external_data")
    suspend fun deleteAll()
}

@Dao
interface ImportProfileDao {

    @Query("SELECT * FROM import_profiles ORDER BY last_used_at DESC")
    fun observeAll(): Flow<List<ImportProfileEntity>>

    @Query("SELECT * FROM import_profiles WHERE id = :id")
    suspend fun getById(id: Long): ImportProfileEntity?

    @Query("SELECT * FROM import_profiles ORDER BY last_used_at DESC")
    suspend fun getAll(): List<ImportProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ImportProfileEntity): Long

    @Update
    suspend fun update(profile: ImportProfileEntity)

    @Delete
    suspend fun delete(profile: ImportProfileEntity)

    @Query("DELETE FROM import_profiles")
    suspend fun deleteAll()
}
