package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.CashPotDao
import com.rhys.financetracker.data.local.entity.CashPotEntryEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The household cash pot: notes and coins, belonging to nobody's account.
 *
 * Only ever changed by hand. Cash taken out of any account is spent where it
 * was taken out; this is for what is actually in the house — what was left
 * over, what something sold for, what somebody was given.
 */
@Singleton
class CashPotRepository @Inject constructor(
    private val cashPotDao: CashPotDao,
) {

    fun observeTotal(): Flow<Long> = cashPotDao.observeTotal()

    fun observeRecent(limit: Int): Flow<List<CashPotEntryEntity>> = cashPotDao.observeRecent(limit)

    /** Money into the pot ([isIn]) or out of it. */
    suspend fun record(amountMinor: Long, isIn: Boolean, note: String?): AppResult<Long> =
        runCatchingApp("Could not record that") {
            require(amountMinor > 0L) { "Enter an amount" }
            cashPotDao.insert(
                CashPotEntryEntity(
                    date = DateUtils.today(),
                    amountMinor = amountMinor,
                    isIn = isIn,
                    note = note?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
        }

    /**
     * Makes the pot's total [countedMinor] — what is actually there after
     * counting it — by recording the difference as a correction.
     *
     * A correction rather than a silent change, so the log still adds up to
     * the total and says when the count was taken. Returns the difference
     * recorded, which is zero when the pot was already right.
     */
    suspend fun setTotalTo(countedMinor: Long): AppResult<Long> =
        runCatchingApp("Could not correct the total") {
            require(countedMinor >= 0L) { "The pot can't hold less than nothing" }
            val difference = countedMinor - cashPotDao.getTotal()
            if (difference != 0L) {
                cashPotDao.insert(
                    CashPotEntryEntity(
                        date = DateUtils.today(),
                        amountMinor = kotlin.math.abs(difference),
                        isIn = difference > 0L,
                        note = COUNTED_NOTE,
                    ),
                )
            }
            difference
        }

    suspend fun delete(entry: CashPotEntryEntity): AppResult<Unit> =
        runCatchingApp("Could not remove that entry") { cashPotDao.delete(entry) }

    companion object {
        /** The note on an entry made by [setTotalTo]. */
        const val COUNTED_NOTE = "Counted — total corrected"
    }
}
