package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/**
 * One statement imported into one account: what it covered, what it added,
 * and what it did to the account's balance — everything needed to show the
 * history and to take the import back out again.
 */
@Entity(
    tableName = "import_batches",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("account_id")],
)
data class ImportBatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
    @ColumnInfo(name = "first_date") val firstDate: LocalDate?,
    @ColumnInfo(name = "last_date") val lastDate: LocalDate?,
    @ColumnInfo(name = "rows_added") val rowsAdded: Int,
    @ColumnInfo(name = "rows_updated") val rowsUpdated: Int,
    /** The account's given balance and its date before the import… */
    @ColumnInfo(name = "balance_before_minor") val balanceBeforeMinor: Long?,
    @ColumnInfo(name = "balance_date_before") val balanceDateBefore: LocalDate?,
    /** …and after, so an undo can put it back if nothing has moved it since. */
    @ColumnInfo(name = "balance_after_minor") val balanceAfterMinor: Long?,
    @ColumnInfo(name = "balance_date_after") val balanceDateAfter: LocalDate?,
)
