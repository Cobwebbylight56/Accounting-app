package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One line of the household budget: what it's called ("Fuel"), what a month
 * of it should come to, and what counts towards it — the spending in a
 * category, or the money paid into a loan or saver.
 */
@Entity(
    tableName = "budget_lines",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("category_id"), Index("account_id")],
)
data class BudgetLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    /** The spending that counts: this category and the ones in it. */
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    /** Or the money paid into this loan or saver. */
    @ColumnInfo(name = "account_id") val accountId: Long? = null,
    /** The usual month's amount, in pence. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** An aim rather than a limit — fuel, the food shop — where going over isn't a big problem. */
    @ColumnInfo(name = "is_aim", defaultValue = "0") val isAim: Boolean = false,
    /** For an aim: it follows the average of the last few months. */
    @ColumnInfo(name = "follow_average", defaultValue = "0") val followAverage: Boolean = false,
    /** Money to put aside or pay off, reached rather than kept under. */
    @ColumnInfo(name = "is_put_aside", defaultValue = "0") val isPutAside: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    /** Made from the household spreadsheet. */
    @ColumnInfo(name = "from_sheet", defaultValue = "0") val fromSheet: Boolean = false,
)
