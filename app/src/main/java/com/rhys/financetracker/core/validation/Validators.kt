package com.rhys.financetracker.core.validation

import com.rhys.financetracker.core.money.Money
import java.time.LocalDate

/**
 * Input validation used by every editor screen.  Validation lives here rather
 * than in the ViewModels so that the same rules apply to typed input, imported
 * spreadsheet rows and restored backups.
 */
object Validators {

    const val MAX_NAME_LENGTH = 80
    const val MAX_NOTES_LENGTH = 1_000

    /** The furthest into the past/future a record may be dated (sanity guard). */
    val EARLIEST_DATE: LocalDate = LocalDate.of(1970, 1, 1)
    val LATEST_DATE: LocalDate = LocalDate.of(2200, 12, 31)

    fun validateName(value: String, field: String = "Name"): ValidationResult = when {
        value.isBlank() -> ValidationResult.invalid("$field cannot be empty")
        value.length > MAX_NAME_LENGTH ->
            ValidationResult.invalid("$field must be $MAX_NAME_LENGTH characters or fewer")
        else -> ValidationResult.Valid
    }

    fun validateNotes(value: String?): ValidationResult = when {
        value == null -> ValidationResult.Valid
        value.length > MAX_NOTES_LENGTH ->
            ValidationResult.invalid("Notes must be $MAX_NOTES_LENGTH characters or fewer")
        else -> ValidationResult.Valid
    }

    /**
     * @param allowZero some records (an opening balance) may legitimately be zero,
     *   others (a bill) may not.
     * @param allowNegative overdrafts and refunds are negative; a savings target is not.
     */
    fun validateAmount(
        raw: String,
        allowZero: Boolean = false,
        allowNegative: Boolean = false,
    ): ValidationResult {
        val parsed = Money.parseOrNull(raw)
            ?: return ValidationResult.invalid("Enter a valid amount, for example 24.99")
        if (!allowZero && parsed == 0L) return ValidationResult.invalid("Amount cannot be zero")
        if (!allowNegative && parsed < 0L) return ValidationResult.invalid("Amount cannot be negative")
        return ValidationResult.Valid
    }

    fun validateDate(date: LocalDate?): ValidationResult = when {
        date == null -> ValidationResult.invalid("Choose a date")
        date.isBefore(EARLIEST_DATE) || date.isAfter(LATEST_DATE) ->
            ValidationResult.invalid("Choose a date between 1970 and 2200")
        else -> ValidationResult.Valid
    }

    fun validateDateOrder(start: LocalDate?, end: LocalDate?): ValidationResult = when {
        start == null || end == null -> ValidationResult.Valid
        end.isBefore(start) -> ValidationResult.invalid("The end date must be after the start date")
        else -> ValidationResult.Valid
    }

    fun validateInterval(interval: Int): ValidationResult = when {
        interval < 1 -> ValidationResult.invalid("The interval must be at least 1")
        interval > 999 -> ValidationResult.invalid("The interval must be 999 or less")
        else -> ValidationResult.Valid
    }

    fun validatePin(pin: String, minLength: Int = 4, maxLength: Int = 12): ValidationResult = when {
        pin.length < minLength -> ValidationResult.invalid("The PIN must be at least $minLength digits")
        pin.length > maxLength -> ValidationResult.invalid("The PIN must be $maxLength digits or fewer")
        !pin.all { it.isDigit() } -> ValidationResult.invalid("The PIN must contain digits only")
        else -> ValidationResult.Valid
    }

    /** Combines several checks, returning the first failure. */
    fun firstError(vararg results: ValidationResult): String? =
        results.filterIsInstance<ValidationResult.Invalid>().firstOrNull()?.message
}

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val message: String) : ValidationResult

    val errorOrNull: String? get() = (this as? Invalid)?.message
    val isValid: Boolean get() = this is Valid

    companion object {
        fun invalid(message: String): ValidationResult = Invalid(message)
    }
}
