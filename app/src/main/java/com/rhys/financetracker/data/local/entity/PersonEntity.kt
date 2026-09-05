package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * A member of the household: the user, a partner, a child, or a notional
 * "Joint" person that owns shared accounts.
 */
@Entity(
    tableName = "people",
    indices = [Index(value = ["name"], unique = true)],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** ARGB colour used to tint this person's rows and chart series. */
    @ColumnInfo(name = "color_hex") val colorHex: String,
    /** Set for the "Joint"/household person so it can be treated specially. */
    @ColumnInfo(name = "is_shared") val isShared: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    val notes: String? = null,
    /** Archived records stay in the database and in history but are hidden from pickers. */
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
)
