package com.rhys.financetracker.data.repository

import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.projection.PayeeEntry
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

/** Money sent to one person and money they sent back, side by side. */
data class PersonLedger(
    val name: String,
    val sent: List<PayeeEntry>,
    val received: List<PayeeEntry>,
) {
    val sentMinor: Long get() = sent.sumOf { it.amountMinor }
    val receivedMinor: Long get() = received.sumOf { it.amountMinor }

    /** Positive when they have sent more than they were sent. */
    val netMinor: Long get() = receivedMinor - sentMinor
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

    /**
     * Each person money went to or came from, with both sides together.
     *
     * Money in counts as from a person when it is filed under a people
     * category, reads like a transfer from someone, or comes from a name
     * money was also sent to. Names are matched however the bank writes them
     * ("J Smith", "John Smith", "Smith J"), so both sides land on one row.
     */
    fun observeMoneyWithPeople(from: LocalDate, to: LocalDate): Flow<List<PersonLedger>> =
        combine(
            observeSentToPeople(from, to),
            transactionDao.observeReceivedFromPeople(from, to, PEOPLE_IN),
        ) { sentGroups, receivedEntries ->
            ledgers(sentGroups.flatMap { it.entries }, receivedEntries)
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

    companion object {
        /**
         * Pairs up [sent] and [received] by person. Money in from a name that
         * money was never sent to is kept only when it reads like a person.
         */
        fun ledgers(sent: List<PayeeEntry>, received: List<PayeeEntry>): List<PersonLedger> {
            fun nameOf(entry: PayeeEntry) = PayeeNames.of(entry.description).ifBlank { entry.description.trim() }
            val sentByKey = sent.groupBy { PayeeNames.personKey(nameOf(it)) }
            val receivedByKey = received
                .filter { entry ->
                    PayeeNames.personKey(nameOf(entry)) in sentByKey ||
                        entry.categoryName != null ||
                        PayeeNames.looksLikeFromAPerson(entry.description)
                }
                .groupBy { PayeeNames.personKey(nameOf(it)) }
            return (sentByKey.keys + receivedByKey.keys).map { key ->
                val out = sentByKey[key].orEmpty()
                val back = receivedByKey[key].orEmpty()
                // The fullest spelling of the name the bank used.
                val name = (out + back).map { nameOf(it) }.maxByOrNull { it.length } ?: key
                PersonLedger(name, out, back)
            }.sortedByDescending { it.sentMinor + it.receivedMinor }
        }

        /** Categories that say only that a card was used. */
        private val VAGUE = listOf("Card spending")

        /** Categories that mean money sent to a person. */
        private val PEOPLE = listOf("People & services", "Transfers & payments")

        /** Categories that mean money in from a person. */
        private val PEOPLE_IN = PEOPLE + listOf("Gifts", "Money from people")

        private const val BATCH = 400
    }
}
