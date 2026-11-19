package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** One person's part of a budget line: their column of the household sheet. */
@Entity(
    tableName = "budget_shares",
    primaryKeys = ["line_id", "person_id"],
    foreignKeys = [
        ForeignKey(
            entity = BudgetLineEntity::class,
            parentColumns = ["id"],
            childColumns = ["line_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("person_id")],
)
data class BudgetShareEntity(
    @ColumnInfo(name = "line_id") val lineId: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
)
