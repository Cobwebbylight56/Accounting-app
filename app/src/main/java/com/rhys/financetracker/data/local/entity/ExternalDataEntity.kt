package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The most recent value of an externally sourced figure (an exchange rate, the
 * next bank holiday, a fuel price).
 *
 * [isManual] records whether the value was fetched or typed in.  Figures with
 * no free public API are always manual; the UI makes that distinction visible
 * rather than pretending the number is live.
 */
@Entity(tableName = "external_data")
data class ExternalDataEntity(
    /** Matches [com.rhys.financetracker.domain.model.ExternalDataKey.key]. */
    @PrimaryKey val key: String,
    /** Stored as text so a rate, a percentage and a date can share one table. */
    val value: String,
    val unit: String,
    /** Where the figure came from — an API host, or "Entered by hand". */
    val source: String,
    @ColumnInfo(name = "is_manual") val isManual: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = Instant.now().toEpochMilli(),
    /** Populated when the last refresh attempt failed, so the UI can explain why. */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)
