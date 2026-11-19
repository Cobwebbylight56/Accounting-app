package com.rhys.financetracker.ui.budget

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.BudgetLineEntity
import com.rhys.financetracker.data.local.entity.CategoryEntity
import com.rhys.financetracker.data.repository.BudgetLineView
import com.rhys.financetracker.data.repository.BudgetOverview
import com.rhys.financetracker.data.repository.DraftLine
import com.rhys.financetracker.domain.budget.BudgetSource
import com.rhys.financetracker.domain.budget.Grade
import com.rhys.financetracker.domain.budget.LineStatus
import com.rhys.financetracker.domain.budget.MonthScore
import com.rhys.financetracker.domain.budget.Suggestion
import com.rhys.financetracker.domain.model.CategoryKind
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.CategoryPicker
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The household budget: how this month is going against it, how earlier
 * months did, and every line — set up from the spreadsheet the app started
 * from, or from what's been spent lately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    /** Null when Budget is a tab, with nothing to go back to. */
    onBack: (() -> Unit)? = null,
    viewModel: BudgetViewModel = hiltViewModel(),
) {
    val month by viewModel.month.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val setup by viewModel.setup.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val splitJoint by viewModel.splitJoint.collectAsStateWithLifecycle()

    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BudgetLineView?>(null) }
    var adding by remember { mutableStateOf(false) }
    /** A person's cell being changed: the line, and which column. */
    var cell by remember { mutableStateOf<Pair<BudgetLineView, Int>?>(null) }

    val pickSheet = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::setUpFromSheet)
    }
    val openSheet = { pickSheet.launch(SPREADSHEET_TYPES) }

    val current = setup
    if (current != null) BackHandler(onBack = viewModel::cancelSetup)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (current == null) "Budget" else "Set up your budget") },
                navigationIcon = {
                    if (current != null || onBack != null) {
                        IconButton(onClick = { if (current != null) viewModel.cancelSetup() else onBack?.invoke() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (current == null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Add a line") }, onClick = { menuOpen = false; adding = true })
                                DropdownMenuItem(text = { Text("Set up from my spreadsheet") }, onClick = { menuOpen = false; openSheet() })
                                DropdownMenuItem(text = { Text("Set up from what we spend") }, onClick = { menuOpen = false; viewModel.setUpFromSpending() })
                                DropdownMenuItem(
                                    text = { Text(if (splitJoint) "Give joint money its own column" else "Share joint money between you") },
                                    onClick = { menuOpen = false; viewModel.setSplitJoint(!splitJoint) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                current != null -> SetupReview(
                    setup = current,
                    categories = categories,
                    accounts = accounts,
                    hasBudget = overview?.lines?.isNotEmpty() == true,
                    onChange = viewModel::changeDraft,
                    onSave = viewModel::saveSetup,
                )
                overview == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                overview!!.lines.isEmpty() -> EmptyBudget(onSheet = openSheet, onSpending = viewModel::setUpFromSpending, onAdd = { adding = true })
                else -> BudgetPage(
                    overview = overview!!,
                    canGoForward = viewModel.canGoForward,
                    onPrevious = viewModel::previousMonth,
                    onNext = viewModel::nextMonth,
                    onMonth = viewModel::showMonth,
                    onLine = { editing = it },
                    onCell = { view, index -> cell = view to index },
                    onAdd = { adding = true },
                    onAccept = viewModel::accept,
                    onDismiss = viewModel::dismiss,
                )
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (editing != null || adding) {
        LineEditor(
            view = editing,
            month = month,
            categories = categories,
            accounts = accounts,
            onDismiss = {
                editing = null
                adding = false
            },
            onSave = { line, thisMonthOnly, clear ->
                viewModel.saveLine(line, thisMonthOnly, clear)
                editing = null
                adding = false
            },
            onDelete = { id ->
                viewModel.deleteLine(id)
                editing = null
            },
        )
    }

    cell?.let { (view, index) ->
        val column = overview?.columns?.getOrNull(index)
        val personId = column?.personId
        if (column == null || personId == null) {
            // The joint column: its part is what's left of the line's total.
            cell = null
            editing = view
        } else {
            ShareDialog(
                person = column.name,
                line = view.line.name,
                current = view.shares[personId] ?: view.cells.getOrNull(index)?.budgetMinor ?: 0L,
                onDismiss = { cell = null },
                onSave = { amount ->
                    viewModel.setPersonShare(view.line.id, personId, amount)
                    cell = null
                },
            )
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text("OK") } },
            text = { Text(text) },
        )
    }
}

@Composable
private fun EmptyBudget(onSheet: () -> Unit, onSpending: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Outlined.Savings, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text("Your household budget", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Set what each thing should cost a month, and see how well you keep to it — month by month, with a score.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From your spreadsheet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Choose the household spreadsheet you started with. Its outgoings — everyone's figures added up — " +
                        "become the budget, each matched to where its money goes. You check them before anything is saved.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onSheet, modifier = Modifier.fillMaxWidth()) { Text("Choose my spreadsheet") }
            }
        }
        OutlinedButton(onClick = onSpending, modifier = Modifier.fillMaxWidth()) { Text("Start from what we spend instead") }
        TextButton(onClick = onAdd) { Text("Or add lines one by one") }
        Text(
            "Bills are limits: keep under them. Fuel and the food shop are aims: going over isn't a big problem, " +
                "so it only costs half marks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BudgetPage(
    overview: BudgetOverview,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMonth: (YearMonth) -> Unit,
    onLine: (BudgetLineView) -> Unit,
    onCell: (BudgetLineView, Int) -> Unit,
    onAdd: () -> Unit,
    onAccept: (Suggestion) -> Unit,
    onDismiss: (Suggestion) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Month before") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(monthName(overview.month), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (overview.inProgress) {
                        Text("So far", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onNext, enabled = canGoForward) { Icon(Icons.Filled.ChevronRight, contentDescription = "Month after") }
            }
        }
        item {
            ScoreCard(
                overview.score,
                overview.streak,
                people = overview.columns.zip(overview.columnScores).filter { it.first.personId != null }
                    .map { (column, score) -> column.name to score },
            )
        }
        item { BudgetSheet(overview, onLine = onLine, onCell = onCell) }
        if (overview.history.size > 1) {
            item { BudgetGraph(overview.history, overview.month, onMonth) }
        }
        items(overview.suggestions, key = { "s${it.lineId}" }) { suggestion ->
            SuggestionCard(suggestion, onAccept = { onAccept(suggestion) }, onDismiss = { onDismiss(suggestion) })
        }
        item {
            Text(
                "Limits: keep under them. Aims, like fuel: going over isn't a big problem, so it only costs half marks. " +
                    "Putting aside: reach it. Tap a line to change it, or someone's figure to change their part.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Add a line") } }
    }
}

@Composable
private fun ScoreCard(score: MonthScore, streak: Int, people: List<Pair<String, MonthScore>> = emptyList()) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    score.percent?.let { "$it%" } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = gradeColourOf(score.grade),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        (score.grade?.label ?: "Nothing to score yet") + if (score.inProgress) " so far" else "",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (score.counted > 0) {
                        Text(
                            "Kept to ${score.kept} of ${score.counted} " + if (score.counted == 1) "line" else "lines",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (score.budgetMinor > 0) {
                val left = score.leftMinor
                Text(
                    "${Money.format(score.spentMinor)} spent of ${Money.format(score.budgetMinor)} — " +
                        when {
                            left >= 0 && score.inProgress -> "${Money.format(left)} left"
                            left >= 0 -> "${Money.format(left)} under budget"
                            else -> "${Money.format(-left)} over"
                        },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Each of you, against your own part.
            if (people.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    people.forEach { (name, theirs) ->
                        Text(
                            "$name ${theirs.percent?.let { "$it%" } ?: "—"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = gradeColourOf(theirs.grade),
                        )
                    }
                }
            }
            if (streak > 0) {
                Text(
                    "Within budget $streak ${if (streak == 1) "month" else "months in a row"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "An aim gone over counts half: that's not a big problem.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SuggestionCard(suggestion: Suggestion, onAccept: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${suggestion.name}: ${Money.format(suggestion.fromMinor)} → ${Money.format(suggestion.toMinor)}?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(suggestion.why, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onAccept) { Text(if (suggestion.raise) "Raise it" else "Lower it") }
                TextButton(onClick = onDismiss) { Text("Leave it") }
            }
        }
    }
}

/** The month's score card, with each person's, as the top of the Budget tab shows it. */
@Composable
internal fun BudgetScoreCard(overview: BudgetOverview) = ScoreCard(
    overview.score,
    overview.streak,
    people = overview.columns.zip(overview.columnScores).filter { it.first.personId != null }.map { (column, score) -> column.name to score },
)

/** One person's usual part of a line. */
@Composable
private fun ShareDialog(person: String, line: String, current: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember { mutableStateOf(Money.formatPlain(current)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$person — $line") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$person's part, a month. The line's total changes with it.", style = MaterialTheme.typography.bodyMedium)
                AmountField(label = "A month", value = amount, onValueChange = { amount = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { Money.parseOrNull(amount)?.let(onSave) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The kind of line, as chosen when setting one up. */
private enum class LineKind(val label: String, val hint: String) {
    LIMIT("A limit", "Bills and the like: keep under it."),
    AIM("An aim", "Like fuel or the food shop: going over isn't a big problem."),
    PUT_ASIDE("Putting aside", "A saver or loan: reach it."),
}

/** Adds a line, or changes one: its amount, kind, what counts, and this month only. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineEditor(
    view: BudgetLineView?,
    month: YearMonth,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (BudgetLineEntity, Long?, Boolean) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val line = view?.line
    var name by remember { mutableStateOf(line?.name.orEmpty()) }
    var amount by remember { mutableStateOf(line?.amountMinor?.let { Money.formatPlain(it) }.orEmpty()) }
    var kind by remember {
        mutableStateOf(
            when {
                line?.isPutAside == true -> LineKind.PUT_ASIDE
                line?.isAim == true -> LineKind.AIM
                else -> LineKind.LIMIT
            },
        )
    }
    var categoryId by remember { mutableStateOf(line?.categoryId) }
    var accountId by remember { mutableStateOf(line?.accountId) }
    var follow by remember { mutableStateOf(line?.followAverage == true) }
    val changed = view?.result?.source == BudgetSource.THIS_MONTH_ONLY
    var thisMonth by remember { mutableStateOf(if (changed) Money.formatPlain(view!!.result.budgetMinor) else "") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(if (line == null) "Add a line" else line.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Called") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            AmountField(label = "A month", value = amount, onValueChange = { amount = it })
            LineKind.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(selected = kind == option, onClick = { kind = option }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = kind == option, onClick = { kind = option })
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(option.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (kind == LineKind.PUT_ASIDE && accounts.isNotEmpty()) {
                AccountChoice(accounts, accountId) {
                    accountId = it
                    if (it != null) categoryId = null
                }
            }
            if (kind != LineKind.PUT_ASIDE || accountId == null) {
                CategoryPicker(
                    label = "What counts",
                    categories = categories.filter { if (kind == LineKind.PUT_ASIDE) it.kind == CategoryKind.SAVING else it.kind == CategoryKind.EXPENSE },
                    selectedId = categoryId,
                    onSelect = { categoryId = it?.id },
                    placeholder = "Choose a category",
                )
            }
            if (kind == LineKind.AIM) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Follow what we usually spend", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "The aim each month is the average of the last three.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = follow, onCheckedChange = { follow = it })
                }
            }
            HorizontalDivider()
            Text("Just for ${monthName(month)}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "A long trip, a birthday: change it for this month only, and the usual amount comes back after.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AmountField(label = "This month only (optional)", value = thisMonth, onValueChange = { thisMonth = it })
            if (changed) TextButton(onClick = { thisMonth = "" }) { Text("Back to the usual amount") }
            val parsed = Money.parseOrNull(amount)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = name.isNotBlank() && parsed != null && parsed > 0,
                    onClick = {
                        val putAside = kind == LineKind.PUT_ASIDE
                        val saved = (line ?: BudgetLineEntity(name = name, amountMinor = 0)).copy(
                            name = name.trim(),
                            amountMinor = parsed ?: 0,
                            isAim = kind == LineKind.AIM,
                            isPutAside = putAside,
                            followAverage = kind == LineKind.AIM && follow,
                            accountId = if (putAside) accountId else null,
                            categoryId = if (putAside && accountId != null) null else categoryId,
                        )
                        val only = Money.parseOrNull(thisMonth)?.takeIf { it > 0 }
                        onSave(saved, only, changed && only == null)
                    },
                ) { Text("Save") }
                if (line != null) TextButton(onClick = { onDelete(line.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AccountChoice(accounts: List<AccountEntity>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(accounts.firstOrNull { it.id == selectedId }?.let { "Paid into ${it.name}" } ?: "Paid into a saver or loan…")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None — use a category") }, onClick = { onSelect(null); open = false })
            accounts.forEach { account ->
                DropdownMenuItem(text = { Text(account.name) }, onClick = { onSelect(account.id); open = false })
            }
        }
    }
}

/** The lines offered by the spreadsheet or by what's been spent, to keep, change or leave out. */
@Composable
private fun SetupReview(
    setup: BudgetSetup,
    categories: List<CategoryEntity>,
    accounts: List<AccountEntity>,
    hasBudget: Boolean,
    onChange: (Int, DraftLine) -> Unit,
    onSave: () -> Unit,
) {
    val kept = setup.drafts.filter { it.include }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (setup.fromSheet) "From your spreadsheet" else "From what you've spent lately",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    (if (setup.fromSheet) "Each outgoing, with everyone's figures added up. " else "Each category's average over the last few months. ") +
                        "Untick what you don't want, and check where each one's money goes. Fuel and food are aims: going over isn't a big problem.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        itemsIndexed(setup.drafts) { index, draft ->
            DraftRow(draft, categories, accounts) { onChange(index, it) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${kept.size} lines · ${Money.format(kept.filter { !it.isPutAside }.sumOf { it.amountMinor })} a month spending" +
                        kept.filter { it.isPutAside }.sumOf { it.amountMinor }.takeIf { it > 0 }?.let { ", ${Money.format(it)} put aside" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (hasBudget) {
                    Text("This replaces the budget you have now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Button(onClick = onSave, enabled = kept.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Save this budget") }
            }
        }
    }
}

@Composable
private fun DraftRow(draft: DraftLine, categories: List<CategoryEntity>, accounts: List<AccountEntity>, onChange: (DraftLine) -> Unit) {
    var amount by remember(draft.name) { mutableStateOf(Money.formatPlain(draft.amountMinor)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = draft.include, onCheckedChange = { onChange(draft.copy(include = it)) })
                Column(Modifier.weight(1f)) {
                    Text(draft.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    draft.sharesText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (draft.include) {
                AmountField(label = "A month", value = amount, onValueChange = { text ->
                    amount = text
                    Money.parseOrNull(text)?.let { onChange(draft.copy(amountMinor = it)) }
                })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !draft.isAim && !draft.isPutAside, onClick = { onChange(draft.copy(isAim = false, isPutAside = false, accountId = null)) }, label = { Text("Limit") })
                    FilterChip(selected = draft.isAim && !draft.isPutAside, onClick = { onChange(draft.copy(isAim = true, isPutAside = false, accountId = null)) }, label = { Text("Aim") })
                    FilterChip(selected = draft.isPutAside, onClick = { onChange(draft.copy(isPutAside = true, isAim = false)) }, label = { Text("Put aside") })
                }
                if (draft.isPutAside && accounts.isNotEmpty()) {
                    AccountChoice(accounts, draft.accountId) { onChange(draft.copy(accountId = it, categoryId = if (it != null) null else draft.categoryId)) }
                }
                if (!draft.isPutAside || draft.accountId == null) {
                    CategoryPicker(
                        label = "What counts",
                        categories = categories.filter { if (draft.isPutAside) it.kind == CategoryKind.SAVING else it.kind == CategoryKind.EXPENSE },
                        selectedId = draft.categoryId,
                        onSelect = { onChange(draft.copy(categoryId = it?.id)) },
                        placeholder = "Choose a category",
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
}

private fun monthName(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.UK)} ${month.year}"

private val SPREADSHEET_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel",
    "text/csv",
    "text/comma-separated-values",
    "application/*",
)
