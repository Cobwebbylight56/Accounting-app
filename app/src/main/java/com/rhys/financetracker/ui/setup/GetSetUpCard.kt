package com.rhys.financetracker.ui.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.local.dao.TransactionDao
import com.rhys.financetracker.data.prefs.SettingsRepository
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One thing to do to get the app working for you. */
data class SetupStep(val step: Step, val done: Boolean) {
    enum class Step(val title: String) {
        PEOPLE("Add the people in your household"),
        ACCOUNTS("Add the accounts your money is in"),
        STATEMENT("Import a bank statement"),
        LIVE("Turn on live payments from bank alerts"),
        BACKUP("Take a backup"),
    }
}

/** Where each step is done; the card hands these to navigation. */
data class SetupRoutes(
    val people: () -> Unit,
    val accounts: () -> Unit,
    val statement: () -> Unit,
    val live: () -> Unit,
    val backup: () -> Unit,
)

@HiltViewModel
class GetSetUpViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    peopleRepository: PeopleRepository,
    accountRepository: AccountRepository,
    transactionDao: TransactionDao,
) : ViewModel() {

    /** The steps, or empty once they are all done or the list was put away. */
    val steps: StateFlow<List<SetupStep>> = combine(
        settingsRepository.settings,
        peopleRepository.observeActive(),
        accountRepository.observeActive(),
        transactionDao.observeStatementRowCount(),
    ) { settings, people, accounts, statementRows ->
        val steps = listOf(
            SetupStep(SetupStep.Step.PEOPLE, people.isNotEmpty()),
            SetupStep(SetupStep.Step.ACCOUNTS, accounts.isNotEmpty()),
            SetupStep(SetupStep.Step.STATEMENT, statementRows > 0),
            SetupStep(SetupStep.Step.LIVE, settings.liveAlerts),
            SetupStep(SetupStep.Step.BACKUP, settings.lastBackupAt != null || settings.autoBackupEnabled),
        )
        if (settings.setupChecklistHidden || steps.all { it.done }) emptyList() else steps
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun hide() {
        viewModelScope.launch { settingsRepository.hideSetupChecklist() }
    }
}

/**
 * Get set up: the few things that make the app work for you, ticked off as
 * they are done, in place of a different prompt on every screen. Gone once
 * everything is done, or put away.
 */
@Composable
fun GetSetUpCard(routes: SetupRoutes, viewModel: GetSetUpViewModel = hiltViewModel()) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    if (steps.isEmpty()) return
    val done = steps.count { it.done }
    SectionCard(title = "Get set up", subtitle = "$done of ${steps.size} done") {
        LinearProgressIndicator(
            progress = { done.toFloat() / steps.size },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        steps.forEach { item ->
            val go = when (item.step) {
                SetupStep.Step.PEOPLE -> routes.people
                SetupStep.Step.ACCOUNTS -> routes.accounts
                SetupStep.Step.STATEMENT -> routes.statement
                SetupStep.Step.LIVE -> routes.live
                SetupStep.Step.BACKUP -> routes.backup
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !item.done, onClick = go)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (item.done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (item.done) "Done" else "To do",
                    tint = if (item.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    item.step.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        TextButton(onClick = viewModel::hide) { Text("Hide this") }
    }
}
