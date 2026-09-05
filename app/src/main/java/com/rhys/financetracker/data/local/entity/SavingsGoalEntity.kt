package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * A named savings target — a holiday, an emergency fund, Christmas.
 *
 * The balance can be tracked in two ways:
 *  * linked to an account ([accountId]), in which case the account balance is
 *    the goal balance; or
 *  * as a virtual pot, where the total of transactions tagged with the goal
 *    plus [manualAdjustmentMinor] is the balance.
 */
@Entity(
    tableName = "savings_goals",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("account_id"), Index("person_id")],
)
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "target_amount_minor") val targetAmountMinor: Long,
    /** Starting balance, or the whole balance for a goal tracked by hand. */
    @ColumnInfo(name = "manual_adjustment_minor") val manualAdjustmentMinor: Long = 0L,
    @ColumnInfo(name = "monthly_contribution_minor") val monthlyContributionMinor: Long = 0L,
    @ColumnInfo(name = "target_date") val targetDate: LocalDate? = null,
    @ColumnInfo(name = "start_date") val startDate: LocalDate = LocalDate.now(),
    @ColumnInfo(name = "account_id") val accountId: Long? = null,
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "icon_key") val iconKey: String? = null,
    val notes: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    @ColumnInfo(name = "is_achieved") val isAchieved: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
)
