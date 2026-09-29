package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * A change to somebody's yearly pay: a pay rise, a yearly review, a new job.
 *
 * Both the pay before and after are kept, so the history reads as it
 * happened — "£27,455 → £29,000 from 1 April, yearly review" — and a rise can
 * be undone exactly. The person's own pay fields always hold what they earn
 * now; a change dated in the future waits here, unapplied, until its day.
 */
@Entity(
    tableName = "income_changes",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("person_id")],
)
data class IncomeChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "person_id") val personId: Long,
    /** The day the new pay starts. */
    @ColumnInfo(name = "effective_date") val effectiveDate: LocalDate,
    @ColumnInfo(name = "previous_gross_minor") val previousGrossMinor: Long?,
    @ColumnInfo(name = "previous_net_minor") val previousNetMinor: Long?,
    @ColumnInfo(name = "new_gross_minor") val newGrossMinor: Long?,
    @ColumnInfo(name = "new_net_minor") val newNetMinor: Long?,
    /** True when the take-home was worked out rather than given. */
    @ColumnInfo(name = "net_is_estimate") val netIsEstimate: Boolean = false,
    /** "Pay rise", "Yearly review", "Promotion", "New job"… */
    val reason: String? = null,
    /** False until the effective date has arrived and the person's pay moved. */
    @ColumnInfo(name = "is_applied") val isApplied: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
) {
    /** The rise before tax, or null when either side is unknown. */
    val grossChangeMinor: Long?
        get() = if (newGrossMinor != null && previousGrossMinor != null) {
            newGrossMinor - previousGrossMinor
        } else {
            null
        }

    /** The rise as a percentage of the old pay, to one decimal place. */
    val grossChangePercent: Double?
        get() {
            val before = previousGrossMinor ?: return null
            val change = grossChangeMinor ?: return null
            if (before <= 0L) return null
            return Math.round(change * 1000.0 / before) / 10.0
        }
}
