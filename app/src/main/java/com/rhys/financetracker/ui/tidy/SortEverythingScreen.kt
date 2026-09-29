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
                text = "Goes through everything in the app and puts it where it belongs, " +
                    "using everything the app knows now — so entries imported before a rule " +
                    "existed are sorted too. Anything you have filed yourself is left alone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Step("Pay rises whose date has come are applied.")
                    Step("Money in and out of savers is filed as savings.")
                    Step(
                        "Payments that name another of your accounts become moves to it — the " +
                            "saver goes up, the loan or card comes down.",
                    )
                    Step(
                        "Unsorted payments and \"Card spending\" get a category from what you " +
                            "have filed before, then from the app's list of shops, websites and outings.",
                    )
                    Step(
                        "Payments the app filed by itself follow how you filed the same payee — " +
                            "change one and the rest follow.",
                    )
                    Step("Regular payments seen at least twice and still being paid are set up as bills.")
                    Step("Loans that are paid off are put away.")
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
            Line("Given a category", result.categorised)
            Line("Moved to match how you filed that payee", result.refiled)
            Line("Turned into moves between your accounts", result.linkedToAccounts)
            Line("Filed as savings", result.filedAsSavings)
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
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
