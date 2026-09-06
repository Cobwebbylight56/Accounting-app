package com.rhys.financetracker.ui.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.importer.AccountFitCheck
import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.DetectedLayout
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportMapping
import com.rhys.financetracker.data.importer.ImportOutcome
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.SheetData
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.WorkbookData
import com.rhys.financetracker.data.local.projection.AccountOption
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.domain.model.TransactionType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the spreadsheet import, one step at a time.
 *
 * Nothing is written to the database until [applyImport] is called on the last
 * step, so the user can go back and change their mind at any point.
 */
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importer: SpreadsheetImporter,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** Offered when filing a statement, so the rows land on the right account. */
    val accounts: StateFlow<List<AccountOption>> = accountRepository.observeActiveOptions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Step 1: read the file the user picked.
     *
     * If the sheet turns out to be a household budget — a column of figures per
     * person, in blocks — the layout is detected and offered as a single
     * button, because mapping that by hand means running the import once per
     * person and is where people give up.
     */
    fun openFile(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null, sourceUri = uri)
            when (val result = importer.readWorkbook(uri)) {
                is AppResult.Success -> {
                    val workbook = result.data
                    val firstSheet = workbook.sheets.firstOrNull()
                    val detected = firstSheet?.let(importer::detectHouseholdLayout)
                    val statement = firstSheet?.let { importer.detectStatement(it) }
                    _state.value = ImportState(
                        step = if (firstSheet == null) ImportStep.CHOOSE_FILE else ImportStep.MAP,
                        sourceUri = uri,
                        workbook = workbook,
                        selectedSheetIndex = 0,
                        detectedLayout = detected,
                        detectedStatement = statement,
                        mapping = firstSheet?.let {
                            importer.suggestMapping(it, ImportTarget.RECURRING_EXPENSE)
                        },
                    ).also { newState -> refreshCandidates(newState) }
                }
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isBusy = false,
                    error = result.message,
                    // A PDF the reader could not make sense of is worth showing:
                    // the layout is what needs fixing, and it cannot be seen
                    // from here.
                    unreadablePdfText = importer.readPdfText(uri)?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    /**
     * Takes the detected layout and builds candidates for every person and
     * every block at once, then goes straight to the review step.
     */
    fun useDetectedLayout() {
        val current = _state.value
        val sheet = current.sheet ?: return
        val layout = current.detectedLayout ?: return
        _state.value = current.copy(
            candidates = importer.buildCandidatesForLayout(sheet, layout),
            usingDetectedLayout = true,
            step = ImportStep.REVIEW,
        )
    }

    /**
     * Reads a bank statement with the detected mapping and goes to review.
     *
     * [account] files the rows against an account, which matters because
     * duplicate checking is per account: the same £40 at the same shop on the
     * same day can legitimately appear on two different cards. It is passed by
     * id rather than name, since names are only unique per person.
     */
    fun useDetectedStatement(account: AccountOption? = null) {
        val current = _state.value
        val sheet = current.sheet ?: return
        val detected = current.detectedStatement ?: return
        val mapping = if (account == null) {
            detected
        } else {
            detected.copy(defaultAccountName = account.name, defaultAccountId = account.id)
        }
        viewModelScope.launch {
            _state.value = current.copy(isBusy = true)
            val candidates = importer.buildCandidatesWithDuplicates(sheet, mapping)
            _state.value = _state.value.copy(
                mapping = mapping,
                candidates = candidates,
                chosenAccount = account,
                // Filing a statement against the wrong account is silent and
                // expensive to undo, so it is checked while there is still a
                // review screen to say it on.
                accountFit = account?.let { importer.checkAccountFit(candidates, it.id) },
                usingDetectedLayout = false,
                step = ImportStep.REVIEW,
                isBusy = false,
            )
        }
    }

    /**
     * Remembers the account the importer was opened from.
     *
     * Kept in state rather than passed straight through, because the file has
     * not been chosen yet — the answer has to survive until the statement card
     * appears.
     */
    fun preselectAccount(accountId: Long?) {
        if (_state.value.preselectedAccountId == accountId) return
        _state.value = _state.value.copy(preselectedAccountId = accountId)
    }

    /**
     * Goes back to pick a different account, with the suggested one already
     * selected — the answer to "which one then?" is the whole point of having
     * asked, and making the user find it again would be a poor reward for
     * taking the advice.
     *
     * The warning itself is dropped on the way. It was about the account just
     * left behind, and leaving it up would make the next choice look condemned
     * before it had been checked.
     */
    fun chooseAnotherAccount() {
        val suggested = _state.value.accountFit?.suggestedAccountId
        _state.value = _state.value.copy(
            step = ImportStep.MAP,
            accountFit = null,
            chosenAccount = null,
            preselectedAccountId = suggested,
        )
    }

    /** Keeps the chosen account despite the warning, and says no more about it. */
    fun keepChosenAccount() {
        _state.value = _state.value.copy(accountFit = null)
    }

    /** Falls back to mapping the columns by hand. */
    fun mapByHand() {
        _state.value = _state.value.copy(usingDetectedLayout = false, step = ImportStep.MAP)
    }

    fun selectSheet(index: Int) {
        val workbook = _state.value.workbook ?: return
        val sheet = workbook.sheets.getOrNull(index) ?: return
        val target = _state.value.mapping?.target ?: ImportTarget.RECURRING_EXPENSE
        val newState = _state.value.copy(
            selectedSheetIndex = index,
            detectedLayout = importer.detectHouseholdLayout(sheet),
            detectedStatement = importer.detectStatement(sheet),
            usingDetectedLayout = false,
            mapping = importer.suggestMapping(sheet, target),
        )
        refreshCandidates(newState)
    }

    fun setTarget(target: ImportTarget) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(_state.value.copy(mapping = mapping.copy(target = target)))
    }

    fun setColumnRole(column: Int, role: ColumnRole) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(columnRoles = mapping.columnRoles + (column to role)),
            ),
        )
    }

    fun setHeaderRow(row: Int) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(
                    headerRow = row,
                    firstDataRow = (row + 1).coerceAtLeast(0),
                ),
            ),
        )
    }

    fun setRowRange(first: Int, last: Int) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(firstDataRow = first, lastDataRow = last),
            ),
        )
    }

    /**
     * Applies a single person or account to the whole block — the layout most
     * hand-built household spreadsheets use, where one column of figures
     * belongs to one person.
     */
    fun setDefaults(person: String?, account: String?, category: String?) {
        val mapping = _state.value.mapping ?: return
        refreshCandidates(
            _state.value.copy(
                mapping = mapping.copy(
                    defaultPersonName = person?.takeIf { it.isNotBlank() },
                    defaultAccountName = account?.takeIf { it.isNotBlank() },
                    defaultCategoryName = category?.takeIf { it.isNotBlank() },
                ),
            ),
        )
    }

    /**
     * Flips one row between money in and money out.
     *
     * The app cannot always tell. A statement gives a line of text, and where
     * neither the running balance nor a debit/credit letter says which way the
     * money went, an employer's name on a credit is indistinguishable from a
     * shop's name on a payment. Rather than guessing and being confidently
     * wrong across a whole file, the reading is shown and this changes it.
     */
    fun toggleDirection(id: String) {
        applyDirections { candidate ->
            if (candidate.id == id) candidate.flipped() else candidate
        }
    }

    /** Flips every row at once, for a file read the wrong way round throughout. */
    fun swapAllDirections() {
        applyDirections { it.flipped() }
    }

    private fun ImportCandidate.flipped(): ImportCandidate =
        if (target != ImportTarget.TRANSACTION) {
            this
        } else {
            // Null means "nothing said", which is read as money out, so its
            // opposite is money in.
            copy(
                transactionType = if (transactionType == TransactionType.INCOME) {
                    TransactionType.EXPENSE
                } else {
                    TransactionType.INCOME
                },
            )
        }

    /**
     * Applies a change of direction and then asks the ledger again.
     *
     * Duplicate checking and the corrections both key on which way the money
     * went, so every verdict about these rows is stale the moment one is
     * flipped.
     */
    private fun applyDirections(change: (ImportCandidate) -> ImportCandidate) {
        val current = _state.value
        val mapping = current.mapping ?: return
        val changed = current.candidates.map(change)
        _state.value = current.copy(candidates = changed)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                candidates = importer.refreshAgainstLedger(changed, mapping),
            )
        }
    }

    fun toggleCandidate(id: String) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map { candidate ->
                if (candidate.id == id) {
                    candidate.copy(isSelected = !candidate.isSelected)
                } else {
                    candidate
                }
            },
        )
    }

    fun selectAll(selected: Boolean) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map {
                // Rows already in the ledger stay unticked. Ticking them makes
                // no difference to what is written — they are skipped either
                // way — but it makes the count above the list promise to add
                // rows it is not going to add.
                if (it.isImportable && !it.isAlreadyPresent) {
                    it.copy(isSelected = selected)
                } else {
                    it
                }
            },
        )
    }

    fun goToReview() {
        _state.value = _state.value.copy(step = ImportStep.REVIEW)
    }

    fun goToMapping() {
        _state.value = _state.value.copy(step = ImportStep.MAP, usingDetectedLayout = false)
        // Rebuild from the hand-made mapping, discarding any auto-detected set.
        refreshCandidates(_state.value)
    }

    /** Step 3: write the selected rows. */
    fun applyImport() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            when (val result = importer.applyCandidates(_state.value.candidates)) {
                is AppResult.Success -> _state.value = _state.value.copy(
                    isBusy = false,
                    step = ImportStep.DONE,
                    outcome = result.data,
                )
                is AppResult.Failure -> _state.value = _state.value.copy(
                    isBusy = false,
                    error = result.message,
                )
            }
        }
    }

    fun reset() {
        _state.value = ImportState()
    }

    /**
     * Puts the raw text of the PDF on screen at any point, not only when the
     * reading failed outright.
     *
     * A statement that imports but reads wrongly is the harder case, and it
     * cannot be diagnosed without seeing what the lines actually look like.
     */
    fun showWhatWasRead() {
        val uri = _state.value.sourceUri ?: return
        viewModelScope.launch {
            val text = importer.readPdfText(uri)?.takeIf { it.isNotBlank() }
            _state.value = _state.value.copy(
                unreadablePdfText = text,
                error = if (text == null) "That file is not a PDF, so there is no text to show." else null,
            )
        }
    }

    fun clearUnreadablePdf() {
        _state.value = _state.value.copy(unreadablePdfText = null)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    /** Recomputes the preview whenever the hand-made mapping changes. */
    private fun refreshCandidates(newState: ImportState) {
        val sheet = newState.sheet
        val mapping = newState.mapping
        _state.value = when {
            sheet == null || mapping == null ->
                newState.copy(isBusy = false, candidates = emptyList())
            // An auto-detected set spans several mappings; rebuilding it from
            // the single hand-made mapping would throw most of it away.
            newState.usingDetectedLayout ->
                newState.copy(
                    isBusy = false,
                    candidates = newState.detectedLayout
                        ?.let { importer.buildCandidatesForLayout(sheet, it) }
                        .orEmpty(),
                )
            else -> newState.copy(
                isBusy = false,
                candidates = importer.buildCandidates(sheet, mapping),
            )
        }
    }
}

enum class ImportStep { CHOOSE_FILE, MAP, REVIEW, DONE }

data class ImportState(
    val step: ImportStep = ImportStep.CHOOSE_FILE,
    val workbook: WorkbookData? = null,
    val selectedSheetIndex: Int = 0,
    val mapping: ImportMapping? = null,
    /** Set when the sheet looks like a household budget the app can read whole. */
    val detectedLayout: DetectedLayout? = null,
    /** The file being imported, kept so its text can be shown on request. */
    val sourceUri: Uri? = null,
    /** Set when the sheet looks like a downloaded bank statement. */
    val detectedStatement: ImportMapping? = null,
    /** The account a statement is being filed against, once one is chosen. */
    val chosenAccount: AccountOption? = null,
    /** Set when the rows look like they belong to a different account. */
    val accountFit: AccountFitCheck.Verdict? = null,
    /** The account this import was started from, when it began on one. */
    val preselectedAccountId: Long? = null,
    /** Text pulled from a PDF whose layout was not recognised, for showing. */
    val unreadablePdfText: String? = null,
    val usingDetectedLayout: Boolean = false,
    val candidates: List<ImportCandidate> = emptyList(),
    val outcome: ImportOutcome? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val sheet: SheetData?
        get() = workbook?.sheets?.getOrNull(selectedSheetIndex)

    /** How many rows the import will actually act on. */
    val selectedCount: Int
        get() = candidates.count { it.isSelected && it.isImportable && !it.isAlreadyPresent }
    val problemCount: Int get() = candidates.count { !it.isImportable }

    /** True when the sheet can be imported whole without any manual mapping. */
    val canAutoImport: Boolean get() = detectedLayout?.isUsable == true

    /** True when the sheet is a bank statement and can be read as it stands. */
    val canImportStatement: Boolean get() = detectedStatement != null

    /** How many rows the import would skip because they are already recorded. */
    val alreadyPresentCount: Int get() = candidates.count { it.isAlreadyPresent }

    /** How many rows will correct an entry already held rather than add one. */
    val correctionCount: Int
        get() = candidates.count { it.isSelected && it.isImportable && it.corrects != null }

    /** How many rows will become new entries, which is not all the selected ones. */
    val additionCount: Int get() = selectedCount - correctionCount

    private val transactions: List<ImportCandidate>
        get() = candidates.filter { it.isImportable && it.target == ImportTarget.TRANSACTION }

    /** How the rows are being read, which is the thing most worth checking. */
    val moneyInCount: Int get() = transactions.count { it.transactionType == TransactionType.INCOME }
    val moneyOutCount: Int get() = transactions.size - moneyInCount
}
