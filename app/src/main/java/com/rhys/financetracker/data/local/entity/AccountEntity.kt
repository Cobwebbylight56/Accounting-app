package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.domain.model.AccountType
import java.time.Instant
import java.time.LocalDate

/**
 * A pot of money belonging to a person (or to the household when
 * [personId] points at the shared person).
 *
 * The running balance is **derived**, never stored: it is [openingBalanceMinor]
 * plus every transaction against the account.  Storing a balance invites drift
 * between the balance and the transactions that are supposed to explain it.
 */
@Entity(
    tableName = "accounts",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("person_id"), Index("name")],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val type: AccountType,
    @ColumnInfo(name = "person_id") val personId: Long?,
    /** Balance before the first recorded transaction, in minor units. */
    @ColumnInfo(name = "opening_balance_minor") val openingBalanceMinor: Long = 0L,
    @ColumnInfo(name = "opening_balance_date") val openingBalanceDate: LocalDate = LocalDate.now(),
    @ColumnInfo(name = "currency_code") val currencyCode: String = Money.DEFAULT_CURRENCY_CODE,
    /** An agreed overdraft (positive number) used for the low-balance warning. */
    @ColumnInfo(name = "overdraft_limit_minor") val overdraftLimitMinor: Long = 0L,
    /** Balance below which the app warns, in minor units. */
    @ColumnInfo(name = "low_balance_threshold_minor") val lowBalanceThresholdMinor: Long? = null,
    /** Credit limit for cards; the original advance for loans/mortgages. */
    @ColumnInfo(name = "credit_limit_minor") val creditLimitMinor: Long? = null,
    @ColumnInfo(name = "interest_rate_percent") val interestRatePercent: Double? = null,
    @ColumnInfo(name = "color_hex") val colorHex: String,
    @ColumnInfo(name = "include_in_net_worth") val includeInNetWorth: Boolean = true,
    /** Visible to every person rather than just its owner. */
    @ColumnInfo(name = "is_shared") val isShared: Boolean = false,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0,
    val notes: String? = null,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
)
