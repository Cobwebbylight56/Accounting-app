package com.rhys.financetracker.data.live

import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.importer.MerchantCategoriser
import com.rhys.financetracker.data.importer.Refiling
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.TransactionFingerprint
import com.rhys.financetracker.data.local.dao.AccountDao
import com.rhys.financetracker.data.local.dao.CategoryDao
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.data.prefs.AppSettings
import com.rhys.financetracker.data.prefs.LiveAlertLogEntry
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

    enum class Outcome { ADDED, OFF, NOT_A_PAYMENT, NOT_ALLOWED, ALREADY_ADDED, NO_ACCOUNT, STATEMENT_READY, BALANCE }

    /**
     * Reads one alert from [app] (shown as [appLabel]), posted at [postedAt]
     * epoch millis, and adds the payment in it when there is one.
     */
    suspend fun record(app: String, appLabel: String, title: String?, text: String?, postedAt: Long): Outcome {
        val outcome = read(app, appLabel, title, text, postedAt)
        log(app, appLabel, title, text, outcome)
        return outcome
    }

    /** Android has started the alert reader. */
    suspend fun listenerConnected() = settingsRepository.setLiveListenerAt(Instant.now().toEpochMilli())

    /** The alert reader heard the app's own test alert: reading alerts works. */
    suspend fun testHeard() {
        val now = Instant.now().toEpochMilli()
        settingsRepository.setLiveTestHeard(now)
        settingsRepository.logLiveAlert(
            LiveAlertLogEntry(now, "Test", "Heard", "Reading alerts works — nothing was added"),
        )
    }

    /**
     * Notes on the Live payments page what came of an alert from a banking
     * app, so it can be seen working — or seen not reading something it
     * should. Only banking apps and ones allowed; only a payment's amount and
     * payee are kept, or, for an alert with a £ in it that wasn't read as a
     * payment, its words, to show what was missed.
     */
    private suspend fun log(app: String, appLabel: String, title: String?, text: String?, outcome: Outcome) {
        val settings = settingsRepository.settings.first()
        if (outcome == Outcome.OFF || outcome == Outcome.ADDED) return
        if (app !in BankApps.KNOWN && app !in settings.liveAlertApps) return
        val words = listOfNotNull(title, text).joinToString(" — ").replace(Regex("""\s+"""), " ").trim()
        val (said, detail) = when (outcome) {
            Outcome.NOT_A_PAYMENT ->
                if ('£' in words) "Not read as a payment" to words.take(MAX_LOGGED) else return
            Outcome.ALREADY_ADDED -> "Already in" to "Told twice, or added on its day already"
            Outcome.NO_ACCOUNT -> "Not added" to "Couldn't tell which account — choose one for $appLabel below"
            Outcome.STATEMENT_READY -> "Statement ready" to "A new statement to bring in"
            Outcome.NOT_ALLOWED -> return
            // Noted where it was set, with the account's name.
            Outcome.BALANCE -> return
            Outcome.OFF, Outcome.ADDED -> return
        }
        settingsRepository.logLiveAlert(LiveAlertLogEntry(Instant.now().toEpochMilli(), appLabel, said, detail))
    }

    private suspend fun read(app: String, appLabel: String, title: String?, text: String?, postedAt: Long): Outcome {
        val settings = settingsRepository.settings.first()
        if (!settings.liveAlerts) return Outcome.OFF
        if (app in BankApps.NEVER) return Outcome.NOT_ALLOWED

        // A statement is ready: no money moved, but it is the time to import it.
        val bank = BankApps.bankName(app)
        if (bank != null && BankAlertParser.isStatementReady(title, text)) {
            if (settings.liveStatementNudge) notifier.notifyStatementReady(bank)
            return Outcome.STATEMENT_READY
        }

        val tapApp = app in BankApps.CARD_TAP_APPS
        // The bank's own word on what is in the account, when its alert gives
        // it: what the app shows as there to spend is then the bank's figure.
        val balance = if (bank != null && app !in BankApps.ALSO_TOLD_BY_BANK) BankAlertParser.balanceIn(title, text) else null
        val alert = BankAlertParser.parse(title, text, assumeSpending = tapApp)
        if (alert == null) {
            if (balance == null) return Outcome.NOT_A_PAYMENT
            val accounts = accountDao.getAllActive()
            val accountId = chooseAccount(app, bank, tapApp, BankAlertParser.endingIn(title, text), settings, accounts)
                ?: return Outcome.NO_ACCOUNT
            return if (setBalance(accountId, balance, accounts, appLabel)) Outcome.BALANCE else Outcome.NOT_A_PAYMENT
        }
        if (app !in BankApps.KNOWN && app !in settings.liveAlertApps) {
            // Offered on the Live payments page; nothing of the alert is kept.
            settingsRepository.noteLiveAlertApp(app, appLabel)
            return Outcome.NOT_ALLOWED
        }

        // The same alert posted again: an update, or after a restart.
        val hash = "live:$app:$postedAt:${alert.amountMinor}:${alert.type.name}"
        if (transactionDao.countWithHash(hash) > 0) return Outcome.ALREADY_ADDED

        val accounts = accountDao.getAllActive()
        val accountId = chooseAccount(app, bank, tapApp, alert.ending, settings, accounts) ?: return Outcome.NO_ACCOUNT
        // Learn the card, so a Google Wallet tap on it finds this account. The
        // bank's own alert knows the account; a wallet's is only a first guess.
        alert.ending?.let { ending ->
            if (tapApp) {
                settingsRepository.rememberLiveCard(ending, accountId)
            } else {
                settingsRepository.setLiveCardAccount(ending, accountId)
            }
        }

        // The same payment told twice: by the bank and Google Wallet a moment
        // apart, or by the bank and PayPal or Klarna hours apart. Whichever
        // came first is kept — but the bank knows the account for certain, so
        // when Wallet or PayPal came first, the bank's alert puts it right.
        val now = Instant.now().toEpochMilli()
        val sameMoment = transactionDao.recentLiveHashes(alert.amountMinor, alert.type.name, now - SAME_PAYMENT_MILLIS)
        val sameDay = transactionDao.recentLiveHashes(alert.amountMinor, alert.type.name, now - TOLD_TWICE_MILLIS)
        val twin = sameMoment.firstOrNull() ?: sameDay.firstOrNull { other ->
            val otherApp = appOf(other)
            otherApp != app && (app in BankApps.ALSO_TOLD_BY_BANK || otherApp in BankApps.ALSO_TOLD_BY_BANK)
        }
        // Already in, but the balance the bank gives with it still counts.
        suspend fun alreadyIn(): Outcome {
            if (balance != null) setBalance(accountId, balance, accounts, appLabel = null)
            return Outcome.ALREADY_ADDED
        }
        if (twin != null) {
            if (app !in BankApps.ALSO_TOLD_BY_BANK && appOf(twin) in BankApps.ALSO_TOLD_BY_BANK) {
                transactionDao.moveLive(twin, accountId, now)
            }
            return alreadyIn()
        }

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
            return alreadyIn()
        }

        // A regular payment the app has already added on its day — the car
        // loan on the 1st — and this is the bank saying it went.
        val date = Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        if (transactionDao.findScheduledNear(
                accountId = accountId,
                amountMinor = alert.amountMinor,
                incoming = alert.type == TransactionType.INCOME,
                from = date.minusDays(SCHEDULED_DAYS),
                until = date.plusDays(SCHEDULED_DAYS),
                around = date,
            ) != null
        ) {
            return alreadyIn()
        }

        val learned = transactionDao.getUserFiledDescriptions(SpreadsheetImporter.LEARNED_PAYEE_LIMIT)
            .filterNot { it.categoryName in Refiling.VAGUE }
            .associate { TransactionFingerprint.normaliseDescription(it.description) to it.categoryName }
        val categoryName = if (paidOff == null) MerchantCategoriser.categoryFor(alert.payee, alert.type, learned) else null
        val categoryId = categoryName?.let { categoryIdFor(it, alert.type) }

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
        val summary = listOfNotNull(
            (if (alert.type == TransactionType.INCOME) "+" else "") + Money.format(alert.amountMinor),
            alert.payee,
            categoryName,
            account,
            time,
        ).joinToString(" · ")
        settingsRepository.setLiveAlertLast(summary)
        // The balance after it, when the alert gives one, is the account's now.
        val balanceSet = balance != null && setBalance(accountId, balance, accounts, appLabel = null)
        settingsRepository.logLiveAlert(
            LiveAlertLogEntry(
                Instant.now().toEpochMilli(),
                appLabel,
                "Added",
                summary + if (balanceSet) " · balance now ${Money.format(balance!!)}" else "",
            ),
        )
        return Outcome.ADDED
    }

    /**
     * Where a payment from [app] goes. A bank's alerts go to the account
     * chosen for that bank, then the card's; a wallet's to the card's account
     * first, as one wallet holds cards from several banks. Failing both, one
     * is picked from the card's digits and the bank's name.
     */
    /**
     * Makes [balanceMinor] what [accountId] holds from today — the bank's own
     * figure, so Available is what is really there. Only for an account to
     * spend from or save in: a card's "balance" is what is owed, the other
     * way round. With [appLabel], noted on the Live payments page.
     */
    private suspend fun setBalance(accountId: Long, balanceMinor: Long, accounts: List<AccountEntity>, appLabel: String?): Boolean {
        val account = accounts.firstOrNull { it.id == accountId } ?: return false
        if (account.holding == Holding.OWED) return false
        val now = Instant.now()
        val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
        // What was typed in or added on its day today still counts on top of
        // a balance for today, and the bank's figure already has it in.
        val countedOnTop = transactionDao.manualNetOn(accountId, today)
        accountDao.setBalanceAsOf(accountId, balanceMinor - countedOnTop, today, now.toEpochMilli())
        if (appLabel != null) {
            settingsRepository.logLiveAlert(
                LiveAlertLogEntry(now.toEpochMilli(), appLabel, "Balance", "${account.name}: ${Money.format(balanceMinor)}"),
            )
        }
        return true
    }

    private fun chooseAccount(
        app: String,
        bank: String?,
        tapApp: Boolean,
        ending: String?,
        settings: AppSettings,
        accounts: List<AccountEntity>,
    ): Long? {
        fun valid(id: Long?) = id?.takeIf { wanted -> accounts.any { it.id == wanted } }
        val forApp = valid(settings.liveAlertAccounts[app])
        val forCard = valid(ending?.let { settings.liveCardAccounts[it] })
        (if (tapApp) forCard ?: forApp else forApp ?: forCard)?.let { return it }
        return BankApps.pickAccount(
            bank = bank.takeUnless { tapApp },
            ending = ending,
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
        )
    }

    /** The app a live payment's hash ("live:<app>:…") came from. */
    private fun appOf(hash: String): String = hash.split(':').getOrNull(1).orEmpty()

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

        /** How far from its day a regular payment may actually go out. */
        const val SCHEDULED_DAYS = 4L

        /** How much of a missed alert is kept to show what it said. */
        const val MAX_LOGGED = 160
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
    }
}
