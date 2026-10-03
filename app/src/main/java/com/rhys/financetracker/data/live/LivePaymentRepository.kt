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
) {

    enum class Outcome { ADDED, OFF, NOT_A_PAYMENT, NOT_ALLOWED, ALREADY_ADDED, NO_ACCOUNT }

    /**
     * Reads one alert from [app] (shown as [appLabel]), posted at [postedAt]
     * epoch millis, and adds the payment in it when there is one.
     */
    suspend fun record(app: String, appLabel: String, title: String?, text: String?, postedAt: Long): Outcome {
        val settings = settingsRepository.settings.first()
        if (!settings.liveAlerts) return Outcome.OFF
        if (app in BankApps.NEVER) return Outcome.NOT_ALLOWED

        val alert = BankAlertParser.parse(title, text) ?: return Outcome.NOT_A_PAYMENT
        if (app !in BankApps.KNOWN && app !in settings.liveAlertApps) {
            // Offered on the Live payments page; nothing of the alert is kept.
            settingsRepository.noteLiveAlertApp(app, appLabel)
            return Outcome.NOT_ALLOWED
        }

        // The same alert posted again (an update, or after a restart), or the
        // same payment told by the bank and by Google Wallet a moment apart.
        val hash = "live:$app:$postedAt:${alert.amountMinor}:${alert.type.name}"
        if (transactionDao.countWithHash(hash) > 0) return Outcome.ALREADY_ADDED
        val now = Instant.now().toEpochMilli()
        if (transactionDao.countRecentLive(alert.amountMinor, alert.type.name, now - SAME_PAYMENT_MILLIS) > 0) {
            return Outcome.ALREADY_ADDED
        }

        val accounts = accountDao.getAllActive()
        val chosen = settings.liveAlertAccounts[app]?.takeIf { id -> accounts.any { it.id == id } }
        val accountId = chosen ?: BankApps.pickAccount(
            bank = BankApps.bankName(app),
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

        val learned = transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in Refiling.VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }
        val categoryName = MerchantCategoriser.categoryFor(alert.payee, alert.type, learned)
        val categoryId = categoryName?.let { categoryIdFor(it, alert.type) }

        val date = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        transactionDao.insert(
            TransactionEntity(
                amountMinor = alert.amountMinor,
                type = alert.type,
                date = date,
                description = alert.payee,
                accountId = accountId,
                categoryId = categoryId,
                isCleared = false,
                importHash = hash,
                source = RecordSource.LIVE,
            ),
        )

        val time = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).format(TIME)
        val account = accounts.firstOrNull { it.id == accountId }?.name
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
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    }
}
