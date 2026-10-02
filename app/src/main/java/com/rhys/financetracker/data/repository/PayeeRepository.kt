package com.rhys.financetracker.data.repository

import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.local.projection.PayeeEntry
import com.rhys.financetracker.domain.model.TransactionType
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
    /** Stays the same however the bank spells the name; see PayeeNames.personKey. */
    val key: String,
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
 * Money with people for a period: the people, and the payees set aside as
 * not people — PayPal, shops — which can be brought back.
 */
data class PeopleMoney(
    val people: List<PersonLedger>,
    val notPeople: List<PersonLedger>,
) {
    val sentMinor: Long get() = people.sumOf { it.sentMinor }
    val receivedMinor: Long get() = people.sumOf { it.receivedMinor }

    companion object {
        val EMPTY = PeopleMoney(emptyList(), emptyList())
    }
}

/**
 * Spending grouped by payee: what has been sent to people, and what is not
 * sorted yet — so a whole payee can be filed in one go.
 */
@Singleton
class PayeeRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val settingsRepository: SettingsRepository,
) {

    /** How many payments are still waiting to be sorted, all time. */
    fun observeUnsortedCount(): Flow<Int> = transactionDao.observeUnsortedCount(VAGUE)

    /** Payments not properly sorted, biggest payees first. */
    fun observeUnsorted(from: LocalDate, to: LocalDate): Flow<List<PayeeGroup>> =
        transactionDao.observeUnsorted(from, to, VAGUE).map { group(it) }

    /**
     * Each person money went to or came from, with both sides together, for
     * [personIds]' accounts (everybody's when null).
     *
     * Money in counts as from a person when it is filed under a people
     * category, reads like a transfer from someone, or comes from a name
     * money was also sent to. Names are matched however the bank writes them
     * ("J Smith", "John Smith", "Smith J"), so both sides land on one row.
     * Payees that are not people — PayPal, a shop — are set aside, unless
     * the user has said otherwise.
     */
    fun observeMoneyWithPeople(
        from: LocalDate,
        to: LocalDate,
        personIds: Set<Long>? = null,
    ): Flow<PeopleMoney> {
        val everyone = personIds == null
        val ids = personIds.orEmpty().toList()
        return combine(
            transactionDao.observeSentToPeople(from, to, PEOPLE, everyone, ids),
            transactionDao.observeReceivedFromPeople(from, to, PEOPLE_IN, everyone, ids),
            settingsRepository.settings,
        ) { sent, received, settings ->
            ledgers(sent, received, settings.payeesKeptAsPeople, settings.payeesNotPeople)
        }
    }

    /** Settles whether the payee with [key] is a person; null goes back to the app's guess. */
    suspend fun setIsPerson(key: String, isPerson: Boolean?) {
        settingsRepository.setPayeeIsPerson(key, isPerson)
    }

    /**
     * Files every payment in [ids] under [categoryId] as the user's own
     * choice: each carries the hidden mark, so the app never re-sorts them,
     * and the importer files that payee the same way from then on.
     */
    suspend fun file(ids: List<Long>, categoryId: Long) {
        ids.chunked(BATCH).forEach { batch ->
            transactionDao.setCategoryByUser(batch, categoryId, System.currentTimeMillis())
        }
    }

    /**
     * Every other entry of [type] to the same payee as [description] —
     * "TESCO PFS 3012" finds "TESCO PFS 4471" too. Empty when the description
     * names nobody ("CONTACTLESS PAYMENT"), or for moves between accounts.
     */
    suspend fun samePayee(description: String, type: TransactionType, exceptId: Long): List<PayeeEntry> {
        if (type == TransactionType.TRANSFER) return emptyList()
        val payee = PayeeNames.of(description)
        if (payee.isBlank()) return emptyList()
        return transactionDao.entriesOfType(type.name).filter { entry ->
            entry.id != exceptId && PayeeNames.of(entry.description).equals(payee, ignoreCase = true)
        }
    }

    /**
     * The user put one payment in a category: every other payment to that
     * payee goes there too, whatever it was in before, and so do new
     * statements. Returns how many others moved.
     */
    suspend fun fileEveryPaymentLike(
        description: String,
        type: TransactionType,
        categoryId: Long,
        exceptId: Long,
    ): Int {
        val payee = PayeeNames.of(description)
        if (payee.isBlank()) return 0
        val others = samePayee(description, type, exceptId)
        file(others.map { it.id }, categoryId)
        return others.size
    }

    private fun group(entries: List<PayeeEntry>): List<PayeeGroup> =
        entries.groupBy { PayeeNames.of(it.description).ifBlank { it.description.trim() } }
            .map { (name, list) -> PayeeGroup(name, list) }
            .sortedByDescending { it.totalMinor }

    companion object {
        /**
         * Pairs up [sent] and [received] by person, and sets aside the
         * payees that are not people. [kept] and [hidden] are the user's
         * own answers, by PayeeNames.personKey, and outrank the app's guess.
         */
        fun ledgers(
            sent: List<PayeeEntry>,
            received: List<PayeeEntry>,
            kept: Set<String> = emptySet(),
            hidden: Set<String> = emptySet(),
        ): PeopleMoney {
            fun nameOf(entry: PayeeEntry) = PayeeNames.of(entry.description).ifBlank { entry.description.trim() }
            fun keyOf(entry: PayeeEntry) = PayeeNames.personKey(nameOf(entry))

            val sentByKey = sent
                .filter { entry ->
                    entry.categoryName != null || PayeeNames.looksLikeAPerson(entry.description) ||
                        keyOf(entry) in kept
                }
                .groupBy { keyOf(it) }
            val receivedByKey = received
                .filter { entry ->
                    val key = keyOf(entry)
                    key in sentByKey || key in kept || entry.categoryName != null ||
                        PayeeNames.looksLikeFromAPerson(entry.description)
                }
                .groupBy { keyOf(it) }

            val all = (sentByKey.keys + receivedByKey.keys).map { key ->
                val out = sentByKey[key].orEmpty()
                val back = receivedByKey[key].orEmpty()
                // The fullest spelling of the name the bank used.
                val name = (out + back).map { nameOf(it) }.maxByOrNull { it.length } ?: key
                PersonLedger(key, name, out, back)
            }.sortedByDescending { it.sentMinor + it.receivedMinor }

            val (people, notPeople) = all.partition { ledger ->
                when (ledger.key) {
                    in hidden -> false
                    in kept -> true
                    else -> PayeeNames.isPersonName(ledger.name)
                }
            }
            return PeopleMoney(people, notPeople)
        }

        /** Categories that say only that a card was used. */
        private val VAGUE = listOf("Card spending")

        /** Categories that mean money sent to a person. */
        private val PEOPLE = listOf("People & services", "Transfers & payments", "Payment apps")

        /** Categories that mean money in from a person. */
        private val PEOPLE_IN = PEOPLE + listOf("Gifts", "Money from people")

        private const val BATCH = 400
    }
}
