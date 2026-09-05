package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A saved spreadsheet-import mapping, so that re-importing next month's copy of
 * the same workbook does not mean re-doing the column matching.
 *
 * [mappingJson] holds a serialised
 * [com.rhys.financetracker.data.importer.ImportMapping].
 */
@Entity(
    tableName = "import_profiles",
    indices = [Index(value = ["name"], unique = true)],
)
data class ImportProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "mapping_json") val mappingJson: String,
    @ColumnInfo(name = "source_file_name") val sourceFileName: String? = null,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
)
