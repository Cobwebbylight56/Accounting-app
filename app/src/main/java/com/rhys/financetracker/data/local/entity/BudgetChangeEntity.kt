package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/** A line's budget changed for one month only: the long trip, Christmas. */
@Entity(
    tableName = "budget_changes",
    primaryKeys = ["line_id", "month"],
    foreignKeys = [
        ForeignKey(
            entity = BudgetLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["line_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BudgetChangeEntity(
    @ColumnInfo(name = "line_id") val lineId: Long,
    /** "2026-11". */
    val month: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
)
