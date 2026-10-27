package com.rhys.financetracker.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.prefs.AppSettings
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.domain.tips.TipPlace
import com.rhys.financetracker.ui.components.TipCard
import com.rhys.financetracker.ui.whatsnew.currentVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TipsSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private fun go(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { settingsRepository.block() }
    }

    fun setTipsOn(on: Boolean) = go { setTipsOn(on) }
    fun setNewPointersOn(on: Boolean) = go { setNewPointersOn(on, currentVersion) }
    fun showTipsAgain() = go { showTipsAgain() }
    fun setProgressPopup(on: Boolean) = go { setProgressPopup(on) }
    fun setNeedsLookPopup(on: Boolean) = go { setNeedsLookPopup(on) }
    fun setBackupReminder(on: Boolean) = go { setBackupReminder(on) }
    fun setStatementNudge(on: Boolean) = go { setLiveStatementNudge(on) }
}

/**
 * Tips, the New marks on what's new, and every pop-up and reminder the app
 * shows, each with its own switch — all in one place.
 */
@Composable
fun TipsSettingsScreen(
    onBack: () -> Unit,
    onOpenNotifications: () -> Unit,
    viewModel: TipsSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    SettingsSubScreen("Tips & reminders", onBack, snackbarHostState) {
        TipCard(TipPlace.SETTINGS, modifier = Modifier.padding(16.dp))

        SettingsGroupHeader("Tips")
        SettingsSwitch(
            title = "Tips on each page",
            subtitle = "A short tip about what you can do there, one at a time",
            checked = settings.tipsOn,
            onCheckedChange = viewModel::setTipsOn,
        )
        SettingsSwitch(
            title = "Point out what's new",
            subtitle = "A New mark and a note where each new thing is, until you've seen it. Turned off, things " +
                "added in later updates are still pointed out once.",
            checked = settings.newPointersOffAt == null,
            onCheckedChange = viewModel::setNewPointersOn,
        )
        SettingsItem(
            title = "Show every tip again",
            subtitle = "Including ones you've already seen",
            onClick = {
                viewModel.showTipsAgain()
                scope.launch { snackbarHostState.showSnackbar("Every tip will show again") }
            },
        )

        SettingsGroupHeader("Pop-ups when the app opens")
        SettingsSwitch(
            title = "How you're doing",
            subtitle = "At the start and middle of each month. Reports are still kept in More → Progress reports.",
            checked = settings.progressPopup,
            onCheckedChange = viewModel::setProgressPopup,
        )
        SettingsSwitch(
            title = "Needs a look",
            subtitle = "Things worth checking. They're still listed on Home and in More → Needs a look.",
            checked = settings.needsLookPopup,
            onCheckedChange = viewModel::setNeedsLookPopup,
        )
        SettingsSwitch(
            title = "Back up reminder",
            subtitle = "When nothing has been backed up for two weeks and automatic backup is off",
            checked = settings.backupReminder,
            onCheckedChange = viewModel::setBackupReminder,
        )

        SettingsGroupHeader("Reminders on your phone")
        SettingsSwitch(
            title = "Statement ready",
            subtitle = "When your bank's app says a new statement is out, a reminder to bring it in",
            checked = settings.liveStatementNudge,
            onCheckedChange = viewModel::setStatementNudge,
        )
        SettingsItem(
            title = "Bills, overdue payments and low balances",
            subtitle = "Reminders before a bill is due, and more, in Notifications",
            onClick = onOpenNotifications,
        )
        SettingsNote("Pop-ups only show once each time the app opens, and never on top of each other.")
    }
}
