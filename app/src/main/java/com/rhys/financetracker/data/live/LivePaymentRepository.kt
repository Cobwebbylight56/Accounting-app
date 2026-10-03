package com.rhys.financetracker.data.live

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.domain.model.RecordSource
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.notify.Notifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Turns a banking app's alert into a payment, the moment it arrives.
 *
 * The payment is filed like a statement row — learned payees first, then the
 * app's own rules — and marked as coming from an alert. When the statement
 * for that month is imported, its line for the same payment replaces this one
 * (see StatementPriority), so nothing is counted twice and the bank's own
 * wording and date win.
 */
@Singleton
class LivePaymentRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val accountDao: AccountDao,
    private val categoryDao: CategoryDao,
    private val settingsRepository: SettingsRepository,
    private val notifier: Notifier,
) {

    enum class Outcome { ADDED, OFF, NOT_A_PAYMENT, NOT_ALLOWED, ALREADY_ADDED, NO_ACCOUNT, STATEMENT_READY }

    /**
     * Reads one alert from [app] (shown as [appLabel]), posted at [postedAt]
     * epoch millis, and adds the payment in it when there is one.
     */
    suspend fun record(app: String, appLabel: String, title: String?, text: String?, postedAt: Long): Outcome {
        val settings = settingsRepository.settings.first()
        if (!settings.liveAlerts) return Outcome.OFF
        if (app in BankApps.NEVER) return Outcome.NOT_ALLOWED

        // A statement is ready: no money moved, but it is the time to import it.
        val bank = BankApps.bankName(app)
        if (bank != null && BankAlertParser.isStatementReady(title, text)) {
            if (settings.liveStatementNudge) notifier.notifyStatementReady(bank)
            return Outcome.STATEMENT_READY
        }

        val alert = BankAlertParser.parse(title, text) ?: return Outcome.NOT_A_PAYMENT
        if (app !in BankApps.KNOWN && app !in settings.liveAlertApps) {
            // Offered on the Live payments page; nothing of the alert is kept.
            settingsRepository.noteLiveAlertApp(app, appLabel)
            return Outcome.NOT_ALLOWED
        }

        // The same alert posted again (an update, or after a restart), or the
        // same payment told twice a moment apart.
        val hash = "live:$app:$postedAt:${alert.amountMinor}:${alert.type.name}"
        if (transactionDao.countWithHash(hash) > 0) return Outcome.ALREADY_ADDED
        val now = Instant.now().toEpochMilli()
        if (transactionDao.countRecentLive(alert.amountMinor, alert.type.name, now - SAME_PAYMENT_MILLIS) > 0) {
            return Outcome.ALREADY_ADDED
        }
        // A PayPal, Klarna or Google Wallet payment the bank also told of, or
        // the other way round, can be hours apart.
        val toldByOthers = transactionDao.recentLiveHashes(alert.amountMinor, alert.type.name, now - TOLD_TWICE_MILLIS)
            .map { it.split(':').getOrNull(1).orEmpty() }
            .filter { it.isNotEmpty() && it != app }
        if (toldByOthers.any { other -> app in BankApps.ALSO_TOLD_BY_BANK || other in BankApps.ALSO_TOLD_BY_BANK }) {
            return Outcome.ALREADY_ADDED
        }

        val accounts = accountDao.getAllActive()
        val chosen = settings.liveAlertAccounts[app]?.takeIf { id -> accounts.any { it.id == id } }
        val accountId = chosen ?: BankApps.pickAccount(
            bank = bank,
            ending = alert.ending,
            accounts = accounts.map { account ->
                BankApps.AccountOption(
                    id = account.id,
                    name = account.name,
                    notes = account.notes,
                    isSpending = account.holding == Holding.SPEND,
                    isCard = account.type == AccountType.CREDIT_CARD,
                )
            },
            defaultAccountId = settings.defaultAccountId,
        ) ?: return Outcome.NO_ACCOUNT

        // Money paid to a mortgage, loan, card or pay-later plan — an
        // overpayment, a Klarna instalment — is a move into it: it comes off
        // what is owed, and is not spending.
        val paidOff = if (alert.type == TransactionType.EXPENSE) {
            BankApps.pickBorrowing(
                kind = alert.toBorrowing,
                payee = alert.payee,
                borrowing = accounts.mapNotNull { account ->
                    borrowingKind(account.type)?.let { BankApps.BorrowingOption(account.id, account.name, it) }
                },
                fromAccountId = accountId,
            )
        } else {
            null
        }
        // A card payment is told twice: money out by the bank, money in by the card.
        val paidInto = paidOff ?: accountId.takeIf {
            alert.type == TransactionType.INCOME &&
                accounts.firstOrNull { it.id == accountId }?.let { borrowingKind(it.type) } != null
        }
        if (paidInto != null &&
            transactionDao.countLivePaidInto(paidInto, alert.amountMinor, now - TOLD_TWICE_MILLIS) > 0
        ) {
            return Outcome.ALREADY_ADDED
        }

        val learned = transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in Refiling.VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }
        val categoryName = if (paidOff == null) MerchantCategoriser.categoryFor(alert.payee, alert.type, learned) else null
        val categoryId = categoryName?.let { categoryIdFor(it, alert.type) }

        val date = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        transactionDao.insert(
            TransactionEntity(
                amountMinor = alert.amountMinor,
                type = if (paidOff != null) TransactionType.TRANSFER else alert.type,
                date = date,
                description = alert.payee,
                accountId = accountId,
                transferAccountId = paidOff,
                categoryId = categoryId,
                isCleared = false,
                importHash = hash,
                source = RecordSource.LIVE,
            ),
        )

        val time = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).format(TIME)
        val account = listOfNotNull(
            accounts.firstOrNull { it.id == accountId }?.name,
            paidOff?.let { id -> accounts.firstOrNull { it.id == id }?.name },
        ).joinToString(" → ")
        settingsRepository.setLiveAlertLast(
            listOfNotNull(
                (if (alert.type == TransactionType.INCOME) "+" else "") + Money.format(alert.amountMinor),
                alert.payee,
                categoryName,
                account,
                time,
            ).joinToString(" · "),
        )
        return Outcome.ADDED
    }

    /** "mortgage", "loan", "credit card" or "pay later" for borrowing; null for anything else. */
    private fun borrowingKind(type: AccountType): String? = when (type) {
        AccountType.MORTGAGE -> "mortgage"
        AccountType.LOAN -> "loan"
        AccountType.CREDIT_CARD -> "credit card"
        AccountType.PAY_LATER -> "pay later"
        else -> null
    }

    /** The category called [name]; the savings, cash and transfer kinds first, as the importer does. */
    private suspend fun categoryIdFor(name: String, type: TransactionType): Long? {
        for (kind in listOf(CategoryKind.SAVING, CategoryKind.CASH, CategoryKind.TRANSFER)) {
            categoryDao.getByNameAndKind(name, kind)?.let { return it.id }
        }
        val kind = if (type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        return categoryDao.getByNameAndKind(name, kind)?.id
    }

    private companion object {
        /** Two alerts for one payment arrive well within this. */
        const val SAME_PAYMENT_MILLIS = 3 * 60 * 1000L

        /** How far apart a bank and a payment app may tell of one payment. */
        const val TOLD_TWICE_MILLIS = 24 * 60 * 60 * 1000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    }
}
