package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.dao.RecurringRuleDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao,
    private val personDao: PersonDao,
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao,
    private val recurringRuleDao: RecurringRuleDao,
) {

    fun observeWithBalances(): Flow<List<AccountWithBalance>> =
        accountDao.observeActiveWithBalances()

    fun observeAllWithBalances(): Flow<List<AccountWithBalance>> =
        accountDao.observeAllWithBalances()

    fun observeActive(): Flow<List<AccountEntity>> = accountDao.observeActive()

    /** Active accounts with their owner, for anywhere one has to be chosen. */
    fun observeActiveOptions(): Flow<List<AccountOption>> = accountDao.observeActiveOptions()

    fun observe(id: Long): Flow<AccountEntity?> = accountDao.observeById(id)

    fun observeForPerson(personId: Long): Flow<List<AccountEntity>> =
        accountDao.observeForPerson(personId)

    /** Accounts whose balance has fallen below the user's warning threshold. */
    fun observeLowBalance(): Flow<List<AccountWithBalance>> =
        accountDao.observeActiveWithBalances().map { accounts ->
            accounts.filter { item ->
                val threshold = item.account.lowBalanceThresholdMinor ?: return@filter false
                item.availableMinor < threshold
            }
        }

    suspend fun get(id: Long): AccountEntity? = accountDao.getById(id)

    suspend fun getAll(): List<AccountEntity> = accountDao.getAll()

    suspend fun balanceAsOf(accountId: Long, date: LocalDate): Long =
        accountDao.getBalanceAsOf(accountId, date) ?: 0L

    suspend fun save(account: AccountEntity): AppResult<Long> =
        runCatchingApp("Could not save this account") {
            Validators.validateName(account.name, "Account name").errorOrNull?.let { error(it) }
            Validators.validateNotes(account.notes).errorOrNull?.let { error(it) }
            // Scoped to the owner: two people can each have a "Main account",
            // and refusing that was stopping a second person being set up at all.
            val existing = accountDao.getByNameForPerson(account.name, account.personId)
            if (existing != null && existing.id != account.id) {
                val owner = account.personId?.let { personDao.getById(it)?.name }
                error(
                    if (owner == null) {
                        "There is already a shared account called \"${account.name}\""
                    } else {
                        "$owner already has an account called \"${account.name}\""
                    },
                )
            }
            if (account.id == 0L) {
                accountDao.insert(account)
            } else {
                val before = accountDao.getById(account.id)
                accountDao.update(account.copy(updatedAt = Instant.now().toEpochMilli()))
                if (account.holding == Holding.SET_ASIDE && before?.holding != Holding.SET_ASIDE) {
                    fileAsSavings(account.id)
                }
                account.id
            }
        }

    /**
     * Copies an account's settings — type, colour, limits — without copying its
     * transactions, which is what "duplicate" means for a container of history.
     */
    suspend fun duplicate(id: Long): AppResult<Long> =
        runCatchingApp("Could not duplicate this account") {
            val original = accountDao.getById(id) ?: error("That account no longer exists")
            accountDao.insert(
                original.copy(
                    id = 0L,
                    name = uniqueName(original.name, original.personId),
                    openingBalanceMinor = 0L,
                    openingBalanceDate = LocalDate.now(),
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    /**
     * Puts an account under a person's name.
     *
     * Separate from [save] because it is the one change worth making without
     * opening the account at all. An account nobody owns is invisible to every
     * per-person view in the app — the person's tab on Home says they have
     * nothing and offers to add an account, while their money sits in a group
     * called "Not assigned" on another screen — and nothing on either screen
     * connects the two.
     */
    suspend fun assignTo(accountId: Long, personId: Long?): AppResult<Unit> =
        runCatchingApp("Could not change who this account belongs to") {
            val account = accountDao.getById(accountId) ?: error("That account no longer exists")
            val clash = accountDao.getByNameForPerson(account.name, personId)
            if (clash != null && clash.id != accountId) {
                val owner = personId?.let { personDao.getById(it)?.name }
                error(
                    if (owner == null) {
                        "There is already a shared account called \"${account.name}\""
                    } else {
                        "$owner already has an account called \"${account.name}\""
                    },
                )
            }
            accountDao.update(
                account.copy(personId = personId, updatedAt = Instant.now().toEpochMilli()),
            )
        }

    /**
     * Says what an account holds on [asOf]: the balance from then on is this,
     * plus whatever happens after that day.
     *
     * What is already in an account is not income — it is money you had before
     * the app knew about it — so it is never recorded as a payment in.
     */
    suspend fun setBalanceTo(
        accountId: Long,
        balanceMinor: Long,
        asOf: LocalDate = LocalDate.now(),
    ): AppResult<Unit> = runCatchingApp("Could not correct this balance") {
        accountDao.getById(accountId) ?: error("That account no longer exists")
        accountDao.setBalanceAsOf(accountId, balanceMinor, asOf, Instant.now().toEpochMilli())
    }

    /**
     * Brings an account's balance up to date after a statement was imported.
     *
     * The statement's closing balance is the bank's own figure, so when the
     * statement reaches the account's balance date or beyond, that becomes
     * the balance, as of the statement's last day. An older statement leaves
     * the balance alone: everything in it is already inside the figure.
     *
     * An account that was never given a balance — made at £0 by an import —
     * has only its history to go on, so when a statement without balances
     * reaches back before its balance date, the date moves back to let those
     * entries count.
     *
     * Returns what was done, for the import screen to say.
     */
    suspend fun updateFromStatement(
        accountId: Long,
        closingBalanceMinor: Long?,
        lastDate: LocalDate?,
        firstDate: LocalDate?,
    ): String? {
        val account = accountDao.getById(accountId) ?: return null
        val now = Instant.now().toEpochMilli()
        if (closingBalanceMinor != null && lastDate != null &&
            !lastDate.isBefore(account.openingBalanceDate)
        ) {
            accountDao.setBalanceAsOf(accountId, closingBalanceMinor, lastDate, now)
            return "${account.name}'s balance is now the statement's: " +
                "${com.rhys.financetracker.core.money.Money.format(closingBalanceMinor)} " +
                "on ${com.rhys.financetracker.core.time.DateUtils.format(lastDate)}"
        }
        if (closingBalanceMinor == null && account.openingBalanceMinor == 0L && firstDate != null &&
            !firstDate.isAfter(account.openingBalanceDate)
        ) {
            accountDao.setBalanceAsOf(accountId, 0L, firstDate.minusDays(1), now)
            return null
        }
        if (lastDate != null && lastDate.isBefore(account.openingBalanceDate)) {
            return "This statement ends before ${account.name}'s balance date " +
                "(${com.rhys.financetracker.core.time.DateUtils.format(account.openingBalanceDate)}), " +
                "so its rows are kept as history and the balance is unchanged."
        }
        return null
    }

    /**
     * Says where an account's money counts — to spend, set aside, or owed.
     *
     * Offered from the list as one tap because the accounts that need it are
     * the ones already there, guessed wrong. Setting an account aside also
     * files what is already on it as savings (interest apart), so the money
     * that arrived in a saver stops being counted as income the moment the
     * saver is recognised as one.
     */
    suspend fun setHolding(accountId: Long, holding: Holding): AppResult<Unit> =
        runCatchingApp("Could not change where this account counts") {
            val account = accountDao.getById(accountId) ?: error("That account no longer exists")
            accountDao.update(
                account.copy(holding = holding, updatedAt = Instant.now().toEpochMilli()),
            )
            if (holding == Holding.SET_ASIDE) fileAsSavings(accountId)
        }

    /** See [TransactionDao.fileAsSavings]. */
    suspend fun fileAsSavings(accountId: Long) {
        val savings = categoryDao.getByNameAndKind(SAVINGS_CATEGORY, CategoryKind.SAVING)
            ?: return
        transactionDao.fileAsSavings(accountId, savings.id, Instant.now().toEpochMilli())
    }

    /**
     * Puts away every loan and mortgage that has been paid off, and stops the
     * payments into it. Returns the names of the ones it put away.
     *
     * A loan is something to get rid of: once it reaches nothing owed it has
     * no business sitting on the screen at £0.00. It is archived rather than
     * deleted, so its history — every payment that cleared it — is kept and
     * it can be brought back. Credit cards are left alone; being at £0 is
     * their normal state, not an ending.
     */
    suspend fun archivePaidOffLoans(accounts: List<AccountWithBalance>): List<String> {
        val paidOff = accounts.filter { isPaidOffLoan(it) }
        val now = Instant.now().toEpochMilli()
        paidOff.forEach { loan ->
            accountDao.setArchived(loan.account.id, true, now)
            recurringRuleDao.pauseAllFor(loan.account.id, now)
        }
        return paidOff.map { it.account.name }
    }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this account") {
            accountDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    /**
     * Deletes an account **and every transaction on it** (enforced by the
     * foreign key).  The UI must warn about this; archiving is almost always
     * what the user actually wants.
     */
    suspend fun delete(account: AccountEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this account") {
            accountDao.delete(account)
        }

    companion object {
        /** The seeded savings category; see `DefaultData`. */
        private const val SAVINGS_CATEGORY = "Savings"

        /**
         * A loan or mortgage that was owed and now is not. It must have
         * started in debt — a loan added at £0 by mistake is not "paid off".
         */
        fun isPaidOffLoan(item: AccountWithBalance): Boolean {
            val account = item.account
            // Pay-later plans end too: the last of the three payments and it is done.
            val isLoan = account.type == AccountType.LOAN || account.type == AccountType.MORTGAGE ||
                account.type == AccountType.PAY_LATER
            val wasOwed = account.openingBalanceMinor < 0L || (account.creditLimitMinor ?: 0L) > 0L
            return isLoan && wasOwed && !account.isArchived && item.balanceMinor >= 0L
        }
    }

    private suspend fun uniqueName(base: String, personId: Long?): String {
        var candidate = "$base (copy)"
        var counter = 2
        while (accountDao.getByNameForPerson(candidate, personId) != null) {
            candidate = "$base (copy $counter)"
            counter++
        }
        return candidate
    }
}
