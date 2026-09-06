package com.rhys.financetracker.data.local.projection

import androidx.room.ColumnInfo
import androidx.room.Embedded
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate

/**
 * Read models returned by DAO queries.
 *
 * These are deliberately flat: joining and aggregating in SQL keeps lists fast
 * even with tens of thousands of transactions, whereas loading entities and
 * combining them in Kotlin would not.
 */

/** A transaction together with the names it needs for display. */
data class TransactionWithDetails(
    @Embedded val transaction: TransactionEntity,
    @ColumnInfo(name = "account_name") val accountName: String?,
    @ColumnInfo(name = "transfer_account_name") val transferAccountName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color") val categoryColor: String?,
    @ColumnInfo(name = "person_name") val personName: String?,
    @ColumnInfo(name = "person_color") val personColor: String?,
)

/** An account with its computed running balance. */
data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    @ColumnInfo(name = "balance_minor") val balanceMinor: Long,
    @ColumnInfo(name = "person_name") val personName: String?,
) {
    /** Liabilities contribute negatively to net worth. */
    val netWorthContributionMinor: Long
        get() = if (!account.includeInNetWorth) 0L else balanceMinor

    val isSavings: Boolean get() = account.type.isSavings
    val isLiability: Boolean get() = account.type.isLiability
    val availableMinor: Long get() = balanceMinor + account.overdraftLimitMinor
}

/** Total for one category over a period, used by pie charts and reports. */
data class CategoryTotal(
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color") val categoryColor: String?,
    @ColumnInfo(name = "total_minor") val totalMinor: Long,
    @ColumnInfo(name = "transaction_count") val transactionCount: Int,
)

/** Income/expense totals for one calendar month. */
data class MonthTotals(
    @ColumnInfo(name = "year_month") val yearMonth: String,
    @ColumnInfo(name = "income_minor") val incomeMinor: Long,
    @ColumnInfo(name = "expense_minor") val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

/** Totals for one person, used by the household comparison view. */
data class PersonTotals(
    @ColumnInfo(name = "person_id") val personId: Long?,
    @ColumnInfo(name = "person_name") val personName: String?,
    @ColumnInfo(name = "person_color") val personColor: String?,
    @ColumnInfo(name = "income_minor") val incomeMinor: Long,
    @ColumnInfo(name = "expense_minor") val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

/** A recurring rule plus display names, and whether it is currently overdue. */
data class RecurringRuleWithDetails(
    @Embedded val rule: RecurringRuleEntity,
    @ColumnInfo(name = "account_name") val accountName: String?,
    @ColumnInfo(name = "category_name") val categoryName: String?,
    @ColumnInfo(name = "category_color") val categoryColor: String?,
    @ColumnInfo(name = "person_name") val personName: String?,
) {
    fun isOverdue(today: LocalDate): Boolean = !rule.isPaused && rule.nextDueDate.isBefore(today)
    fun isDueWithin(days: Long, today: LocalDate): Boolean =
        !rule.isPaused && !rule.nextDueDate.isBefore(today) &&
            rule.nextDueDate <= today.plusDays(days)
}

/** A savings goal with its current balance resolved from account or tagged transactions. */
data class SavingsGoalWithProgress(
    @Embedded val goal: SavingsGoalEntity,
    @ColumnInfo(name = "current_amount_minor") val currentAmountMinor: Long,
    @ColumnInfo(name = "account_name") val accountName: String?,
) {
    val remainingMinor: Long get() = (goal.targetAmountMinor - currentAmountMinor).coerceAtLeast(0L)

    /** 0f..1f, safe when the target is zero. */
    val progressFraction: Float
        get() = if (goal.targetAmountMinor <= 0L) {
            0f
        } else {
            (currentAmountMinor.toDouble() / goal.targetAmountMinor.toDouble())
                .coerceIn(0.0, 1.0).toFloat()
        }

    val percentComplete: Int get() = (progressFraction * 100f).toInt()
}

/** Aggregated income/expense pair used across the dashboard and reports. */
data class IncomeExpenseTotals(
    @ColumnInfo(name = "income_minor") val incomeMinor: Long,
    @ColumnInfo(name = "expense_minor") val expenseMinor: Long,
) {
    val netMinor: Long get() = incomeMinor - expenseMinor

    companion object {
        val EMPTY = IncomeExpenseTotals(0L, 0L)
    }
}

/**
 * How many stored transactions carry one import fingerprint.
 *
 * A count rather than a flag, because the same purchase can honestly happen
 * twice in a day and both should be kept.
 */
data class FingerprintCount(
    @ColumnInfo(name = "import_hash") val hash: String,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/** A payee and the category it was filed under, used to learn from past choices. */
data class DescriptionCategory(
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "category_name") val categoryName: String,
)

/** Money in and out of one account over a period. */
data class AccountActivity(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "income_minor") val incomeMinor: Long,
    @ColumnInfo(name = "expense_minor") val expenseMinor: Long,
)

/**
 * A stored transaction a statement row might turn out to be describing.
 *
 * Only what is needed to recognise the pair and to bring the stored one up to
 * the statement, so a whole month of entities is never loaded to compare a
 * date and an amount.
 */
data class ExistingEntry(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "date") val date: LocalDate,
    @ColumnInfo(name = "amount_minor") val amountMinor: Long,
    @ColumnInfo(name = "type") val type: TransactionType,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "notes") val notes: String?,
    @ColumnInfo(name = "source") val source: RecordSource,
)

/**
 * How often one payee appears on one account.
 *
 * Used to judge whether a statement is being filed against the right account:
 * a household's payees are strikingly account-specific, so a file whose payees
 * are all strangers here and all familiar somewhere else is almost certainly
 * pointed at the wrong one.
 */
data class AccountPayee(
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "description") val description: String,
    @ColumnInfo(name = "occurrences") val occurrences: Int,
)

/**
 * An account as a picker shows it: enough to name it, colour it and choose it.
 *
 * Carries the owner because account names are unique per person rather than
 * across the app, so "Main account" can legitimately appear more than once.
 */
data class AccountOption(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "color_hex") val colorHex: String?,
    @ColumnInfo(name = "person_name") val personName: String?,
)

/**
 * The label for [option] within this list: the plain name, or the name with its
 * owner when another account shares it.
 *
 * Qualifying only when it is needed keeps the common case short — most
 * households have one "Car insurance" and do not need to be told whose.
 */
fun List<AccountOption>.labelFor(option: AccountOption): String {
    val shared = count { it.name.equals(option.name, ignoreCase = true) } > 1
    return if (shared && option.personName != null) {
        "${option.personName} · ${option.name}"
    } else {
        option.name
    }
}
