package com.rhys.financetracker.ui.claude

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.data.claude.ChatTurn
import com.rhys.financetracker.data.claude.ClaudeChat
import com.rhys.financetracker.data.claude.ClaudeSettings
import com.rhys.financetracker.data.claude.ClaudeSetup
import com.rhys.financetracker.data.repository.ProgressRepository
import com.rhys.financetracker.domain.claude.ClaudeModel
import com.rhys.financetracker.domain.claude.ClaudeText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AskClaudeViewModel @Inject constructor(
    private val chat: ClaudeChat,
    private val settings: ClaudeSettings,
    private val progress: ProgressRepository,
) : ViewModel() {

    val setup: StateFlow<ClaudeSetup> = settings.setup
    val turns: StateFlow<List<ChatTurn>> = chat.turns
    val working: StateFlow<String?> = chat.working

    /** A month's summary waiting to be shared, once it's been put together. */
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    init {
        viewModelScope.launch { settings.load() }
    }

    fun ask(question: String) {
        viewModelScope.launch { chat.ask(question) }
    }

    fun newChat() {
        viewModelScope.launch { chat.newChat() }
    }

    fun saveKey(key: String) {
        viewModelScope.launch { settings.saveKey(key) }
    }

    fun removeKey() {
        viewModelScope.launch {
            settings.removeKey()
            chat.newChat()
        }
    }

    fun useModel(model: ClaudeModel) {
        viewModelScope.launch {
            if (settings.setup.value.model != model) {
                settings.setModel(model)
                chat.newChat()
            }
        }
    }

    fun makeSummary(soFar: Boolean) {
        viewModelScope.launch {
            _summary.value = runCatching { progress.claudeSummary(soFar) }.getOrElse { "Couldn't put the summary together: ${it.message}" }
        }
    }

    fun closeSummary() {
        _summary.value = null
    }
}

/**
 * Ask Claude: questions about the household's money, answered by Claude
 * looking through the app's own figures — like Claude's personal finance
 * feature, inside the app. Uses the user's own Anthropic API key; without
 * one, it explains how to get one, and a month's summary can still be
 * shared with the Claude app instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskClaudeScreen(onBack: () -> Unit, viewModel: AskClaudeViewModel = hiltViewModel()) {
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask Claude") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (setup.hasKey && turns.isNotEmpty()) {
                        IconButton(onClick = viewModel::newChat, enabled = working == null) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "New conversation")
                        }
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ask Claude settings")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            when {
                !setup.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
                !setup.hasKey -> SetUp(onSave = viewModel::saveKey, onShareInstead = { settingsOpen = true })
                else -> Conversation(turns, working, setup.model, onAsk = viewModel::ask)
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { settingsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SettingsSheet(
                setup = setup,
                onModel = viewModel::useModel,
                onRemoveKey = viewModel::removeKey,
                onShare = { soFar ->
                    settingsOpen = false
                    viewModel.makeSummary(soFar)
                },
            )
        }
    }

    summary?.let { text -> SummaryDialog(text, onClose = viewModel::closeSummary) }
}

/** First time: what this is, what it costs, and where to get a key. */
@Composable
private fun SetUp(onSave: (String) -> Unit, onShareInstead: () -> Unit) {
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("Ask Claude about your money", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Ask anything — \"where did our money go last month?\", \"what subscriptions do we pay for?\", \"how could " +
                "we save £200 a month?\" — and Claude looks through your accounts, payments and bills in the app to answer.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Setting it up", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "It needs your own key for the Claude API. That's separate from a Claude Pro or Max plan: you pay " +
                        "Anthropic only for what you ask, usually a few pence a question.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("1. Sign up at platform.claude.com.", style = MaterialTheme.typography.bodyMedium)
                Text("2. Add some credit under Billing — $5 goes a long way.", style = MaterialTheme.typography.bodyMedium)
                Text("3. Create an API key and paste it below.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { uri.openUri(KEYS_PAGE) }) { Text("Open platform.claude.com") }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Your API key") },
            placeholder = { Text("sk-ant-…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = {
                if (key.isNotEmpty() && !key.startsWith("sk-ant-")) Text("Anthropic keys usually start with sk-ant-")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(key) }, enabled = key.length >= MIN_KEY_LENGTH, modifier = Modifier.fillMaxWidth()) {
            Text("Save and start")
        }
        PrivacyNote()
        OutlinedButton(onClick = onShareInstead, modifier = Modifier.fillMaxWidth()) {
            Text("Or share a summary with the Claude app instead")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(turns: List<ChatTurn>, working: String?, model: ClaudeModel, onAsk: (String) -> Unit) {
    var question by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(turns.size, working) {
        val last = turns.size + (if (working != null) 1 else 0)
        if (last > 0) list.animateScrollToItem(last - 1)
    }
    val send = {
        if (question.isNotBlank() && working == null) {
            onAsk(question)
            question = ""
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (turns.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What would you like to know?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Claude looks through your accounts, payments and bills to answer. Using ${model.label}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUGGESTIONS.forEach { idea ->
                                SuggestionChip(onClick = { onAsk(idea) }, label = { Text(idea) }, enabled = working == null)
                            }
                        }
                    }
                }
            }
            items(turns, key = { it.id }) { turn -> ChatBubble(turn) }
            if (working != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(working, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Ask about your money…") },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                shape = RoundedCornerShape(24.dp),
            )
            IconButton(onClick = send, enabled = question.isNotBlank() && working == null) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask")
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    val mine = turn.fromYou
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = when {
                turn.isError -> MaterialTheme.colorScheme.errorContainer
                mine -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(if (mine) 0.85f else 1f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (mine) {
                    Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectionContainer { Reply(turn.text) }
                    turn.costDollars?.let { cost ->
                        Text(
                            "Cost ${ClaudeModel.describe(cost)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Claude's reply, with its bullets and bold. */
@Composable
private fun Reply(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ClaudeText.lines(text).forEach { line ->
            if (line.parts.isEmpty()) {
                Spacer(Modifier.size(6.dp))
            } else {
                Text(annotated(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun annotated(line: ClaudeText.Line): AnnotatedString = buildAnnotatedString {
    if (line.bullet) append("•  ")
    line.parts.forEach { part ->
        if (part.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part.text) } else append(part.text)
    }
}

@Composable
private fun SettingsSheet(
    setup: ClaudeSetup,
    onModel: (ClaudeModel) -> Unit,
    onRemoveKey: () -> Unit,
    onShare: (soFar: Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ask Claude", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (setup.hasKey) {
            Text("Which Claude", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            ClaudeModel.entries.forEach { model ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = model == setup.model, onClick = { onModel(model) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = model == setup.model, onClick = { onModel(model) })
                    Column {
                        Text(model.label, style = MaterialTheme.typography.bodyLarge)
                        Text(model.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Changing it starts a new conversation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("API key ending ${setup.keyEnding}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRemoveKey) { Text("Remove") }
            }
            HorizontalDivider()
        }
        Text("Share a summary instead", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "No key needed: the month in a few lines, shared with the Claude app or anywhere else. You see it first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onShare(true) }) { Text("This month so far") }
            OutlinedButton(onClick = { onShare(false) }) { Text("Last month") }
        }
        PrivacyNote()
    }
}

@Composable
private fun PrivacyNote() {
    Text(
        "What Claude sees: account names and balances, payees, amounts, dates and categories — looked up only when " +
            "you ask, and sent to Anthropic to answer. Never account, sort code or card numbers, and Claude can only " +
            "look, never change anything. Anthropic doesn't train its models on API conversations. Your key is kept " +
            "encrypted on this phone.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The summary in full, then shared only if asked. */
@Composable
private fun SummaryDialog(text: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Share with Claude") },
        text = {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                SelectionContainer {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                shareWithClaude(context, text)
                onClose()
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton(onClick = {
                copy(context, text)
                copied = true
            }) { Text(if (copied) "Copied" else "Copy") }
        },
    )
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

/** Where API keys are made. */
private const val KEYS_PAGE = "https://platform.claude.com/settings/keys"

/** Shorter than this can't be a key. */
private const val MIN_KEY_LENGTH = 20

private val SUGGESTIONS = listOf(
    "Where did our money go last month?",
    "What subscriptions and bills do we pay?",
    "How could we save £200 a month?",
    "How has our spending changed this year?",
    "Are we on track this month?",
)
