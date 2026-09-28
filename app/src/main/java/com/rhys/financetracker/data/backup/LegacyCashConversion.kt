package com.rhys.financetracker.data.backup

import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
import com.rhys.financetracker.data.local.entity.MonthlySnapshotEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.SavingsGoalEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.TransactionType

/**
 * Turns the cash accounts in a backup from before database version 5 into
 * cash pot entries, exactly as `Migrations.MIGRATION_4_5` does to a database
 * upgraded in place.
 *
 * Kept as plain Kotlin over the restored rows so the two paths can be checked
 * against each other, and so an old backup restores to the same figures that
 * upgrading would have produced: every bank balance unchanged, the pot
 * holding what the cash accounts held.
 */
object LegacyCashConversion {

    data class Result(
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val rules: List<RecurringRuleEntity>,
        val goals: List<SavingsGoalEntity>,
        val snapshots: List<MonthlySnapshotEntity>,
        val potEntries: List<CashPotEntryEntity>,
    )

    fun convert(
        cashAccountIds: Set<Long>,
        accounts: List<AccountEntity>,
        transactions: List<TransactionEntity>,
        rules: List<RecurringRuleEntity>,
        goals: List<SavingsGoalEntity>,
        snapshots: List<MonthlySnapshotEntity>,
        cashCategoryId: Long?,
    ): Result {
        if (cashAccountIds.isEmpty()) {
            return Result(accounts, transactions, rules, goals, snapshots, emptyList())
        }
        val live = accounts
            .filter { it.id in cashAccountIds && !it.isArchived }
            .map { it.id }
            .toSet()
        val pot = mutableListOf<CashPotEntryEntity>()

        accounts.filter { it.id in live && it.openingBalanceMinor != 0L }.forEach { account ->
            pot += CashPotEntryEntity(
                date = account.openingBalanceDate,
                amountMinor = kotlin.math.abs(account.openingBalanceMinor),
                isIn = account.openingBalanceMinor > 0L,
                note = "Already in the pot",
                createdAt = account.createdAt,
            )
        }

        val kept = mutableListOf<TransactionEntity>()
        transactions.forEach { entry ->
            val fromCash = entry.accountId in cashAccountIds
            val toCash = entry.type == TransactionType.TRANSFER &&
                entry.transferAccountId in cashAccountIds
            when {
                // Tin to tin: never left the pot.
                fromCash && toCash -> Unit
                fromCash -> {
                    if (entry.accountId in live && !entry.isArchived) {
                        pot += potEntry(entry, isIn = entry.type == TransactionType.INCOME)
                    }
                    // Cash paid into a bank account stays paid in there.
                    if (entry.type == TransactionType.TRANSFER && entry.transferAccountId != null) {
                        kept += entry.copy(
                            type = TransactionType.INCOME,
                            accountId = entry.transferAccountId,
                            transferAccountId = null,
                            categoryId = cashCategoryId,
                        )
                    }
                }
                toCash -> {
                    if (entry.transferAccountId in live && !entry.isArchived) {
                        pot += potEntry(entry, isIn = true)
                    }
                    // Money into the tin is cash taken out of the bank.
                    kept += entry.copy(
                        type = TransactionType.EXPENSE,
                        transferAccountId = null,
                        categoryId = cashCategoryId,
                    )
                }
                else -> kept += entry
            }
        }

        return Result(
            accounts = accounts.filterNot { it.id in cashAccountIds },
            transactions = kept,
            rules = rules
                .filterNot { it.accountId in cashAccountIds }
                .map { rule ->
                    if (rule.transferAccountId in cashAccountIds) {
                        rule.copy(
                            type = TransactionType.EXPENSE,
                            transferAccountId = null,
                            categoryId = cashCategoryId,
                        )
                    } else {
                        rule
                    }
                },
            goals = goals.map { goal ->
                if (goal.accountId in cashAccountIds) goal.copy(accountId = null) else goal
            },
            snapshots = snapshots.filterNot { it.accountId in cashAccountIds },
            potEntries = pot,
        )
    }

    private fun potEntry(entry: TransactionEntity, isIn: Boolean) = CashPotEntryEntity(
        date = entry.date,
        amountMinor = entry.amountMinor,
        isIn = isIn,
        note = entry.description,
        createdAt = entry.createdAt,
    )
}
