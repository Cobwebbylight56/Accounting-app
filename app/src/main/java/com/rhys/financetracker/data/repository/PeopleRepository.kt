package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.result.runCatchingApp
import com.rhys.financetracker.core.validation.Validators
import com.rhys.financetracker.data.local.dao.PersonDao
import com.rhys.financetracker.data.local.entity.PersonEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * People in the household.
 *
 * Every repository in this package follows the same shape: `observe…` returns a
 * [Flow] for the UI, and the write methods return an [AppResult] carrying a
 * message that is safe to show the user.  Validation happens here so that the
 * same rules apply whether a record arrives from a form, an import or a restore.
 */
@Singleton
class PeopleRepository @Inject constructor(
    private val personDao: PersonDao,
) {

    fun observeActive(): Flow<List<PersonEntity>> = personDao.observeActive()

    fun observeAll(): Flow<List<PersonEntity>> = personDao.observeAll()

    fun observe(id: Long): Flow<PersonEntity?> = personDao.observeById(id)

    suspend fun get(id: Long): PersonEntity? = personDao.getById(id)

    suspend fun save(person: PersonEntity): AppResult<Long> =
        runCatchingApp("Could not save this person") {
            Validators.validateName(person.name, "Name").errorOrNull?.let { error(it) }
            val existing = personDao.getByName(person.name)
            if (existing != null && existing.id != person.id) {
                error("There is already someone called \"${person.name}\"")
            }
            if (person.id == 0L) {
                personDao.insert(person)
            } else {
                personDao.update(person.copy(updatedAt = Instant.now().toEpochMilli()))
                person.id
            }
        }

    /** Copies a person, so a second child can be set up from the first. */
    suspend fun duplicate(id: Long): AppResult<Long> =
        runCatchingApp("Could not duplicate this person") {
            val original = personDao.getById(id) ?: error("That person no longer exists")
            personDao.insert(
                original.copy(
                    id = 0L,
                    name = uniqueName(original.name),
                    createdAt = Instant.now().toEpochMilli(),
                    updatedAt = Instant.now().toEpochMilli(),
                ),
            )
        }

    suspend fun setArchived(id: Long, archived: Boolean): AppResult<Unit> =
        runCatchingApp("Could not archive this person") {
            personDao.setArchived(id, archived, Instant.now().toEpochMilli())
        }

    /**
     * Deletes permanently.  Their accounts are kept but become unassigned, so
     * no transaction history is ever lost by removing a person.
     */
    suspend fun delete(person: PersonEntity): AppResult<Unit> =
        runCatchingApp("Could not delete this person") {
            if (person.isShared) error("The shared household record cannot be deleted")
            personDao.delete(person)
        }

    private suspend fun uniqueName(base: String): String {
        var candidate = "$base (copy)"
        var counter = 2
        while (personDao.getByName(candidate) != null) {
            candidate = "$base (copy $counter)"
            counter++
        }
        return candidate
    }
}
