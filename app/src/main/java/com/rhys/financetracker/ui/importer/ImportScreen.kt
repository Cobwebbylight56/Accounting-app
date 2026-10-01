package com.rhys.financetracker.ui.importer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import com.rhys.financetracker.core.time.DateUtils
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import com.rhys.financetracker.data.importer.StatementCheck
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.data.local.entity.PersonEntity
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.data.local.projection.labelFor
import com.rhys.financetracker.domain.model.Holding
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.EmptyState
import com.rhys.financetracker.ui.components.ErrorBanner
import com.rhys.financetracker.ui.components.LabelledTextField
import com.rhys.financetracker.ui.components.SectionCard
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlinx.coroutines.launch

/**
 * The spreadsheet import, in three steps: choose the file, say what the
 * columns mean, then check what will be created.
 *
 * The preview is the important part.  Importing a hand-made spreadsheet is
 * guesswork however clever the matching is, so the app shows exactly what it
 * intends to create and lets the user untick anything wrong before a single row
 * is written.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onFinished: () -> Unit,
    preselectedAccountId: Long? = null,
    /** The person whose page the import was started from. */
    expectedPersonId: Long? = null,
    incomingFile: Uri? = null,
    onIncomingFileHandled: () -> Unit = {},
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Arrived from an account, so that account is the answer and the picker
    // has nothing left to ask.
    LaunchedEffect(preselectedAccountId) {
        viewModel.preselectAccount(preselectedAccountId)
    }

    // Arrived from a person's page: the name on the statement is checked
    // against theirs.
    LaunchedEffect(expectedPersonId) {
        viewModel.expectPerson(expectedPersonId)
    }

    // Opened from a download or the share sheet: read it without making the
    // user find the same file again through the picker.
    LaunchedEffect(incomingFile) {
        incomingFile?.let {
            viewModel.openFile(it)
            onIncomingFileHandled()
        }
    }

    // On the review page the phone's back button does what the arrow does —
    // back to the columns — rather than throwing the whole import away.
    BackHandler(
        enabled = state.step == ImportStep.REVIEW && state.whatWasRead == null,
        onBack = viewModel::goToMapping,
    )

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::openFile) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.whatWasRead != null) {
                                viewModel.closeWhatWasRead()
                            } else if (state.step == ImportStep.REVIEW) {
                                viewModel.goToMapping()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            state.error?.let {
                ErrorBanner(
                    message = it,
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.padding(16.dp),
                )
            }

            // On the review page it scrolls with everything else instead.
            state.unreadablePdfText?.takeIf { state.step != ImportStep.REVIEW }?.let { text ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    UnreadablePdfCard(
                        text = text,
                        onDismiss = viewModel::clearUnreadablePdf,
                        onShowAll = viewModel::showWhatWasRead,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Shown in place of the step, inside this page, so it sits
            // between the top bar and the phone's own buttons like any page.
            val whatWasRead = state.whatWasRead
            if (whatWasRead != null) {
                BackHandler(onBack = viewModel::closeWhatWasRead)
                WhatWasReadPage(text = whatWasRead, onClose = viewModel::closeWhatWasRead)
            } else when (state.step) {
                ImportStep.CHOOSE_FILE -> ChooseFileStep(
                    onChoose = {
                        pickFile.launch(
                            arrayOf(
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "text/csv",
                                "text/comma-separated-values",
                                "text/plain",
                                "application/*",
                            ),
                        )
                    },
                )

                ImportStep.MAP -> MappingStep(state = state, viewModel = viewModel)

                ImportStep.REVIEW -> ReviewStep(state = state, viewModel = viewModel)

                ImportStep.DONE -> DoneStep(
                    state = state,
                    onImportAnother = viewModel::reset,
                    onFinish = onFinished,
                    onToggleBill = viewModel::toggleBill,
                    onAddBills = viewModel::addChosenBills,
                )
            }
        }
    }
}

/**
 * What the PDF actually contained, when its layout was not recognised.
 *
 * Every bank lays a statement out differently and this app cannot have met
 * them all. Showing the text turns a dead end into something that can be
 * fixed: copy it, send it on, and the reader can be taught this layout.
 */
@Composable
private fun UnreadablePdfCard(text: String, onDismiss: () -> Unit, onShowAll: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val preview = remember(text) {
        text.split("\n").filter { it.isNotBlank() }.take(PREVIEW_LINES)
    }

    SectionCard(
        title = "What was read from the PDF",
        subtitle = "The lines the app got out of your statement",
    ) {
        Text(
            text = "Copy this and send it on, and the app can be taught your bank's " +
                "layout. Blank out anything you would rather not share — the shape " +
                "of the lines is what matters, not the figures.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                preview.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Copy all text")
            }
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("Dismiss")
            }
        }
        TextButton(onClick = onShowAll) { Text("See every line") }
    }
}

/**
 * Every line read from the file, full screen, numbered so a missing row is
 * easy to point at.
 *
 * It takes the place of the page rather than being a card in the list, so
 * it opens where the user is looking instead of at the top of a long
 * review page, and its buttons stay clear of the phone's own.
 */
@Composable
private fun WhatWasReadPage(text: String, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val lines = remember(text) { text.split("\n").map { it.trimEnd() }.filter { it.isNotBlank() } }
    var copied by remember(text) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("What was read", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "${lines.size} lines — every one the app got out of the file. " +
                "If a payment is missing here, the file itself did not contain it as text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // At the top, not the bottom: at the bottom they could end up under
        // the phone's own buttons, where they cannot be pressed.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                },
                modifier = Modifier.weight(1f),
            ) { Text(if (copied) "Copied" else "Copy all text") }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(12.dp),
                )
                .padding(8.dp),
            // Room below the last line, so it can be scrolled clear of the
            // phone's own buttons.
            contentPadding = PaddingValues(bottom = 64.dp),
        ) {
            items(lines.size) { index ->
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = "${index + 1}".padStart(4),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = lines[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** Enough lines to show the shape of a statement without filling the screen. */
private const val PREVIEW_LINES = 25

/** Rows of the sheet shown before importing. Enough to recognise the file. */
private const val PREVIEW_ROWS = 12

@Composable
private fun ChooseFileStep(onChoose: () -> Unit) {
    Column {
        EmptyState(
            icon = Icons.Outlined.UploadFile,
            title = "Bank statement or spreadsheet",
            message = "Choose a statement downloaded from your bank — PDF or CSV — or an " +
                ".xlsx budget. Nothing is changed until you have seen exactly what will " +
                "be created.",
            actionLabel = "Choose a file",
            onAction = onChoose,
        )
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = "Tips",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "• PDF statements work, and are usually the only kind a banking " +
                    "app offers. A CSV export, if your bank has one, reads more " +
                    "reliably — look on the website rather than the app.\n" +
                    "• Figures read from a PDF are checked against the running balance, " +
                    "and anything that does not add up is flagged for you to look at.\n" +
                    "• Import old statements too, in any order — that is how you build " +
                    "up a spending history.\n" +
                    "• Overlapping statements are safe. Rows already added are skipped, " +
                    "and the summary says how many.\n" +
                    "• Older .xls files need saving as .xlsx or .csv first.\n" +
                    "• If a budget sheet has a column of figures for each person, the app " +
                    "will spot that and offer to import the whole thing in one tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The banner that turns a whole household sheet into one tap.
 *
 * It appears only when the layout was recognised, and it always says exactly
 * what it found, so the user can tell whether the guess is right before
 * committing to it.
 */
/**
 * Offered when the file is a downloaded bank statement.
 *
 * The account has to be chosen rather than guessed: a statement file rarely
 * names the account in a form the app would recognise, and filing rows against
 * the wrong account would put the duplicate check on the wrong history.
 */
@Composable
private fun DetectedStatementCard(state: ImportState, viewModel: ImportViewModel) {
    if (!state.canImportStatement) return
    val allAccounts by viewModel.accounts.collectAsStateWithLifecycle()
    val found = state.statementKind
    var showEveryAccount by rememberSaveable { mutableStateOf(false) }
    val people by viewModel.people.collectAsStateWithLifecycle()
    var pickingPerson by rememberSaveable { mutableStateOf(false) }
    // Whose accounts are offered: the person the statement is for, as best
    // it is known. Only theirs — making sure it lands on the right person is
    // the point — and only of the statement's own kind, which is what stops
    // a saver's statement being filed against a current account.
    val whom = state.filingFor
    val ofThisKind = allAccounts.filter { found == null || it.holding == found.kind.holding }
    val theirsOfKind = ofThisKind.filter { whom == null || it.personName == whom }
    val accounts = when {
        showEveryAccount -> allAccounts
        else -> theirsOfKind
    }
    val preselected = allAccounts.firstOrNull { it.id == state.preselectedAccountId }
    var chosen by remember(accounts, preselected) {
        mutableStateOf(preselected?.takeIf { it in accounts } ?: accounts.firstOrNull())
    }

    SectionCard(
        title = "This looks like a bank statement",
        subtitle = "Import it as transactions",
    ) {
        Text(
            text = "Dates, descriptions and amounts were found. Rows already in " +
                "the app are skipped, so importing overlapping statements is safe.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))
        WhoseStatement(
            state = state,
            people = people,
            pickingPerson = pickingPerson,
            onPickPerson = { pickingPerson = !pickingPerson },
            onFileUnder = { name ->
                viewModel.fileUnder(name)
                pickingPerson = false
            },
            onNewPerson = viewModel::addPersonFromStatement,
        )
        found?.let { kind ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "It's a ${kind.kind.displayName} statement" +
                    (kind.productName?.let { " ($it)" } ?: "") +
                    ", so only ${kind.kind.displayName}s are offered below.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // Nothing of the right kind under this person: offer to make exactly
        // that, under them, rather than letting it be filed against whatever
        // account happens to exist.
        if (theirsOfKind.isEmpty() && !showEveryAccount && !state.ownerConflict && !state.ownerUnknown) {
            Spacer(Modifier.height(8.dp))
            val whose = whom?.let { "$it's " }.orEmpty()
            val kindName = found?.kind?.displayName ?: "account"
            Text(
                text = "There's no ${whose}$kindName in the app yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(
                onClick = viewModel::createAccountForStatement,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Add " + (
                        found?.productName ?: found?.kind?.accountType?.displayName
                            ?: "a current account"
                        ) + (whom?.let { " for $it" } ?: ""),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        DropdownField(
            label = if (preselected != null) "Adding to" else "Add these to",
            options = accounts,
            selected = chosen,
            onSelect = { chosen = it },
            optionLabel = { accounts.labelFor(it) },
            optionColor = { colorFromHex(it.colorHex) },
            placeholder = if (accounts.isEmpty()) "No accounts of this kind" else "Choose an account",
        )
        if (found != null || whom != null) {
            TextButton(onClick = { showEveryAccount = !showEveryAccount }) {
                Text(if (showEveryAccount) "Only matching accounts" else "Show every account")
            }
        }
        chosen?.takeIf { it.holding == Holding.SET_ASIDE }?.let {
            Text(
                text = "This account is set aside, so money in and out of it is filed as " +
                    "savings (interest apart) — not as income or spending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.useDetectedStatement(chosen) },
            // Not while it is still unsettled whose statement this is.
            enabled = chosen != null && !state.isBusy && !state.ownerConflict,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Read the statement")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Spending is sorted into categories automatically, and you can " +
                "correct anything before it is saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whose statement this is, and what to do if it is not who it was expected
 * to be.
 *
 * Four cases: the name on it matches (said, with a tick); it names somebody
 * else in the app (offer to file it under them, or keep it here); it names
 * nobody the app knows (start a new person, or say whose it is — which
 * teaches the app that name); or no name could be read (say whose it is
 * going under, with a way to change it).
 */
@Composable
private fun WhoseStatement(
    state: ImportState,
    people: List<PersonEntity>,
    pickingPerson: Boolean,
    onPickPerson: () -> Unit,
    onFileUnder: (String) -> Unit,
    onNewPerson: () -> Unit,
) {
    val owner = state.statementOwnerName
    val expected = state.expectedPersonName
    when {
        state.ownerConflict && owner != null && expected != null -> {
            Text(
                text = "This statement is addressed to $owner, not $expected.",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = { onFileUnder(owner) }, modifier = Modifier.fillMaxWidth()) {
                Text("Add it to $owner's accounts")
            }
            OutlinedButton(onClick = { onFileUnder(expected) }, modifier = Modifier.fillMaxWidth()) {
                Text("It's $expected's — keep it here")
            }
        }

        state.ownerUnknown -> {
            val printed = state.unknownOwnerName.orEmpty()
            Text(
                text = "This statement is addressed to $printed, who isn't in the app yet.",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Button(onClick = onNewPerson, modifier = Modifier.fillMaxWidth()) {
                Text("Start a new person for them")
            }
            Text(
                text = "Or, if it's someone already here under another name, pick them — " +
                    "the app will recognise \"$printed\" as them from now on:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PersonChips(people, expected, onFileUnder)
        }

        else -> {
            val whom = state.filingFor
            Text(
                text = when {
                    whom == null -> "No name could be read from this statement. Whose is it?"
                    owner == whom && state.filingPersonName == null ->
                        "✓ The name on the statement matches $whom."
                    owner == null && state.filingPersonName == null ->
                        "Adding to $whom's accounts. No name could be read from the " +
                            "statement to check."
                    else -> "Adding to $whom's accounts."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (owner == whom && whom != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (whom == null || pickingPerson) {
                PersonChips(people, expected, onFileUnder)
            } else {
                TextButton(onClick = onPickPerson) { Text("Someone else's?") }
            }
        }
    }
}

/** A chip per person, for saying whose a statement is. */
@Composable
private fun PersonChips(people: List<PersonEntity>, first: String?, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        people.sortedByDescending { it.name == first }.forEach { person ->
            AssistChip(
                onClick = { onPick(person.name) },
                label = { Text("It's ${person.name}'s") },
                leadingIcon = { ColorDot(colorFromHex(person.colorHex)) },
            )
        }
    }
}

@Composable
private fun DetectedLayoutCard(state: ImportState, viewModel: ImportViewModel) {
    val layout = state.detectedLayout ?: return

    SectionCard(
        title = "This looks like a household budget",
        subtitle = "Everything can be imported in one go",
    ) {
        Text(
            text = layout.describe(),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Each person's column is read separately, and any \"both\" or " +
                "\"total\" column is ignored so nothing is counted twice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = viewModel::useDetectedLayout,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text("Import the whole sheet")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You will see everything it plans to create before anything is saved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MappingStep(state: ImportState, viewModel: ImportViewModel) {
    val sheet = state.sheet ?: return
    val mapping = state.mapping ?: return
    var defaultPerson by remember { mutableStateOf(mapping.defaultPersonName.orEmpty()) }
    var defaultAccount by remember { mutableStateOf(mapping.defaultAccountName.orEmpty()) }
    var defaultCategory by remember { mutableStateOf(mapping.defaultCategoryName.orEmpty()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (state.canImportStatement) {
            item { DetectedStatementCard(state = state, viewModel = viewModel) }
        }
        // Never both. A statement's "Money out" and "Money in" headings are
        // columns of figures with text headings, which is exactly what a
        // household budget's per-person columns look like — so the household
        // reader offers to import a statement as two people called "Money out"
        // and "Money in". Being a statement is the more specific answer and
        // wins outright.
        if (state.canAutoImport && !state.canImportStatement) {
            item { DetectedLayoutCard(state = state, viewModel = viewModel) }
        }
        if (state.canAutoImport || state.canImportStatement) {
            item {
                Text(
                    text = "Or set the columns yourself",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        if ((state.workbook?.sheets?.size ?: 0) > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sheet", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.workbook?.sheets.orEmpty()) { candidate ->
                            val index = state.workbook?.sheets?.indexOf(candidate) ?: 0
                            FilterChip(
                                selected = index == state.selectedSheetIndex,
                                onClick = { viewModel.selectSheet(index) },
                                label = { Text(candidate.name) },
                            )
                        }
                    }
                }
            }
        }

        // A bank statement's rows are always things that happened, so there
        // is nothing to choose; asking only invited every payment to become a
        // repeating bill.
        if (!state.canImportStatement) {
            item {
                DropdownField(
                    label = "What should these rows become?",
                    options = ImportTarget.entries,
                    selected = mapping.target,
                    onSelect = viewModel::setTarget,
                    optionLabel = { it.displayName },
                )
            }
            item {
                Text(
                    text = mapping.target.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SheetPreview(state = state) }

        item {
            SectionCard(
                title = "What each column means",
                subtitle = "The app has had a guess — correct anything it got wrong",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0 until sheet.columnCount).forEach { column ->
                        val header = if (mapping.headerRow >= 0) {
                            sheet.cell(mapping.headerRow, column)
                        } else {
                            ""
                        }
                        val sample = (mapping.firstDataRow..minOf(
                            mapping.firstDataRow + 3,
                            sheet.rowCount - 1,
                        )).mapNotNull { row ->
                            sheet.cell(row, column).takeIf { it.isNotBlank() }
                        }.firstOrNull()

                        DropdownField(
                            label = header.ifBlank { "Column ${column + 1}" } +
                                (sample?.let { "  (e.g. $it)" } ?: ""),
                            options = ColumnRole.entries,
                            selected = mapping.columnRoles[column] ?: ColumnRole.IGNORE,
                            onSelect = { role -> viewModel.setColumnRole(column, role) },
                            optionLabel = { it.displayName },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = "Apply to every row",
                subtitle = "Useful when the whole block belongs to one person or account",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabelledTextField(
                        label = "Person",
                        value = defaultPerson,
                        onValueChange = {
                            defaultPerson = it
                            viewModel.setDefaults(defaultPerson, defaultAccount, defaultCategory)
                        },
                        placeholder = "Rhys",
                        supportingText = "Created if they do not exist yet",
                    )
                    LabelledTextField(
                        label = "Account",
                        value = defaultAccount,
                        onValueChange = {
                            defaultAccount = it
                            viewModel.setDefaults(defaultPerson, defaultAccount, defaultCategory)
                        },
                        placeholder = "Rhys bank",
                    )
                    LabelledTextField(
                        label = "Category",
                        value = defaultCategory,
                        onValueChange = {
                            defaultCategory = it
                            viewModel.setDefaults(defaultPerson, defaultAccount, defaultCategory)
                        },
                        placeholder = "Leave empty to use the column",
                    )
                }
            }
        }

        item {
            SectionCard(title = "Which rows to read") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabelledTextField(
                        label = "Heading row (0 for the first row)",
                        value = mapping.headerRow.toString(),
                        onValueChange = { text ->
                            text.toIntOrNull()?.let(viewModel::setHeaderRow)
                        },
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabelledTextField(
                            label = "First data row",
                            value = mapping.firstDataRow.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let {
                                    viewModel.setRowRange(it, mapping.lastDataRow)
                                }
                            },
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        LabelledTextField(
                            label = "Last data row",
                            value = mapping.lastDataRow.toString(),
                            onValueChange = { text ->
                                text.toIntOrNull()?.let {
                                    viewModel.setRowRange(mapping.firstDataRow, it)
                                }
                            },
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = viewModel::goToReview,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = state.candidates.isNotEmpty(),
            ) {
                Text("Check ${state.candidates.size} rows")
            }
        }
    }
}

/** A scrollable window onto the raw spreadsheet, so the user can see what they are mapping. */
@Composable
private fun SheetPreview(state: ImportState) {
    val sheet = state.sheet ?: return
    val rows = sheet.rows.take(PREVIEW_ROWS)
    val widest = sheet.rows.maxOfOrNull { it.size } ?: 0

    SectionCard(
        title = "Your spreadsheet",
        // A preview that shows part of the file without saying so reads as the
        // whole file, and then a column that is merely off to the right looks
        // like one the app failed to find.
        subtitle = buildString {
            append(sheet.name)
            append(" · ")
            if (sheet.rows.size > rows.size) {
                append("first ${rows.size} of ${sheet.rows.size} rows")
            } else {
                append("all ${sheet.rows.size} rows")
            }
            if (widest > 3) append(", scroll sideways for every column")
        },
    ) {
        Column(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            rows.forEachIndexed { rowIndex, cells ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = rowIndex.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    cells.forEach { cell ->
                        Surface(
                            color = if (rowIndex == state.mapping?.headerRow) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                text = cell.ifBlank { " " },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(96.dp)
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Says when the rows look like they belong to a different account.
 *
 * Placed above the list rather than in a dialog: it is a judgement about the
 * whole file, and the rows underneath are the evidence for or against it.
 */
@Composable
private fun WrongAccountWarning(state: ImportState, viewModel: ImportViewModel) {
    val verdict = state.accountFit ?: return
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val suggested = accounts.firstOrNull { it.id == verdict.suggestedAccountId } ?: return
    val chosen = state.chosenAccount

    Surface(
        color = FinanceTheme.colors.warningContainer,
        contentColor = FinanceTheme.colors.onWarningContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "This looks like ${accounts.labelFor(suggested)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(verdict.recognisedThere)
                    append(" of these payees already appear on ")
                    append(accounts.labelFor(suggested))
                    append(", and ")
                    append(if (verdict.recognisedHere == 0) "none" else "only ${verdict.recognisedHere}")
                    append(" on ")
                    append(chosen?.let { accounts.labelFor(it) } ?: "this account")
                    append(". If that is the right account, carry on — a new card ")
                    append("or a first statement will look like this too.")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::chooseAnotherAccount) { Text("Change account") }
                TextButton(onClick = viewModel::keepChosenAccount) { Text("Keep it") }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ReviewStep(state: ImportState, viewModel: ImportViewModel) {
    val targets by viewModel.ownTargets.collectAsStateWithLifecycle()
    var choosingFor by remember { mutableStateOf<ImportCandidate?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Everything scrolls together — the check, the counts and every row —
        // so the whole import can be read, not only what fits above the list.
        LazyColumn(modifier = Modifier.weight(1f)) {
            state.unreadablePdfText?.let { text ->
                item {
                    Box(modifier = Modifier.padding(16.dp)) {
                        UnreadablePdfCard(
                            text = text,
                            onDismiss = viewModel::clearUnreadablePdf,
                            onShowAll = viewModel::showWhatWasRead,
                        )
                    }
                }
            }
            item { WrongAccountWarning(state = state, viewModel = viewModel) }
            if (state.canImportStatement) {
                item {
                    StatementCheckCard(
                        check = state.check,
                        candidates = state.candidates,
                        onSwap = viewModel::toggleDirection,
                        onShowWhatWasRead = viewModel::showWhatWasRead,
                    )
                }
            }
            item { ReviewSummary(state = state, viewModel = viewModel) }
            items(state.candidates, key = { it.id }) { candidate ->
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    CandidateRow(
                        candidate = candidate,
                        onToggle = { viewModel.toggleCandidate(candidate.id) },
                        onFlip = { viewModel.toggleDirection(candidate.id) },
                        onChooseDestination = if (targets.isNotEmpty()) {
                            { choosingFor = candidate }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::mapByHand,
                modifier = Modifier.weight(1f),
            ) { Text("Change") }
            Button(
                onClick = viewModel::applyImport,
                modifier = Modifier.weight(1f),
                enabled = state.selectedCount > 0 && !state.isBusy,
            ) { Text("Import") }
        }
    }

    choosingFor?.let { candidate ->
        val out = candidate.transactionType != TransactionType.INCOME
        AlertDialog(
            onDismissRequest = { choosingFor = null },
            title = { Text(if (out) "Where did this go?" else "Where did this come from?") },
            text = {
                Column {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    DestinationOption(
                        label = if (out) "Spending — it's gone" else "Income — money earned",
                        selected = candidate.transferAccountId == null,
                        onClick = {
                            viewModel.setDestination(candidate.id, null)
                            choosingFor = null
                        },
                    )
                    targets.forEach { target ->
                        DestinationOption(
                            label = (if (out) "Into " else "From ") + target.name +
                                " · " + target.type.displayName,
                            selected = candidate.transferAccountId == target.id,
                            onClick = {
                                viewModel.setDestination(candidate.id, target)
                                choosingFor = null
                            },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "The app remembers this payee for next time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { choosingFor = null }) { Text("Close") } },
        )
    }
}

/** The counts above the rows: what will be added, skipped, corrected and moved. */
@Composable
private fun ReviewSummary(state: ImportState, viewModel: ImportViewModel) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Counted honestly: a corrected row is not an added one, and a
                // row already held is neither, so a plain "will be added" over
                // the whole selection promises more than the import does.
                Text(
                    text = "${state.additionCount} of ${state.candidates.size} rows will be added",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.usingDetectedLayout) {
                    Text(
                        text = "Read straight from your sheet's layout",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.alreadyPresentCount > 0) {
                    Text(
                        text = "${state.alreadyPresentCount} are already recorded and " +
                            "have been left unticked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.correctionCount > 0) {
                    Text(
                        text = "${state.correctionCount} will update an entry you " +
                            "already had rather than add a second copy",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.moveCount > 0) {
                    Text(
                        text = "${state.moveCount} move money to or from your own accounts — " +
                            "savers go up, loans come down",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.problemCount > 0) {
                    Text(
                        text = "${state.problemCount} cannot be read and have been left out",
                        style = MaterialTheme.typography.bodySmall,
                        color = FinanceTheme.colors.warning,
                    )
                }
            }
            TextButton(onClick = { viewModel.selectAll(true) }) { Text("All") }
            TextButton(onClick = { viewModel.selectAll(false) }) { Text("None") }
        }

        // Above the list rather than inside it: reading a whole statement the
        // wrong way round is the mistake worth catching, and it is invisible
        // one row at a time.
        if (state.moneyOutCount + state.moneyInCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Reading ${state.moneyOutCount} as money out and " +
                        "${state.moneyInCount} as money in",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::swapAllDirections) { Text("Swap all") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Tap an amount to change one row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (state.sourceUri != null) {
                    TextButton(onClick = viewModel::showWhatWasRead) { Text("Show what was read") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

    }
}

/**
 * Whether the whole statement was read: the rows counted, the dates they
 * cover, and — where the statement prints a running balance — proof that
 * every row accounts for it, or exactly where it does not.
 */
@Composable
private fun StatementCheckCard(
    check: StatementCheck,
    candidates: List<ImportCandidate>,
    onSwap: (String) -> Unit,
    onShowWhatWasRead: () -> Unit,
) {
    val colors = FinanceTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(
                if (check.canBeChecked && !check.isProvenComplete) {
                    colors.warningContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = "Read ${check.rows} rows" +
                (
                    if (check.firstDate != null && check.lastDate != null) {
                        " from ${DateUtils.formatShort(check.firstDate)} to " +
                            DateUtils.formatShort(check.lastDate)
                    } else {
                        ""
                    }
                    ) +
                ": ${Money.format(check.moneyInMinor)} in, ${Money.format(check.moneyOutMinor)} out.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        when {
            check.isProvenComplete -> Text(
                text = "✓ Every row adds up to the statement's own balance: " +
                    "${Money.format(check.startBalanceMinor ?: 0L)} at the start → " +
                    "${Money.format(check.endBalanceMinor ?: 0L)} at the end. Nothing is missing.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.positive,
                fontWeight = FontWeight.SemiBold,
            )
            check.canBeChecked -> {
                check.gaps.forEach { gap ->
                    val between = "between " +
                        (gap.after?.let { DateUtils.formatShort(it) } ?: "the start") + " and " +
                        (gap.before?.let { DateUtils.formatShort(it) } ?: "the end")
                    val suspect = gap.suspectIds.singleOrNull()
                        ?.let { id -> candidates.firstOrNull { it.id == id } }
                    Text(
                        text = "⚠ ${Money.format(kotlin.math.abs(gap.unaccountedMinor))} isn't " +
                            "accounted for $between. " +
                            if (suspect != null) {
                                "It adds up if \"${suspect.name}\" " +
                                    "(${Money.format(suspect.amountMinor)}) is the other way round."
                            } else {
                                "A row there wasn't read — check the statement for a payment " +
                                    "of ${Money.format(kotlin.math.abs(gap.unaccountedMinor))} " +
                                    "(or a few adding up to it)."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onWarningContainer,
                    )
                    if (suspect != null) {
                        TextButton(onClick = { onSwap(suspect.id) }) { Text("Swap it") }
                    }
                }
                TextButton(onClick = onShowWhatWasRead) { Text("Show what was read") }
            }
            else -> Text(
                text = "This statement doesn't print a running balance on its rows, so it " +
                    "can't be checked against one. Compare the totals above with the " +
                    "statement's own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DestinationOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CandidateRow(
    candidate: ImportCandidate,
    onToggle: () -> Unit,
    onFlip: () -> Unit,
    onChooseDestination: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = candidate.isSelected,
            onCheckedChange = { onToggle() },
            enabled = candidate.isImportable,
        )
        val canChoose = onChooseDestination != null && candidate.isImportable &&
            candidate.target == ImportTarget.TRANSACTION && !candidate.isAlreadyPresent
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (canChoose) Modifier.clickable { onChooseDestination?.invoke() } else Modifier),
        ) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Where the money went, when it went to one of their own accounts
            // — the line that says the saver will go up or the loan come down.
            candidate.transferAccountName?.let { other ->
                val out = candidate.transactionType != TransactionType.INCOME
                Text(
                    text = if (out) "→ into $other" else "← from $other",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            candidate.alreadyNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = candidate.problem
                    // A correction changes an entry that is already there, so
                    // it says which one. "Updated 3 entries" after the fact is
                    // no help at all when one of them was the wrong entry.
                    ?: candidate.corrects?.let {
                        "Updates \"${it.existingDescription}\" from ${it.existingDateIso}"
                    }
                    ?: listOfNotNull(
                        candidate.dateIso?.let(DateUtils::parseIsoOrNull)?.let(DateUtils::formatShort),
                        candidate.categoryName ?: "Not sorted yet",
                        candidate.accountName,
                        candidate.balanceMinor?.let { "balance after ${Money.format(it)}" },
                    ).joinToString(" · ").ifBlank { "Row ${candidate.sourceRow + 1}" },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    !candidate.isImportable -> FinanceTheme.colors.warning
                    candidate.corrects != null -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Tappable, because the app cannot always tell which way the money went
        // and being confidently wrong about it is worse than asking. The sign
        // and the colour make the reading legible at a glance down the list,
        // which is what catches a whole file read the wrong way round.
        val isIncome = candidate.transactionType == TransactionType.INCOME
        TextButton(
            onClick = onFlip,
            enabled = candidate.isImportable && candidate.target == ImportTarget.TRANSACTION,
        ) {
            Text(
                text = (if (isIncome) "+" else "−") + Money.format(candidate.amountMinor),
                style = MaterialTheme.typography.bodyLarge,
                color = if (isIncome) {
                    FinanceTheme.colors.positive
                } else {
                    FinanceTheme.colors.negative
                },
            )
        }
    }
}

@Composable
private fun DoneStep(
    state: ImportState,
    onImportAnother: () -> Unit,
    onFinish: () -> Unit,
    onToggleBill: (String) -> Unit = {},
    onAddBills: () -> Unit = {},
) {
    val outcome = state.outcome

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Import finished", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = outcome?.summary() ?: "Nothing was added",
            style = MaterialTheme.typography.bodyLarge,
        )

        // The account's balance, brought up to the statement's own figure.
        state.balanceNote?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        if (state.foundBills.isNotEmpty()) {
            SectionCard(
                title = "Regular payments found",
                subtitle = "Tick the ones that are bills",
            ) {
                state.foundBills.forEach { bill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleBill(bill.name) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = bill.name in state.chosenBills,
                            onCheckedChange = { onToggleBill(bill.name) },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bill.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${bill.frequency.displayName}" +
                                    (if (bill.isVariable) ", about " else ", ") +
                                    Money.format(bill.amountMinor) + " · " + bill.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onAddBills,
                    enabled = state.chosenBills.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add ${state.chosenBills.size} as bills") }
                Text(
                    text = "They go on Bills with their next date. When the next statement " +
                        "arrives it updates them rather than adding them twice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        state.billsNote?.let { note ->
            Text(note, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }

        outcome?.let {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (it.peopleCreated > 0) Text("${it.peopleCreated} people added")
                if (it.accountsCreated > 0) Text("${it.accountsCreated} accounts added")
                if (it.categoriesCreated > 0) Text("${it.categoriesCreated} categories added")
                if (it.recurringCreated > 0) {
                    Text("${it.recurringCreated} regular payments added")
                }
                if (it.transactionsCreated > 0) {
                    Text("${it.transactionsCreated} transactions added")
                }
                if (it.transactionsUpdated > 0) {
                    Text("${it.transactionsUpdated} entries updated from the statement")
                }
            }
            if (it.transactionsUpdated > 0) {
                Text(
                    text = "Those were already recorded by hand or from a spreadsheet. " +
                        "The bank's date and payee replaced what was there, and what " +
                        "it used to say was kept in the entry's notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (it.skipped > 0) {
                // "14 skipped" on its own reads like something went wrong
                // without saying what, and the rows are already gone from view
                // by this point.
                Text(
                    text = "${it.skipped} rows were left unticked on the review screen. " +
                        "That is usually rows the balance check could not confirm — " +
                        "import the statement again and tick any you want to keep.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (it.problems.isNotEmpty()) {
                SectionCard(title = "Rows that were skipped") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        it.problems.take(10).forEach { problem ->
                            Text(problem, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Done")
        }
        OutlinedButton(onClick = onImportAnother, modifier = Modifier.fillMaxWidth()) {
            Text("Import another block")
        }
    }
}
