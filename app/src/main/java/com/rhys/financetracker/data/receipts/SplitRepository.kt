package com.rhys.financetracker.data.receipts

import androidx.room.withTransaction
import com.rhys.financetracker.data.local.AppDatabase
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.ReceiptDao
import com.rhys.financetracker.data.local.dao.SplitDao
import com.rhys.financetracker.data.local.dao.SplitPart
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.ReceiptItemChoiceEntity
import com.rhys.financetracker.data.local.entity.TransactionSplitEntity
import com.rhys.financetracker.domain.model.CategoryKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Payments split across categories: the parts, kept adding up to the
 * payment, and filled in from what is on its receipt.
 */
@Singleton
class SplitRepository @Inject constructor(
    private val database: AppDatabase,
    private val splitDao: SplitDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val receiptDao: ReceiptDao,
) {

    /** One part as it is being edited. A null category is the payment's own. */
    data class Draft(val label: String, val amountMinor: Long, val categoryId: Long?)

    fun observeParts(transactionId: Long): Flow<List<SplitPart>> = splitDao.observeParts(transactionId)

    /** The parts as they stand, without the "rest of the shop" the app keeps itself. */
    suspend fun drafts(transactionId: Long): List<Draft> =
        splitDao.getFor(transactionId)
            .filterNot { it.label == REST && it.categoryId == null }
            .map { Draft(it.label, it.amountMinor, it.categoryId) }

    /**
     * Splits [transactionId] into [parts]. Whatever they leave of the
     * payment is kept as the rest of the shop, in the payment's own category,
     * so the parts always add up. Returns null, or why it could not be saved.
     * With [learn], the category of each item is remembered for next time.
     */
    suspend fun save(transactionId: Long, parts: List<Draft>, learn: Boolean): String? {
        val payment = transactionDao.getById(transactionId) ?: return "That payment has gone."
        val used = parts.filter { it.amountMinor > 0L }
        val rest = payment.amountMinor - used.sumOf { it.amountMinor }
        if (rest < 0L) return "The parts come to more than the payment."
        val rows = if (used.isEmpty()) {
            emptyList()
        } else {
            (used + listOfNotNull(Draft(REST, rest, null).takeIf { rest > 0L }))
                .mapIndexed { index, part ->
                    TransactionSplitEntity(
                        transactionId = transactionId,
                        categoryId = part.categoryId,
                        amountMinor = part.amountMinor,
                        label = part.label.trim().ifEmpty { "Part ${index + 1}" },
                        position = index,
                    )
                }
        }
        database.withTransaction {
            splitDao.deleteFor(transactionId)
            if (rows.isNotEmpty()) splitDao.insertAll(rows)
        }
        if (learn) {
            splitDao.rememberItems(
                used.filter { it.categoryId != null && ItemCategoriser.keyOf(it.label).length >= MIN_KEY }
                    .map { ReceiptItemChoiceEntity(ItemCategoriser.keyOf(it.label), it.categoryId!!) },
            )
        }
        return null
    }

    suspend fun clear(transactionId: Long) = splitDao.deleteFor(transactionId)

    /** Whether the payment has a receipt with writing on it to split by. */
    suspend fun receiptItems(transactionId: Long): List<ReceiptItems.Item> =
        receiptDao.latestTextFor(transactionId)?.let(ReceiptItems::itemsIn).orEmpty()

    /**
     * Parts for each item on the payment's receipt, each in the category it
     * looks like — or the one chosen for it before. Items that look like the
     * payment's own (food at a supermarket) are left to it.
     */
    suspend fun fromReceipt(transactionId: Long): List<Draft> {
        val items = receiptItems(transactionId)
        if (items.isEmpty()) return emptyList()
        val categories = categoryDao.getAll()
        val names = categories.associate { it.id to it.name }
        val learned = splitDao.itemChoices().mapNotNull { choice -> names[choice.categoryId]?.let { choice.itemKey to it } }.toMap()
        return items.map { item ->
            val category = ItemCategoriser.categoryFor(item.name, learned)?.let { name ->
                categories.firstOrNull { it.name.equals(name, ignoreCase = true) && it.kind == CategoryKind.EXPENSE }?.id
            }
            Draft(item.name, item.amountMinor, category)
        }
    }

    /**
     * Keeps a split payment's parts adding up after its amount changed: the
     * rest of the shop takes up the difference, and if the parts alone now
     * come to more than the payment, the split is dropped.
     */
    suspend fun reconcile(transactionId: Long) {
        val parts = splitDao.getFor(transactionId)
        if (parts.isEmpty()) return
        val payment = transactionDao.getById(transactionId) ?: return
        if (parts.sumOf { it.amountMinor } == payment.amountMinor) return
        val kept = parts.filterNot { it.label == REST && it.categoryId == null }
        if (kept.sumOf { it.amountMinor } > payment.amountMinor) {
            splitDao.deleteFor(transactionId)
        } else {
            save(transactionId, kept.map { Draft(it.label, it.amountMinor, it.categoryId) }, learn = false)
        }
    }

    companion object {
        /** What the part of a split payment nobody named is called. */
        const val REST = "Rest of the shop"
        private const val MIN_KEY = 3
    }
}
