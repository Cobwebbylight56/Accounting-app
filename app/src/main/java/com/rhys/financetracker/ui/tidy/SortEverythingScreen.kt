package com.rhys.financetracker.ui.tidy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.repository.TidyUpRepository
import com.rhys.financetracker.data.repository.TidyUpResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SortEverythingState(
    val isRunning: Boolean = false,
    val result: TidyUpResult? = null,
    val error: String? = null,
)

@HiltViewModel
class SortEverythingViewModel @Inject constructor(
    private val tidyUp: TidyUpRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SortEverythingState())
    val state: StateFlow<SortEverythingState> = _state.asStateFlow()

    fun run() {
        if (_state.value.isRunning) return
        _state.value = SortEverythingState(isRunning = true)
        viewModelScope.launch {
            _state.value = runCatching { tidyUp.sortEverything() }.fold(
                onSuccess = { SortEverythingState(result = it) },
                onFailure = { SortEverythingState(error = "Could not finish sorting: ${it.message ?: "unknown error"}") },
            )
        }
    }
}

/**
 * One button that files everything already in the app by today's rules,
 * instead of moving each entry by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortEverythingScreen(
    onBack: () -> Unit,
    onOpenSortSpending: () -> Unit,
    viewModel: SortEverythingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sort everything") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Goes through every payment in the app and puts it where it belongs, using " +
                    "everything the app knows now. Anything you filed yourself is left alone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Which account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Step(
                        "Card payments go on the card they were paid with — a Google Wallet tap on the " +
                            "Zopa card goes on the Zopa card, not the current account.",
                    )
                    Step("Payments added twice, or on the wrong account, by an older version are put right.")
                    Text("Moves, not spending", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Step(
                        "Payments that name another of your accounts — by its name, or its sort code and " +
                            "account number — become moves to it: the saver goes up, the card comes down.",
                    )
                    Step("Money between the two of you — \"EVANS RHYS\" — is a move, not spending.")
                    Step("Money in and out of savers is filed as savings.")
                    Text("Categories", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Step(
                        "Unsorted payments get a category from what you've filed before, then from the " +
                            "app's list of shops — and ones the app filed move to where you filed that payee.",
                    )
                    Text("Bills and loans", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Step("Regular payments still being paid become bills; paid-off loans are put away.")
                }
            }

            if (state.isRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Sorting…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = viewModel::run, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.result == null) "Sort everything now" else "Run again")
                }
            }

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            state.result?.let { result ->
                ResultCard(result = result, onOpenSortSpending = onOpenSortSpending)
            }

        }
    }
}

@Composable
private fun Step(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultCard(result: TidyUpResult, onOpenSortSpending: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (result.changedAnything) "Done" else "Everything was already in the right place",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Checked ${result.entriesChecked} entries on ${result.accountsChecked} " +
                    (if (result.accountsChecked == 1) "account." else "accounts."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only what changed: a list of noughts hid the one line that mattered.
            Line("Card payments put on their card", result.cardPayments)
            Line("Older mistakes put right", result.putRight)
            Line("Turned into moves between your accounts", result.linkedToAccounts)
            Line("Money between you filed as moves", result.betweenYou)
            Line("Filed as savings", result.filedAsSavings)
            Line("Given a category", result.categorised)
            Line("Moved to a better category", result.refiled)
            Line("Pay rises applied", result.payRisesApplied)
            Line("Regular payments set up as bills", result.billsAdded.size)
            if (result.billsAdded.isNotEmpty()) {
                Text(
                    text = result.billsAdded.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (result.loansCleared.isNotEmpty()) {
                Text(
                    text = "Paid off and put away: ${result.loansCleared.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (result.stillUnsorted > 0) {
                Text(
                    text = "${result.stillUnsorted} ${if (result.stillUnsorted == 1) "payment" else "payments"} " +
                        "the app could not place. File them by who they went to and it learns them for next time.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenSortSpending, modifier = Modifier.fillMaxWidth()) {
                    Text("Sort the rest")
                }
            } else {
                Text("Nothing left unsorted.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Line(label: String, count: Int) {
    if (count == 0) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
