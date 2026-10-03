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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.ui.components.DropdownField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LivePaymentsState(
    val settings: AppSettings = AppSettings(),
    val accounts: List<AccountEntity> = emptyList(),
)

@HiltViewModel
class LivePaymentsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    val state: StateFlow<LivePaymentsState> =
        combine(settingsRepository.settings, accountRepository.observeActive()) { settings, accounts ->
            LivePaymentsState(settings, accounts)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LivePaymentsState())

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLiveAlerts(enabled) }
    }

    fun setAccount(app: String, accountId: Long?) {
        viewModelScope.launch { settingsRepository.setLiveAlertAccount(app, accountId) }
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
        } else if (settings.liveAlerts) {
            SettingsNote("Working: alerts from the banking apps below are read as they arrive.")
        }

        if (settings.liveAlertLast.isNotBlank()) {
            SettingsGroupHeader("Last added")
            SettingsNote(settings.liveAlertLast)
        }

        SettingsGroupHeader("Which account each app's payments go to")
        if (bankApps.isEmpty()) {
            SettingsNote(
                "No banking apps found on this phone yet. Nationwide, Lloyds, Halifax, Barclays, HSBC, " +
                    "NatWest, Santander, Monzo, Starling, Revolut, Chase, Amex and Google Wallet are read.",
            )
        }
        bankApps.forEach { (app, label) ->
            val options = listOf<AccountEntity?>(null) + state.accounts
            DropdownField(
                label = label,
                options = options,
                selected = state.accounts.firstOrNull { it.id == settings.liveAlertAccounts[app] },
                onSelect = { viewModel.setAccount(app, it?.id) },
                optionLabel = { it?.name ?: "Choose automatically" },
                placeholder = "Choose automatically",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        SettingsNote(
            "Automatically means an account with the card's last four digits in its name, then one " +
                "named after the bank, then your main account.",
        )

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
                "When you import that month's statement, each one is matched to the bank's line and " +
                "replaced by it, so nothing is counted twice. Direct Debits and standing orders often " +
                "don't send an alert, so those still come in with the statement.",
        )
        SettingsNote(
            "Alerts are read on this phone only. Nothing is sent anywhere, and the text of an alert " +
                "that isn't a payment is never kept. Turn the switch off, or take the app's notification " +
                "access away in Android's settings, to stop at any time.",
        )
    }
}

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
