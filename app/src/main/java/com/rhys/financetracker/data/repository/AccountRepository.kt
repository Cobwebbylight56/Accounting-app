package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.local.projection.AccountWithBalance
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
                accountDao.update(account.copy(updatedAt = Instant.now().toEpochMilli()))
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
