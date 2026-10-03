package com.rhys.financetracker.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.importer.SubscriptionTracker
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.BillFinderRepository
import com.rhys.financetracker.data.repository.ResortNote
import com.rhys.financetracker.data.repository.TidyUpRepository
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.transactions.LedgerRequests
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Something that needs a look, and what to do about it. */
data class InboxItem(
    val kind: Kind,
    val title: String,
    val detail: String,
    val action: String,
    /** A second, lesser choice: "Put them back", "Later". */
    val secondAction: String? = null,
    val accountId: Long? = null,
) {
    enum class Kind { RESORTED, UNSORTED, TO_CHECK, OLD_ALERTS, MISSING_STATEMENT, SUBSCRIPTION_MISSED, BACKUP }
}

/** Where an inbox item's action goes; the screen hands these to navigation. */
data class InboxRoutes(
    val sortPayments: () -> Unit,
    val openToCheck: () -> Unit,
    val importStatement: () -> Unit,
    val openAccount: (Long) -> Unit,
    val openBills: () -> Unit,
    val openBackup: () -> Unit,
    val sortEverything: () -> Unit,
)

/**
 * Everything that needs a look, in one list: what used to be a re-sort card
 * and a backup card on Home, a Sort spending page in More, and nothing at
 * all for missing statements or stopped subscriptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val tidyUp: TidyUpRepository,
    private val ledgerRequests: LedgerRequests,
    transactionDao: TransactionDao,
    accountRepository: AccountRepository,
    private val billFinder: BillFinderRepository,
) : ViewModel() {

    private val missedSubscriptions = MutableStateFlow<List<SubscriptionTracker.Subscription>>(emptyList())

    init {
        viewModelScope.launch {
            missedSubscriptions.value = runCatching { billFinder.subscriptions() }.getOrDefault(emptyList())
                .filter { it.status == SubscriptionTracker.Status.MISSED }
        }
    }

    /** Months with no statement, per account that has statements at all: up to last month. */
    private val missingStatements: Flow<List<Triple<Long, String, List<YearMonth>>>> =
        accountRepository.observeActive().flatMapLatest { accounts ->
            if (accounts.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(accounts.map { account -> transactionDao.observeStatementMonths(account.id).map { account to it } }) { rows ->
                val lastMonth = DateUtils.currentYearMonth().minusMonths(1)
                rows.mapNotNull { (account, months) ->
                    val have = months.mapNotNull { DateUtils.parseYearMonthKey(it) }.toSet()
                    val first = have.minOrNull() ?: return@mapNotNull null
                    val from = maxOf(first, lastMonth.minusMonths(LOOK_BACK_MONTHS))
                    val missing = generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(lastMonth) }
                        .filter { it !in have }.toList()
                    missing.takeIf { it.isNotEmpty() }?.let { Triple(account.id, account.name, it) }
                }
            }
        }

    private val counts = combine(
        transactionDao.observeUncategorisedCount(DateUtils.today().minusDays(UNSORTED_DAYS)),
        transactionDao.observeUnconfirmed().map { it.size },
        transactionDao.observeOldLiveCount(DateUtils.today().minusDays(OLD_ALERT_DAYS)),
    ) { unsorted, toCheck, oldAlerts -> Triple(unsorted, toCheck, oldAlerts) }

    val items: StateFlow<List<InboxItem>> = combine(
        settingsRepository.settings,
        accountRepository.observeWithBalances(),
        counts,
        missingStatements,
        missedSubscriptions,
    ) { settings, balances, (unsorted, toCheck, oldAlerts), missing, missed ->
        buildList {
            ResortNote.decode(settings.lastResortSummary)?.let { note ->
                add(
                    InboxItem(
                        InboxItem.Kind.RESORTED,
                        title = "${note.moved} ${if (note.moved == 1) "payment" else "payments"} re-sorted",
                        detail = "The app now knows more shops, so it moved payments it had filed itself" +
                            (if (note.examples.isEmpty()) "." else ": " + note.examples.joinToString(", ") + ".") +
                            " Anything you filed yourself was left alone.",
                        action = "OK",
                        secondAction = "Put them back",
                    ),
                )
            }
            if (toCheck > 0) {
                add(
                    InboxItem(
                        InboxItem.Kind.TO_CHECK,
                        title = "$toCheck ${if (toCheck == 1) "payment" else "payments"} to check",
                        detail = "Added by a regular payment with an amount that changes — check what it really was.",
                        action = "Check",
                    ),
                )
            }
            if (unsorted > 0) {
                add(
                    InboxItem(
                        InboxItem.Kind.UNSORTED,
                        title = "$unsorted ${if (unsorted == 1) "payment" else "payments"} with no category",
                        detail = "Sort them by who they went to — one tap files every payment to the same place.",
                        action = "Sort",
                    ),
                )
            }
            if (oldAlerts > 0) {
                add(
                    InboxItem(
                        InboxItem.Kind.OLD_ALERTS,
                        title = "$oldAlerts from bank alerts waiting for a statement",
                        detail = "Added from alerts over a month ago and not matched to a statement yet. Import " +
                            "the statement and each is replaced by the bank's line.",
                        action = "Import",
                    ),
                )
            }
            missing.forEach { (id, name, months) ->
                add(
                    InboxItem(
                        InboxItem.Kind.MISSING_STATEMENT,
                        title = "$name: ${if (months.size == 1) "a statement is missing" else "${months.size} statements missing"}",
                        detail = "No statement for " + months.takeLast(MONTHS_NAMED).joinToString(", ") { DateUtils.formatMonth(it) } +
                            (if (months.size > MONTHS_NAMED) " and earlier" else "") + ".",
                        action = "Open",
                        accountId = id,
                    ),
                )
            }
            if (missed.isNotEmpty()) {
                add(
                    InboxItem(
                        InboxItem.Kind.SUBSCRIPTION_MISSED,
                        title = "${missed.size} ${if (missed.size == 1) "subscription" else "subscriptions"} may have stopped",
                        detail = missed.take(MONTHS_NAMED).joinToString(", ") { it.name } + " missed a payment.",
                        action = "See",
                    ),
                )
            }
            val now = System.currentTimeMillis()
            val backupDue = balances.isNotEmpty() && !settings.autoBackupEnabled &&
                (settings.lastBackupAt == null || now - settings.lastBackupAt > BACKUP_AFTER_MS) &&
                (settings.backupNudgeSnoozedUntil == null || now > settings.backupNudgeSnoozedUntil)
            if (backupDue) {
                add(
                    InboxItem(
                        InboxItem.Kind.BACKUP,
                        title = "Back up your money",
                        detail = "Everything is only on this phone. A backup keeps it safe if the phone is lost.",
                        action = "Back up",
                        secondAction = "Later",
                    ),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun act(item: InboxItem, routes: InboxRoutes) {
        when (item.kind) {
            InboxItem.Kind.RESORTED -> viewModelScope.launch { settingsRepository.clearResortNote() }
            InboxItem.Kind.UNSORTED -> routes.sortPayments()
            InboxItem.Kind.TO_CHECK -> {
                ledgerRequests.openToCheck()
                routes.openToCheck()
            }
            InboxItem.Kind.OLD_ALERTS -> routes.importStatement()
            InboxItem.Kind.MISSING_STATEMENT -> item.accountId?.let(routes.openAccount)
            InboxItem.Kind.SUBSCRIPTION_MISSED -> routes.openBills()
            InboxItem.Kind.BACKUP -> routes.openBackup()
        }
    }

    fun second(item: InboxItem) {
        when (item.kind) {
            InboxItem.Kind.RESORTED -> viewModelScope.launch { tidyUp.undoLastResort() }
            InboxItem.Kind.BACKUP -> viewModelScope.launch {
                settingsRepository.snoozeBackupNudge(System.currentTimeMillis() + SNOOZE_MS)
            }
            else -> Unit
        }
    }

    private companion object {
        const val UNSORTED_DAYS = 92L
        const val OLD_ALERT_DAYS = 35L
        const val LOOK_BACK_MONTHS = 11L
        const val MONTHS_NAMED = 3
        const val BACKUP_AFTER_MS = 14L * 24 * 60 * 60 * 1000
        const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000
    }
}

private fun iconFor(kind: InboxItem.Kind): ImageVector = when (kind) {
    InboxItem.Kind.RESORTED -> Icons.Outlined.AutoFixHigh
    InboxItem.Kind.UNSORTED -> Icons.Outlined.Category
    InboxItem.Kind.TO_CHECK -> Icons.Outlined.FactCheck
    InboxItem.Kind.OLD_ALERTS -> Icons.Outlined.Bolt
    InboxItem.Kind.MISSING_STATEMENT -> Icons.Outlined.CalendarMonth
    InboxItem.Kind.SUBSCRIPTION_MISSED -> Icons.Outlined.Subscriptions
    InboxItem.Kind.BACKUP -> Icons.Outlined.Backup
}

/** The "Needs a look" card on Home: the first few things, and the way to all of them. Nothing when all is well. */
@Composable
fun NeedsALookCard(
    routes: InboxRoutes,
    onOpenInbox: () -> Unit,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    SectionCard(title = if (items.size == 1) "1 thing needs a look" else "${items.size} things need a look") {
        items.take(HOME_SHOWN).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.act(item, routes) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InboxIcon(item.kind)
                Spacer(Modifier.width(12.dp))
                Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(item.action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (items.size > HOME_SHOWN) {
            TextButton(onClick = onOpenInbox) { Text("See all ${items.size}") }
        }
    }
}

private const val HOME_SHOWN = 3

@Composable
private fun InboxIcon(kind: InboxItem.Kind) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(36.dp)) {
        Icon(
            iconFor(kind),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(8.dp),
        )
    }
}

/** Every thing that needs a look, each with what to do about it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    onBack: () -> Unit,
    routes: InboxRoutes,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Needs a look") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (items.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("All done", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Nothing needs a look right now.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(items, key = { it.kind.name + it.title }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InboxIcon(item.kind)
                            Spacer(Modifier.width(12.dp))
                            Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            OutlinedButton(onClick = { viewModel.act(item, routes) }) {
                                Text(item.action)
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                            item.secondAction?.let { label ->
                                TextButton(onClick = { viewModel.second(item) }) { Text(label) }
                            }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.size(8.dp))
                Text("Tidy up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            item {
                TextButton(onClick = routes.sortPayments) { Text("Sort payments by who they went to") }
                TextButton(onClick = routes.sortEverything) { Text("Sort everything again") }
            }
        }
    }
}
