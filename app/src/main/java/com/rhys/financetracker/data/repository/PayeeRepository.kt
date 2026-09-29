package com.rhys.financetracker.data.repository

import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.projection.PayeeEntry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Payments added up by who they went to. */
data class PayeeGroup(
    val name: String,
    val entries: List<PayeeEntry>,
) {
    val totalMinor: Long get() = entries.sumOf { it.amountMinor }
    val count: Int get() = entries.size
    val lastDate: LocalDate? get() = entries.maxOfOrNull { it.date }
    val ids: List<Long> get() = entries.map { it.id }
}

/**
 * Spending grouped by payee: what has been sent to people, and what is not
 * sorted yet — so a whole payee can be filed in one go.
 */
@Singleton
class PayeeRepository @Inject constructor(
    private val transactionDao: TransactionDao,
) {

    /** Payments not properly sorted, biggest payees first. */
    fun observeUnsorted(from: LocalDate, to: LocalDate): Flow<List<PayeeGroup>> =
        transactionDao.observeUnsorted(from, to, VAGUE).map { group(it) }

    /**
     * Money sent to people: payments filed as going to somebody, and unsorted
     * ones that read like a transfer to a person.
     */
    fun observeSentToPeople(from: LocalDate, to: LocalDate): Flow<List<PayeeGroup>> =
        transactionDao.observeSentToPeople(from, to, PEOPLE).map { entries ->
            group(
                entries.filter { entry ->
                    entry.categoryName != null || PayeeNames.looksLikeAPerson(entry.description)
                },
            )
        }

    /** Files every payment in [ids] under [categoryId]; the importer learns it too. */
    suspend fun file(ids: List<Long>, categoryId: Long) {
        ids.chunked(BATCH).forEach { batch ->
            transactionDao.setCategory(batch, categoryId, System.currentTimeMillis())
        }
    }

    private fun group(entries: List<PayeeEntry>): List<PayeeGroup> =
        entries.groupBy { PayeeNames.of(it.description).ifBlank { it.description.trim() } }
            .map { (name, list) -> PayeeGroup(name, list) }
            .sortedByDescending { it.totalMinor }

    private companion object {
        /** Categories that say only that a card was used. */
        val VAGUE = listOf("Card spending")

        /** Categories that mean money sent to a person. */
        val PEOPLE = listOf("People & services", "Transfers & payments")

        const val BATCH = 400
    }
}
