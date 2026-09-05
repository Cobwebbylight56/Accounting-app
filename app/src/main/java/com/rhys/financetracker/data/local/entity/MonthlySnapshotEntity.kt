package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * An immutable record of how a month finished, written once by the monthly
 * rollover and never changed afterwards.
 *
 * Snapshots exist so that history stays truthful: if a transaction from March
 * is corrected in June, the June report is right *and* the archived March
 * figures still show what was believed at the time.  A snapshot is stored per
 * account; household and per-person totals are aggregated from these rows.
 */
@Entity(
    tableName = "monthly_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["year_month", "account_id"], unique = true),
        Index("account_id"),
    ],
)
data class MonthlySnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** ISO year-month key, e.g. `2026-03`. */
    @ColumnInfo(name = "year_month") val yearMonth: String,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "opening_balance_minor") val openingBalanceMinor: Long,
    @ColumnInfo(name = "closing_balance_minor") val closingBalanceMinor: Long,
    @ColumnInfo(name = "total_income_minor") val totalIncomeMinor: Long,
    @ColumnInfo(name = "total_expense_minor") val totalExpenseMinor: Long,
    @ColumnInfo(name = "total_transfers_in_minor") val totalTransfersInMinor: Long,
    @ColumnInfo(name = "total_transfers_out_minor") val totalTransfersOutMinor: Long,
    @ColumnInfo(name = "transaction_count") val transactionCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
)
