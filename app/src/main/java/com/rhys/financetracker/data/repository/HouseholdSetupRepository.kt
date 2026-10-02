package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.domain.loan.LoanMaths
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Setting somebody up, in the order a person thinks about their money: who
 * they are, what they earn, where it is kept, and what they owe.
 *
 * Shared by the first-run setup and by each person's own page, so an account
 * or loan added later is made exactly the way the setup would have made it.
 */
@Singleton
class HouseholdSetupRepository @Inject constructor(
    private val peopleRepository: PeopleRepository,
    private val accountRepository: AccountRepository,
    private val recurringRepository: RecurringRepository,
) {

    /** Makes the person, with their yearly pay if given. */
    suspend fun createPerson(
        name: String,
        colorHex: String,
        grossYearlyMinor: Long? = null,
        netYearlyMinor: Long? = null,
    ): AppResult<Long> {
        val existing = peopleRepository.activePeople()
            .firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        if (existing != null) return AppResult.Success(existing.id)
        return peopleRepository.save(
            PersonEntity(
                name = name.trim(),
                colorHex = colorHex,
                sortOrder = peopleRepository.activePeople().size,
                grossYearlyIncomeMinor = grossYearlyMinor,
                netYearlyIncomeMinor = netYearlyMinor,
            ),
        )
    }

    /** Sets what somebody earns a year, before and after tax. */
    suspend fun setPay(personId: Long, grossMinor: Long?, netMinor: Long?): AppResult<Long> {
        val person = peopleRepository.get(personId)
            ?: return AppResult.Failure("That person no longer exists")
        return peopleRepository.save(
            person.copy(grossYearlyIncomeMinor = grossMinor, netYearlyIncomeMinor = netMinor),
        )
    }

    /** An account of theirs, holding [balanceMinor] today. */
    suspend fun addAccount(
        personId: Long,
        type: AccountType,
        name: String,
        balanceMinor: Long,
    ): AppResult<Long> = accountRepository.save(
        AccountEntity(
            name = name.trim().ifEmpty { type.displayName },
            type = type,
            personId = personId,
            openingBalanceMinor = if (type.defaultHolding == Holding.OWED) {
                // What is owed is held as a negative balance, however it was typed.
                -kotlin.math.abs(balanceMinor)
            } else {
                balanceMinor
            },
            openingBalanceDate = DateUtils.today(),
            colorHex = DefaultData.PALETTE.random(),
        ),
    )

    /**
     * A loan to pay off: [owedMinor] still owed, out of [originalMinor] when
     * known, paid [monthlyPaymentMinor] a month from [payFromAccountId].
     *
     * The monthly payment is set up as a regular transfer into the loan, so
     * the amount owed goes down by itself each month — and when it reaches
     * nothing the loan is put away and the payment stops.
     */
    suspend fun addLoan(
        personId: Long,
        name: String,
        owedMinor: Long,
        originalMinor: Long?,
        monthlyPaymentMinor: Long?,
        paymentDay: Int,
        payFromAccountId: Long?,
        /** The yearly interest rate, kept on the account so payoff dates count interest. */
        interestRatePercent: Double? = null,
        /** Loan or mortgage as chosen; worked out from the name when not given. */
        accountType: AccountType? = null,
    ): AppResult<Long> = runCatchingApp("Could not add that loan") {
        require(owedMinor > 0L) { "Enter how much is still owed" }
        val loanName = name.trim().ifEmpty { "Loan" }
        val type = accountType ?: if (loanName.contains("mortgage", ignoreCase = true)) {
            AccountType.MORTGAGE
        } else {
            AccountType.LOAN
        }
        val loanId = when (
            val made = accountRepository.save(
                AccountEntity(
                    name = loanName,
                    type = type,
                    personId = personId,
                    openingBalanceMinor = -owedMinor,
                    openingBalanceDate = DateUtils.today(),
                    creditLimitMinor = originalMinor?.takeIf { it >= owedMinor } ?: owedMinor,
                    interestRatePercent = interestRatePercent,
                    colorHex = DefaultData.PALETTE.random(),
                ),
            )
        ) {
            is AppResult.Success -> made.data
            is AppResult.Failure -> error(made.message)
        }
        if (monthlyPaymentMinor != null && monthlyPaymentMinor > 0L && payFromAccountId != null) {
            val firstDue = DateUtils.safeDayOfMonth(
                DateUtils.currentYearMonth().let { month ->
                    if (DateUtils.today().dayOfMonth > paymentDay) month.plusMonths(1) else month
                },
                paymentDay,
            )
            recurringRepository.save(
                RecurringRuleEntity(
                    name = "$loanName payment",
                    amountMinor = monthlyPaymentMinor,
                    type = TransactionType.TRANSFER,
                    frequency = Frequency.MONTHLY,
                    startDate = firstDue,
                    nextDueDate = firstDue,
                    accountId = payFromAccountId,
                    transferAccountId = loanId,
                    personId = personId,
                ),
            )
        }
        loanId
    }

    /** Months left on a loan at a given monthly payment, interest included; null when it never ends. */
    fun monthsToClear(owedMinor: Long, monthlyPaymentMinor: Long?, ratePercent: Double? = null): Int? {
        if (monthlyPaymentMinor == null || monthlyPaymentMinor <= 0L) return null
        return LoanMaths.monthsToClear(owedMinor, ratePercent ?: 0.0, monthlyPaymentMinor)
    }

    /** When a loan paid at [monthlyPaymentMinor] a month will be clear, interest included. */
    fun clearBy(
        owedMinor: Long,
        monthlyPaymentMinor: Long?,
        today: LocalDate = DateUtils.today(),
        ratePercent: Double? = null,
    ): LocalDate? =
        monthsToClear(owedMinor, monthlyPaymentMinor, ratePercent)?.let { today.plusMonths(it.toLong()) }
}
