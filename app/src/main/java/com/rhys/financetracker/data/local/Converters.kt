package com.rhys.financetracker.data.local

import androidx.room.TypeConverter
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate

/**
 * Room type converters.
 *
 * Dates are stored as ISO-8601 text (`2026-03-31`) rather than epoch numbers so
 * that the database is readable, sortable with plain SQL string comparison, and
 * immune to time-zone drift.  Enums are stored by name; see the note in
 * [com.rhys.financetracker.domain.model.AccountType] about never renaming them.
 */
class Converters {

    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? =
        value?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }

    @TypeConverter
    fun accountTypeToString(value: AccountType): String = value.name

    @TypeConverter
    fun stringToAccountType(value: String): AccountType =
        runCatching { AccountType.valueOf(value) }.getOrDefault(AccountType.OTHER)

    @TypeConverter
    fun transactionTypeToString(value: TransactionType): String = value.name

    @TypeConverter
    fun stringToTransactionType(value: String): TransactionType =
        runCatching { TransactionType.valueOf(value) }.getOrDefault(TransactionType.EXPENSE)

    @TypeConverter
    fun categoryKindToString(value: CategoryKind): String = value.name

    @TypeConverter
    fun stringToCategoryKind(value: String): CategoryKind =
        runCatching { CategoryKind.valueOf(value) }.getOrDefault(CategoryKind.EXPENSE)

    @TypeConverter
    fun frequencyToString(value: Frequency): String = value.name

    @TypeConverter
    fun stringToFrequency(value: String): Frequency =
        runCatching { Frequency.valueOf(value) }.getOrDefault(Frequency.MONTHLY)

    @TypeConverter
    fun recurrenceModeToString(value: RecurrenceMode): String = value.name

    @TypeConverter
    fun stringToRecurrenceMode(value: String): RecurrenceMode =
        runCatching { RecurrenceMode.valueOf(value) }.getOrDefault(RecurrenceMode.AUTO_POST)
}
