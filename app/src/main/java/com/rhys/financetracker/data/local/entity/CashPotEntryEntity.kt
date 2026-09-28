package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * One movement of notes and coins in or out of the household cash pot.
 *
 * ## Why the pot is not an account
 *
 * It used to be one — an account of type Cash, created under whoever was
 * being looked at. That made it belong to a person and to the bank-account
 * machinery: it could be picked when importing a statement, it appeared in
 * Available or Saved depending on how its type was read, and transfers into
 * it looked like savings moving. None of that is what a tin of money is.
 *
 * The pot is a place money is kept, not somewhere a bank reports on. It
 * belongs to the household, it is only ever changed by hand, and its total is
 * simply what went in less what came out. A withdrawal from any account
 * counts as spent where it happened; what is left over is put in here.
 */
@Entity(
    tableName = "cash_pot_entries",
    indices = [Index("date")],
)
data class CashPotEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val date: LocalDate,
    /** Never negative; the direction is [isIn]. */
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    /** True for money put in, false for money spent or taken out. */
    @ColumnInfo(name = "is_in") val isIn: Boolean,
    val note: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
) {
    /** This entry's effect on the pot's total. */
    val signedMinor: Long get() = if (isIn) amountMinor else -amountMinor
}
