package com.rhys.financetracker.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.live.BankAlertListener
import com.rhys.financetracker.data.live.BankApps
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.prefs.AppSettings
import com.rhys.financetracker.data.prefs.LiveAlertLogEntry
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.notify.Notifier
import com.rhys.financetracker.ui.components.AccountOwners
import com.rhys.financetracker.ui.components.DropdownField
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LivePaymentsState(
    val settings: AppSettings = AppSettings(),
    val accounts: List<AccountEntity> = emptyList(),
    /** Whose each account is, so two called the same can be told apart. */
    val people: List<com.rhys.financetracker.data.local.entity.PersonEntity> = emptyList(),
)

@HiltViewModel
class LivePaymentsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    accountRepository: AccountRepository,
    peopleRepository: com.rhys.financetracker.data.repository.PeopleRepository,
    private val notifier: Notifier,
) : ViewModel() {

    /**
     * Posts the pretend alert the reader should hear. False when the app may
     * not post notifications, so there is nothing to hear.
     */
    fun postTest(): Boolean = notifier.postLiveTest()

    fun clearLog() {
        viewModelScope.launch { settingsRepository.clearLiveAlertLog() }
    }

    val state: StateFlow<LivePaymentsState> =
        combine(
            settingsRepository.settings,
            accountRepository.observeActive(),
            peopleRepository.observeActive(),
        ) { settings, accounts, people ->
            LivePaymentsState(settings, accounts, people)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LivePaymentsState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLiveAlerts(enabled) }
    }

    fun setAccount(app: String, accountId: Long?) {
        viewModelScope.launch { settingsRepository.setLiveAlertAccount(app, accountId) }
    }

    fun setCardAccount(ending: String, accountId: Long?) {
        viewModelScope.launch { settingsRepository.setLiveCardAccount(ending, accountId) }
    }

    fun setStatementNudge(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLiveStatementNudge(enabled) }
    }

    fun setAllowed(app: String, allowed: Boolean) {
        viewModelScope.launch { settingsRepository.setLiveAlertAppAllowed(app, allowed) }
    }
}

/**
 * Live payments: payments added the moment a banking app's alert says money
 * moved, then replaced by the statement's line when it is imported.
 *
 * Two things have to be true for it to work, and this page shows both: the
 * switch here is on, and Android has given the app notification access.
 */
@Composable
fun LivePaymentsScreen(
    onBack: () -> Unit,
    viewModel: LivePaymentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings = state.settings
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Access is given in Android's settings, so look again on coming back.
    var hasAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasAccess = hasNotificationAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val bankApps = remember(settings.liveAlertApps) {
        (BankApps.KNOWN.keys + settings.liveAlertApps)
            .mapNotNull { app -> appLabel(context, app)?.let { app to it } }
            .sortedBy { it.second.lowercase() }
    }

    SettingsSubScreen("Live payments", onBack, snackbarHostState) {
        SettingsSwitch(
            title = "Add payments from bank alerts",
            subtitle = "When your banking app tells you money went out or came in, it's added straight away",
            icon = Icons.Outlined.Bolt,
            checked = settings.liveAlerts,
            onCheckedChange = viewModel::setEnabled,
        )

        if (settings.liveAlerts && !hasAccess) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("One more step", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Android needs to let the app see your alerts. Tap below, find Finance Tracker " +
                            "and turn it on, then come back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { openAccessSettings(context) },
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("Allow notification access") }
                }
            }
        }

        if (settings.liveAlerts && hasAccess) {
            WorkingCheck(
                testHeardAt = settings.liveTestHeardAt,
                listenerAt = settings.liveListenerAt,
                onTest = viewModel::postTest,
            )
        }

        SettingsSwitch(
            title = "Tell me when a statement is ready",
            subtitle = "When your bank says a new statement is out, a reminder to bring it in",
            checked = settings.liveStatementNudge,
            onCheckedChange = viewModel::setStatementNudge,
            enabled = settings.liveAlerts,
        )

        if (settings.liveAlerts) {
            SettingsGroupHeader("What it heard lately")
            if (settings.liveAlertLog.isEmpty()) {
                SettingsNote(
                    "Nothing yet. Each payment alert from your banking apps shows here with what was done " +
                        "with it — added, already in, or not read as a payment.",
                )
            } else {
                settings.liveAlertLog.forEach { entry -> HeardLine(entry) }
                TextButton(onClick = viewModel::clearLog, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Clear this list")
                }
            }
        }

        SettingsGroupHeader("Which account each app's payments go to")
        if (bankApps.isEmpty()) {
            SettingsNote(
                "No banking apps found on this phone yet. Nationwide, Lloyds, Halifax, Barclays, HSBC, " +
                    "NatWest, Santander, Monzo, Starling, Revolut, Chase, Amex, Google Wallet, PayPal, " +
                    "Klarna and Curve are read.",
            )
        }
        bankApps.forEach { (app, label) ->
            val options = listOf<AccountEntity?>(null) + state.accounts
            DropdownField(
                label = label,
                options = options,
                selected = state.accounts.firstOrNull { it.id == settings.liveAlertAccounts[app] },
                onSelect = { viewModel.setAccount(app, it?.id) },
                optionLabel = { account ->
                    account?.let { AccountOwners.nameOf(it, state.accounts, state.people) } ?: "Choose automatically"
                },
                placeholder = "Choose automatically",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        SettingsNote(
            "Automatically means an account with the card's last four digits in its name, then one " +
                "named after the bank, then your main account.",
        )

        SettingsGroupHeader("Your cards")
        if (settings.liveCardAccounts.isEmpty()) {
            SettingsNote(
                "Cards show here once an alert names one by its last four digits. Google Wallet only " +
                    "says which card was tapped, so this is how its payments find the right account.",
            )
        } else {
            SettingsNote("Google Wallet taps on each card go to the account beside it. Your bank's alerts fill these in.")
        }
        settings.liveCardAccounts.entries.sortedBy { it.key }.forEach { (ending, accountId) ->
            DropdownField(
                label = "Card ending $ending",
                options = listOf<AccountEntity?>(null) + state.accounts,
                selected = state.accounts.firstOrNull { it.id == accountId },
                onSelect = { viewModel.setCardAccount(ending, it?.id) },
                optionLabel = { account ->
                    account?.let { AccountOwners.nameOf(it, state.accounts, state.people) } ?: "Forget this card"
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        val others = settings.liveAlertSeen
            .map { it.substringBefore('|') to it.substringAfter('|') }
            .filter { (app, _) -> app !in BankApps.KNOWN }
        if (others.isNotEmpty()) {
            SettingsGroupHeader("Other apps that sent payment alerts")
            SettingsNote("Not read unless you allow them. Only the app's name was kept, not what it said.")
            others.forEach { (app, label) ->
                SettingsItem(
                    title = label,
                    subtitle = if (app in settings.liveAlertApps) "Payments added" else "Ignored",
                    onClick = { viewModel.setAllowed(app, app !in settings.liveAlertApps) },
                    trailing = {
                        Switch(
                            checked = app in settings.liveAlertApps,
                            onCheckedChange = { viewModel.setAllowed(app, it) },
                        )
                    },
                )
            }
        }

        SettingsGroupHeader("How it works")
        SettingsNote(
            "Payments from alerts are filed like statement lines and show on Home and Spending at once. " +
                "An overpayment, or a payment to a card, Klarna or loan, comes off what you owe on it " +
                "rather than counting as spending. A payment PayPal, Klarna or Google Wallet tells you " +
                "about that your bank tells you about too is only added once — and if Google Wallet " +
                "got there first, your bank's alert moves it to the right account. " +
                "When you import that month's statement, each one is matched to the bank's line and " +
                "replaced by it, so nothing is counted twice. Direct Debits and standing orders often " +
                "don't send an alert, so those still come in with the statement.",
        )
        SettingsNote(
            "Alerts are read on this phone only and nothing is sent anywhere. Only banking apps' alerts " +
                "are noted under What it heard lately: a payment's amount and payee, or — for an alert " +
                "that mentions a £ amount but wasn't read as a payment — its words, so you can see what " +
                "was missed. The last 20 are kept; Clear this list removes them. Turn the switch off, or " +
                "take the app's notification access away in Android's settings, to stop at any time.",
        )
    }
}

/**
 * Whether reading alerts really works: a pretend alert is posted, and the
 * reader says when it hears it. Not heard within a few seconds, it says what
 * usually fixes that.
 */
@Composable
private fun WorkingCheck(testHeardAt: Long?, listenerAt: Long?, onTest: () -> Boolean) {
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var blocked by remember { mutableStateOf(false) }
    var timedOut by remember { mutableStateOf(false) }
    val started = startedAt
    LaunchedEffect(started) {
        if (started != null) {
            timedOut = false
            delay(TEST_WAIT_MILLIS)
            timedOut = true
        }
    }
    val heard = started != null && testHeardAt != null && testHeardAt >= started
    val (headline, detail, colour) = when {
        blocked -> Triple(
            "Can't test yet",
            "The test needs the app's own notifications. Allow them in Android's settings for Finance Tracker, then test again.",
            MaterialTheme.colorScheme.tertiaryContainer,
        )
        heard -> Triple(
            "Working",
            "The test alert was read straight away. Payment alerts from your banking apps are read the same way.",
            MaterialTheme.colorScheme.primaryContainer,
        )
        started != null && !timedOut -> Triple("Testing…", "Posting a test alert and listening for it.", MaterialTheme.colorScheme.surfaceVariant)
        started != null -> Triple(
            "The test alert wasn't heard",
            "Turn notification access off and on again for Finance Tracker, then test again. Some phones " +
                "also stop apps in the background: set Finance Tracker's battery use to Unrestricted.",
            MaterialTheme.colorScheme.errorContainer,
        )
        else -> Triple(
            "Is it working?",
            listenerAt?.let { "The alert reader last started ${TIME.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))}. " }
                .orEmpty() + "Test it to be sure: a pretend alert is posted and read. Nothing is added.",
            MaterialTheme.colorScheme.surfaceVariant,
        )
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = colour),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    blocked = false
                    if (onTest()) startedAt = System.currentTimeMillis() else blocked = true
                },
                enabled = started == null || heard || timedOut,
                modifier = Modifier.padding(top = 10.dp),
            ) { Text(if (started == null) "Test it" else "Test again") }
        }
    }
}

/** One alert heard: when, from which app, what came of it, and the detail. */
@Composable
private fun HeardLine(entry: LiveAlertLogEntry) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)) {
        Text(
            "${TIME.format(Instant.ofEpochMilli(entry.at).atZone(ZoneId.systemDefault()))} · ${entry.app} — ${entry.outcome}",
            style = MaterialTheme.typography.labelLarge,
            color = if (entry.outcome == "Added" || entry.outcome == "Heard") {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (entry.detail.isNotBlank()) {
            Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** How long the test waits to hear its alert before saying it wasn't. */
private const val TEST_WAIT_MILLIS = 6_000L

private fun hasNotificationAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

private fun appLabel(context: Context, app: String): String? = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(app, 0)).toString()
}.getOrNull()

/** Android's page for this app's notification access, or the list of all such apps. */
private fun openAccessSettings(context: Context) {
    val component = ComponentName(context, BankAlertListener::class.java).flattenToString()
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val opened = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        runCatching { context.startActivity(detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) runCatching { context.startActivity(list.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
