package com.rhys.financetracker.ui.importer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportTarget
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
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                            if (state.step == ImportStep.REVIEW) {
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

            when (state.step) {
                ImportStep.CHOOSE_FILE -> ChooseFileStep(
                    onChoose = {
                        pickFile.launch(
                            arrayOf(
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
                )
            }
        }
    }
}

@Composable
private fun ChooseFileStep(onChoose: () -> Unit) {
    Column {
        EmptyState(
            icon = Icons.Outlined.UploadFile,
            title = "Bank statement or spreadsheet",
            message = "Choose a .csv statement downloaded from your bank, or an .xlsx or " +
                ".csv budget. Nothing is changed until you have seen exactly what will " +
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
                text = "• Bank statements: download one as CSV from online banking. The " +
                    "layout is recognised, so there is nothing to set up.\n" +
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
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    var chosen by remember(accounts) { mutableStateOf(accounts.firstOrNull()) }

    SectionCard(
        title = "This looks like a bank statement",
        subtitle = "Import it as transactions",
    ) {
        Text(
            text = "Dates, descriptions and amounts were found. Rows already in " +
                "the app are skipped, so importing overlapping statements is safe.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        DropdownField(
            label = "Add these to",
            options = accounts,
            selected = chosen,
            onSelect = { chosen = it },
            optionLabel = { it.name },
            optionColor = { colorFromHex(it.colorHex) },
            placeholder = if (accounts.isEmpty()) "No accounts yet" else "Choose an account",
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { viewModel.useDetectedStatement(chosen?.name) },
            enabled = chosen != null && !state.isBusy,
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
        if (state.canAutoImport) {
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
    val rows = sheet.rows.take(8)

    SectionCard(title = "Your spreadsheet", subtitle = sheet.name) {
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
                    cells.take(10).forEach { cell ->
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

@Composable
private fun ReviewStep(state: ImportState, viewModel: ImportViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${state.selectedCount} of ${state.candidates.size} rows will be added",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (state.usingDetectedLayout) {
                    Text(
                        text = "Read straight from your sheet's layout",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(state.candidates, key = { it.id }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    onToggle = { viewModel.toggleCandidate(candidate.id) },
                )
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
}

@Composable
private fun CandidateRow(candidate: ImportCandidate, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = candidate.isSelected,
            onCheckedChange = { onToggle() },
            enabled = candidate.isImportable,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.problem ?: listOfNotNull(
                    candidate.categoryName,
                    candidate.personName,
                    candidate.accountName,
                    candidate.dateIso,
                ).joinToString(" · ").ifBlank { "Row ${candidate.sourceRow + 1}" },
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.isImportable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    FinanceTheme.colors.warning
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = Money.format(candidate.amountMinor),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun DoneStep(
    state: ImportState,
    onImportAnother: () -> Unit,
    onFinish: () -> Unit,
) {
    val outcome = state.outcome

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Import finished", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = outcome?.summary() ?: "Nothing was added",
            style = MaterialTheme.typography.bodyLarge,
        )

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
