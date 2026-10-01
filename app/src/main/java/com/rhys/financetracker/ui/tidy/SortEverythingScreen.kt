package com.rhys.financetracker.ui.tidy

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
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
    /** Payees whose category the shop's own name disagrees with. */
    val fixes: List<com.rhys.financetracker.data.repository.CategoryFix> = emptyList(),
    /** The fixes ticked to be moved, by key; all of them to begin with. */
    val ticked: Set<String> = emptySet(),
    val fixNote: String? = null,
)

@HiltViewModel
class SortEverythingViewModel @Inject constructor(
    private val tidyUp: TidyUpRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SortEverythingState())
    val state: StateFlow<SortEverythingState> = _state.asStateFlow()

    init {
        loadFixes()
    }

    fun run() {
        if (_state.value.isRunning) return
        _state.value = SortEverythingState(isRunning = true)
        viewModelScope.launch {
            _state.value = runCatching { tidyUp.sortEverything() }.fold(
                onSuccess = { SortEverythingState(result = it) },
                onFailure = { SortEverythingState(error = "Could not finish sorting: ${it.message ?: "unknown error"}") },
            )
            loadFixes()
        }
    }

    private fun loadFixes(note: String? = null) {
        viewModelScope.launch {
            val fixes = runCatching { tidyUp.suggestCategoryFixes() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(fixes = fixes, ticked = fixes.map { it.key }.toSet(), fixNote = note)
        }
    }

    fun toggle(key: String) {
        val ticked = _state.value.ticked
        _state.value = _state.value.copy(ticked = if (key in ticked) ticked - key else ticked + key)
    }

    /** Moves the ticked ones and remembers the unticked ones as right where they are. */
    fun applyFixes() {
        val current = _state.value
        val (move, keep) = current.fixes.partition { it.key in current.ticked }
        viewModelScope.launch {
            val moved = tidyUp.applyCategoryFixes(move)
            tidyUp.keepAsTheyAre(keep)
            loadFixes(
                "$moved " + (if (moved == 1) "payment" else "payments") + " moved" +
                    if (keep.isNotEmpty()) "; ${keep.size} left as they are and not suggested again." else ".",
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

            state.fixNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            if (state.fixes.isNotEmpty()) {
                FixesCard(state = state, onToggle = viewModel::toggle, onApply = viewModel::applyFixes)
            }
        }
    }
}

/**
 * Payments in a category their shop's name disagrees with, to be looked over
 * and moved. Unticked ones are left as they are and not suggested again.
 */
@Composable
private fun FixesCard(state: SortEverythingState, onToggle: (String) -> Unit, onApply: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Categories to check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "These look to be in the wrong place from the shop's own name — Tesco PFS is " +
                    "fuel, not the weekly shop. Untick any that are right as they are.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.fixes.forEach { fix ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(fix.key) },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Checkbox(checked = fix.key in state.ticked, onCheckedChange = { onToggle(fix.key) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fix.payee, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "${fix.fromCategory} → ${fix.toCategory} · ${fix.ids.size} " +
                                (if (fix.ids.size == 1) "payment" else "payments") + " · " +
                                com.rhys.financetracker.core.money.Money.format(fix.totalMinor),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                val count = state.fixes.count { it.key in state.ticked }
                Text(if (count == state.fixes.size) "Move all $count" else "Move $count, leave the rest")
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
