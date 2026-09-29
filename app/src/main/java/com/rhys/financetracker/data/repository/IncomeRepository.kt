package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.dao.IncomeChangeDao
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.entity.IncomeChangeEntity
import com.rhys.financetracker.domain.income.PayRise
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Yearly pay and how it changes.
 *
 * The person record holds what somebody earns now; this keeps how it got
 * there. A rise dated today or earlier moves the person's pay at once. One
 * dated later waits and is applied on its day by [applyDue], which Home runs
 * each time it opens.
 */
@Singleton
class IncomeRepository @Inject constructor(
    private val incomeChangeDao: IncomeChangeDao,
    private val personDao: PersonDao,
) {

    fun observeHistory(personId: Long): Flow<List<IncomeChangeEntity>> =
        incomeChangeDao.observeForPerson(personId)

    /**
     * Records a change to [personId]'s pay: [newGrossMinor] before tax and,
     * when known, [newNetMinor] take-home. Without a take-home it is
     * estimated from the old one and marked as an estimate.
     */
    suspend fun addChange(
        personId: Long,
        effectiveDate: LocalDate,
        newGrossMinor: Long,
        newNetMinor: Long?,
        reason: String?,
        today: LocalDate = DateUtils.today(),
    ): AppResult<IncomeChangeEntity> = runCatchingApp("Could not record that pay change") {
        val person = personDao.getById(personId) ?: error("That person no longer exists")
        require(newGrossMinor > 0L) { "Enter the new pay" }
        // What it was just before: the latest change still waiting, if there
        // is one, otherwise what they earn now.
        val waiting = incomeChangeDao.getPending(personId)
            .filter { it.effectiveDate <= effectiveDate }
            .maxByOrNull { it.effectiveDate }
        val previousGross = waiting?.newGrossMinor ?: person.grossYearlyIncomeMinor
        val previousNet = waiting?.newNetMinor ?: person.netYearlyIncomeMinor
        val estimated = newNetMinor == null
        val net = newNetMinor ?: PayRise.estimateNet(previousGross, previousNet, newGrossMinor)
        val change = IncomeChangeEntity(
            personId = personId,
            effectiveDate = effectiveDate,
            previousGrossMinor = previousGross,
            previousNetMinor = previousNet,
            newGrossMinor = newGrossMinor,
            newNetMinor = net,
            netIsEstimate = estimated && net != null,
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
        )
        val id = incomeChangeDao.insert(change)
        applyDue(today)
        incomeChangeDao.getById(id) ?: change.copy(id = id)
    }

    /** Moves everybody's pay on for every change whose day has come. */
    suspend fun applyDue(today: LocalDate = DateUtils.today()): Int {
        val due = incomeChangeDao.getDue(today)
        due.forEach { change ->
            val person = personDao.getById(change.personId) ?: return@forEach
            personDao.update(
                person.copy(
                    grossYearlyIncomeMinor = change.newGrossMinor ?: person.grossYearlyIncomeMinor,
                    netYearlyIncomeMinor = change.newNetMinor ?: person.netYearlyIncomeMinor,
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
            incomeChangeDao.update(change.copy(isApplied = true))
        }
        return due.size
    }

    /**
     * Removes a change. If it is what the person earns now, their pay goes
     * back to what it was before it.
     */
    suspend fun delete(change: IncomeChangeEntity): AppResult<Unit> =
        runCatchingApp("Could not remove that pay change") {
            val person = personDao.getById(change.personId)
            if (change.isApplied && person != null &&
                person.grossYearlyIncomeMinor == change.newGrossMinor
            ) {
                personDao.update(
                    person.copy(
                        grossYearlyIncomeMinor = change.previousGrossMinor,
                        netYearlyIncomeMinor = change.previousNetMinor,
                        updatedAt = Instant.now().toEpochMilli(),
                    ),
                )
            }
            incomeChangeDao.delete(change)
        }
}
