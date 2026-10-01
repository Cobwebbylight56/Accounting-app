package com.rhys.financetracker.data.repository

import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.OwnAccountMatcher
import com.rhys.financetracker.data.importer.PayeeNames
import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.local.seed.DefaultData
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.TransactionType
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.flow.first

/**
 * What an automatic re-sort moved, for the note on Home: how many payments,
 * and the biggest moves as "Tesco Pfs → Fuel".
 */
data class ResortNote(val moved: Int, val examples: List<String>) {
    fun encode(): String = (listOf(moved.toString()) + examples).joinToString("\n")

    companion object {
        fun decode(text: String): ResortNote? {
            val lines = text.split('\n')
            val moved = lines.firstOrNull()?.toIntOrNull() ?: return null
            return if (moved > 0) ResortNote(moved, lines.drop(1).filter { it.isNotBlank() }) else null
        }
    }
}

/** What one run of [TidyUpRepository.sortEverything] changed. */
data class TidyUpResult(
    /** Pay rises whose date had come and were applied. */
    val payRisesApplied: Int = 0,
    /** Entries on savers filed as savings. */
    val filedAsSavings: Int = 0,
    /** Entries turned into moves between the person's own accounts. */
    val linkedToAccounts: Int = 0,
    /** Entries given a category. */
    val categorised: Int = 0,
    /** Entries the app had filed moved to a better category. */
    val refiled: Int = 0,
    /** Regular payments set up as bills. */
    val billsAdded: List<String> = emptyList(),
    /** How much was looked at, so "nothing to do" can be believed. */
    val entriesChecked: Int = 0,
    val accountsChecked: Int = 0,
    /** Paid-off loans put away. */
    val loansCleared: List<String> = emptyList(),
    /** Money out that still has no proper category. */
    val stillUnsorted: Int = 0,
) {
    val changedAnything: Boolean
        get() = payRisesApplied + filedAsSavings + linkedToAccounts + categorised + refiled > 0 ||
            loansCleared.isNotEmpty() || billsAdded.isNotEmpty()
}

/**
 * Goes over everything already in the app and files it by today's rules.
 *
 * The importer only sorts rows as they arrive. Anything imported before a
 * rule existed — a shop the categoriser has since learned, a saver added
 * after its payments, a loan payment that came in as spending — stays where
 * it landed until moved by hand. This runs the same rules over the lot:
 *
 * 1. Pay rises whose day has come are applied.
 * 2. Every movement on a saver is filed as savings.
 * 3. Payments that name another of the person's own accounts become moves to
 *    it, so the saver goes up and the loan comes down — unless the other
 *    account's own statement already has the same movement.
 * 4. Unfiled payments, and ones filed only as "Card spending", are given a
 *    category from what has been filed before, then the built-in shop list.
 * 5. Everything the app filed by itself is re-sorted: to where the user
 *    filed the same payee, or else by the built-in list. See [resort].
 * 6. Regular payments seen at least twice and still being paid are set up
 *    as bills.
 * 7. Paid-off loans are put away.
 *
 * Nothing the user chose is overridden: entries whose category they set
 * carry a hidden mark (TransactionEntity.categoryByUser) and are never moved.
 */
@Singleton
class TidyUpRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val incomeRepository: IncomeRepository,
    private val importer: SpreadsheetImporter,
    private val billFinder: BillFinderRepository,
    private val settingsRepository: com.rhys.financetracker.data.prefs.SettingsRepository,
) {

    suspend fun sortEverything(): TidyUpResult {
        val payRises = incomeRepository.applyDue()

        val accounts = accountDao.getAllActive()
        val filed = accounts.filter { it.holding == Holding.SET_ASIDE }
            .sumOf { accountRepository.fileAsSavings(it.id) }

        val linked = accounts.sumOf { account ->
            linkOwnAccounts(account.id, isSaver = account.holding == Holding.SET_ASIDE)
        }

        val categorised = categoriseUnsorted()
        val refiled = resort(remember = false)
        val bills = addStillPaidBills()

        val cleared = accountRepository.archivePaidOffLoans(
            accountDao.observeActiveWithBalances().first(),
        )

        return TidyUpResult(
            payRisesApplied = payRises,
            filedAsSavings = filed,
            linkedToAccounts = linked,
            categorised = categorised,
            refiled = refiled,
            billsAdded = bills,
            entriesChecked = transactionDao.countActive(),
            accountsChecked = accounts.size,
            loansCleared = cleared,
            stillUnsorted = transactionDao.countUnsorted(VAGUE),
        )
    }

    /** Turns [accountId]'s payments to and from its owner's other accounts into moves. */
    private suspend fun linkOwnAccounts(accountId: Long, isSaver: Boolean): Int {
        val targets = importer.ownAccountTargets(accountId)
        if (targets.isEmpty()) return 0
        val rows = transactionDao.looseOnAccount(accountId, LOOSE)
        if (rows.isEmpty()) return 0

        val learned = transactionDao.transferLinks(accountId, SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .mapNotNull { link ->
                val other = if (link.accountId == accountId) link.transferAccountId else link.accountId
                other?.let { TransactionFingerprint.normaliseDescription(link.description) to it }
            }
            .toMap()
        val savingIds = categoryDao.getAll().filter { it.kind == CategoryKind.SAVING }.map { it.id }.toSet()
        val margin = SpreadsheetImporter.TRANSFER_MARGIN_DAYS
        val held = transactionDao.transfersTouching(
            accountId,
            rows.first().date.minusDays(margin),
            rows.last().date.plusDays(margin),
        ).toMutableList()

        var changed = 0
        rows.forEach { row ->
            val out = row.type == TransactionType.EXPENSE
            // Already recorded as a move — by the loan's own payment or the
            // other statement — so this row is the same money, not new money.
            val existing = held.firstOrNull { transfer ->
                transfer.amountMinor == row.amountMinor &&
                    abs(transfer.date.toEpochDay() - row.date.toEpochDay()) <= margin &&
                    if (out) transfer.accountId == accountId else transfer.transferAccountId == accountId
            }
            if (existing != null) {
                held.remove(existing)
                return@forEach
            }

            val target = OwnAccountMatcher.match(
                description = row.description,
                amountMinor = row.amountMinor,
                date = row.date,
                isMoneyOut = out,
                // On a saver everything is savings; "the only saver" means
                // money arriving from spending, not one saver to another.
                isSavings = !isSaver && row.categoryId in savingIds,
                targets = targets,
                learned = learned,
            ) ?: return@forEach

            val mirrored = transactionDao.sameMovement(
                accountId = target.id,
                type = (if (out) TransactionType.INCOME else TransactionType.EXPENSE).name,
                amountMinor = row.amountMinor,
                from = row.date.minusDays(margin),
                to = row.date.plusDays(margin),
            )
            if (mirrored.isNotEmpty()) return@forEach

            transactionDao.update(asTransfer(row, target.id, Instant.now().toEpochMilli()))
            changed++
        }
        return changed
    }

    /** Files unsorted entries by what has been filed before, then the built-in list. */
    private suspend fun categoriseUnsorted(): Int {
        val rows = transactionDao.getUnsortedEntries(VAGUE)
        if (rows.isEmpty()) return 0
        val learned = learnedFromUser()
        val vagueIds = VAGUE.mapNotNull { categoryDao.getByName(it)?.id }.toSet()

        val byCategory = mutableMapOf<Long, MutableList<Long>>()
        rows.forEach { row ->
            val name = MerchantCategoriser.categoryFor(row.description, row.type, learned)
                ?: return@forEach
            val id = categoryIdFor(name, row.type) ?: return@forEach
            if (id == row.categoryId || id in vagueIds && row.categoryId != null) return@forEach
            byCategory.getOrPut(id) { mutableListOf() }.add(row.id)
        }
        val now = Instant.now().toEpochMilli()
        byCategory.forEach { (categoryId, ids) ->
            ids.chunked(BATCH).forEach { transactionDao.setCategory(it, categoryId, now) }
        }
        return byCategory.values.sumOf { it.size }
    }

    /** What the user filed each payee under, by normalised description. */
    private suspend fun learnedFromUser(): Map<String, String> =
        transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }

    /**
     * Re-sorts everything the app filed by itself — never an entry with the
     * hidden "set by user" mark. Each goes where the user filed that payee,
     * or else where the built-in list says. Returns how many moved; with
     * [remember], Home is told what moved and can put it back.
     */
    suspend fun resort(remember: Boolean = true): Int {
        // Payees filed in 1.34, before entries carried the mark: mark them now.
        val chosen = settingsRepository.settings.first().categoryPayeesChosen
        val rows = transactionDao.getAppFiled()
        if (chosen.isNotEmpty()) {
            val theirs = rows.filter { PayeeNames.of(it.description).lowercase() in chosen }.map { it.id }
            theirs.chunked(BATCH).forEach { transactionDao.markCategoryByUser(it) }
            settingsRepository.clearCategoryPayeesChosen()
        }

        val learned = learnedFromUser()
        val names = categoryDao.getAll().associate { it.id to it.name }
        val moves = mutableListOf<Move>()
        rows.forEach { row ->
            if (PayeeNames.of(row.description).lowercase() in chosen) return@forEach
            val current = names[row.categoryId]
            val better = Refiling.better(row.description, row.type, current, learned) ?: return@forEach
            val id = categoryIdFor(better, row.type) ?: return@forEach
            if (id == row.categoryId) return@forEach
            moves += Move(row, id, better)
        }
        val now = Instant.now().toEpochMilli()
        moves.groupBy { it.toId }.forEach { (categoryId, group) ->
            group.map { it.row.id }.chunked(BATCH).forEach { transactionDao.setCategory(it, categoryId, now) }
        }
        if (remember) {
            val examples = moves
                .groupBy { "${PayeeNames.of(it.row.description).ifBlank { it.row.description.trim() }} → ${it.to}" }
                .entries.sortedByDescending { (_, group) -> group.sumOf { it.row.amountMinor } }
                .take(NOTE_EXAMPLES)
                .map { it.key }
            settingsRepository.setResorted(
                rulesVersion = MerchantCategoriser.RULES_VERSION,
                summary = if (moves.isEmpty()) "" else ResortNote(moves.size, examples).encode(),
                undo = moves.joinToString(",") { "${it.row.id}:${it.row.categoryId}" },
            )
        }
        return moves.size
    }

    /**
     * Re-sorts once whenever the app's list of shops has changed since the
     * last time — on the first start after an update. Cheap to call on every
     * start: it does nothing until the rules change.
     */
    suspend fun resortIfRulesChanged() {
        if (settingsRepository.settings.first().resortedRulesVersion >= MerchantCategoriser.RULES_VERSION) return
        categoriseUnsorted()
        resort(remember = true)
    }

    /**
     * Puts back what the last automatic re-sort moved, and marks those
     * entries as the user's, so they are not moved again.
     */
    suspend fun undoLastResort() {
        val undo = settingsRepository.settings.first().lastResortUndo
        undo.split(',').forEach { pair ->
            val (id, categoryId) = pair.split(':').takeIf { it.size == 2 } ?: return@forEach
            // A category deleted since cannot be gone back to; that one stays.
            runCatching {
                transactionDao.restoreCategory(id.toLongOrNull() ?: return@forEach, categoryId.toLongOrNull())
            }
        }
        settingsRepository.clearResortNote()
    }

    private class Move(val row: TransactionEntity, val toId: Long, val to: String)

    /**
     * Sets up as bills the regular payments seen at least twice that are
     * still being paid. One that has stopped — its next payment long overdue
     * — is left alone.
     */
    private suspend fun addStillPaidBills(): List<String> {
        val today = DateUtils.today()
        val bills = billFinder.find().filter { bill ->
            bill.isConfirmed &&
                !bill.nextDue(bill.lastDate.plusDays(1)).isBefore(today.minusDays(STOPPED_AFTER_DAYS))
        }
        if (bills.isEmpty()) return emptyList()
        return if (billFinder.addAsBills(bills).isSuccess) bills.map { it.name } else emptyList()
    }

    /** The category called [name], preferring the savings and cash kinds, as the importer does. */
    private suspend fun categoryIdFor(name: String, type: TransactionType): Long? {
        for (kind in listOf(CategoryKind.SAVING, CategoryKind.CASH)) {
            categoryDao.getByNameAndKind(name, kind)?.let { return it.id }
        }
        val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        categoryDao.getByNameAndKind(name, kind)?.let { return it.id }
        return categoryRepository.findOrCreate(name, kind, DefaultData.PALETTE.random()).id
    }

    companion object {
        /** Categories that say only that a card was used. */
        val VAGUE = Refiling.VAGUE

        /**
         * Categories a move between own accounts is often filed under by
         * mistake. "Card spending" is not one: a card was used in a shop, and
         * a shop sharing a word with a card's name is not a payment to it.
         */
        val LOOSE = listOf("Transfers & payments", "Credit & loans", "Car finance", "Mortgage")

        private const val BATCH = 400

        /** How many of the biggest moves Home names. */
        private const val NOTE_EXAMPLES = 3

        /** How overdue a regular payment can be before it counts as stopped. */
        private const val STOPPED_AFTER_DAYS = 10L

        /**
         * [row] as a move between accounts. Money out stays on its account and
         * goes to [otherId]; money in is turned round to come from [otherId].
         */
        fun asTransfer(row: TransactionEntity, otherId: Long, now: Long): TransactionEntity =
            if (row.type == TransactionType.EXPENSE) {
                row.copy(type = TransactionType.TRANSFER, transferAccountId = otherId, categoryId = null, updatedAt = now)
            } else {
                row.copy(
                    type = TransactionType.TRANSFER,
                    accountId = otherId,
                    transferAccountId = row.accountId,
                    categoryId = null,
                    updatedAt = now,
                )
            }
    }
}
