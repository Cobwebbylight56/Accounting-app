package com.rhys.financetracker.ui.claude

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.repository.ProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AskClaudeState(
    val soFar: Boolean = true,
    val text: String? = null,
    val error: String? = null,
)

@HiltViewModel
class AskClaudeViewModel @Inject constructor(
    private val progress: ProgressRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AskClaudeState())
    val state: StateFlow<AskClaudeState> = _state.asStateFlow()

    fun show(soFar: Boolean) {
        _state.value = AskClaudeState(soFar = soFar)
        viewModelScope.launch {
            _state.value = runCatching { progress.claudeSummary(soFar) }.fold(
                onSuccess = { AskClaudeState(soFar = soFar, text = it) },
                onFailure = { AskClaudeState(soFar = soFar, error = "Couldn't put the summary together: ${it.message ?: "unknown error"}") },
            )
        }
    }
}

/**
 * Shares a month's summary with the Claude app, to ask what it makes of it.
 * What's shared is shown in full first; nothing leaves the phone until
 * Share is tapped, and then only to the app chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskClaudeScreen(onBack: () -> Unit, viewModel: AskClaudeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (state.text == null && state.error == null) viewModel.show(state.soFar) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask Claude") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
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
                "Share a summary of the month with the Claude app and ask what it makes of it — what stands " +
                    "out, where you could cut back. You'll see exactly what's shared below, and can change it " +
                    "in Claude before sending.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.soFar, onClick = { viewModel.show(true) }, label = { Text("This month so far") })
                FilterChip(selected = !state.soFar, onClick = { viewModel.show(false) }, label = { Text("Last month") })
            }

            val text = state.text
            when {
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                text == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        SelectionContainer {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    Button(onClick = { shareWithClaude(context, text) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Share with Claude")
                    }
                    OutlinedButton(onClick = {
                        copy(context, text)
                        copied = true
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (copied) "Copied — paste it into Claude" else "Copy instead")
                    }
                }
            }

            Text(
                "Only totals, categories and who payments went to are shared — never an account number, sort " +
                    "code or card number. Claude's own finance feature that links straight to your bank is US-only " +
                    "for now; this works anywhere, with no link to your bank.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Straight to the Claude app when it's there; otherwise the share sheet, to choose. */
private fun shareWithClaude(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    try {
        context.startActivity(Intent(send).setPackage(CLAUDE_APP))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent.createChooser(send, "Ask Claude"))
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Money summary", text))
}

/** The Claude app on Android. */
private const val CLAUDE_APP = "com.anthropic.claude"
