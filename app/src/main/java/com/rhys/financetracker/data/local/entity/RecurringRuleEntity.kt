package com.rhys.financetracker.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate

/**
 * A template that generates transactions on a schedule — a salary, a direct
 * debit, a standing order into savings.
 *
 * [nextDueDate] is the engine's cursor: it always points at the next occurrence
 * that has not yet been generated, so catching up after the app has not been
 * opened for a month is simply "keep generating while nextDueDate <= today".
 */
@Entity(
    tableName = "recurring_rules",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_account_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["category_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("account_id"),
        Index("transfer_account_id"),
        Index("category_id"),
        Index("person_id"),
        Index("next_due_date"),
        Index("savings_goal_id"),
    ],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    val type: TransactionType,
    val frequency: Frequency,
    /**
     * Multiplier for [Frequency.CUSTOM_DAYS] and [Frequency.CUSTOM_MONTHS];
     * ignored for the fixed frequencies.
     */
    val interval: Int = 1,
    @ColumnInfo(name = "start_date") val startDate: LocalDate,
    @ColumnInfo(name = "end_date") val endDate: LocalDate? = null,
    /** Stop after this many occurrences; null means "until the end date or forever". */
    @ColumnInfo(name = "max_occurrences") val maxOccurrences: Int? = null,
    @ColumnInfo(name = "occurrences_generated") val occurrencesGenerated: Int = 0,
    @ColumnInfo(name = "next_due_date") val nextDueDate: LocalDate,
    @ColumnInfo(name = "last_generated_date") val lastGeneratedDate: LocalDate? = null,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "transfer_account_id") val transferAccountId: Long? = null,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    @ColumnInfo(name = "savings_goal_id") val savingsGoalId: Long? = null,
    val mode: RecurrenceMode = RecurrenceMode.AUTO_POST,
    /** How many days before the due date to raise a reminder; null disables it. */
    @ColumnInfo(name = "reminder_days_before") val reminderDaysBefore: Int? = null,
    /** True for bills whose amount changes each time (energy, fuel). */
    @ColumnInfo(name = "is_variable_amount") val isVariableAmount: Boolean = false,
    val notes: String? = null,
    @ColumnInfo(name = "is_paused") val isPaused: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = Instant.now().toEpochMilli(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = Instant.now().toEpochMilli(),
)
