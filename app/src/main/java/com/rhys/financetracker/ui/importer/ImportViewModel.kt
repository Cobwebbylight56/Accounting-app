package com.rhys.financetracker.ui.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
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
            _state.value = _state.value.copy(isBusy = true, error = null)
            when (val result = importer.readWorkbook(uri)) {
                is AppResult.Success -> {
                    val workbook = result.data
                    val firstSheet = workbook.sheets.firstOrNull()
                    val detected = firstSheet?.let(importer::detectHouseholdLayout)
                    val statement = firstSheet?.let { importer.detectStatement(it) }
                    _state.value = ImportState(
                        step = if (firstSheet == null) ImportStep.CHOOSE_FILE else ImportStep.MAP,
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
                if (it.isImportable) it.copy(isSelected = selected) else it
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
    /** Set when the sheet looks like a downloaded bank statement. */
    val detectedStatement: ImportMapping? = null,
    /** The account this import was started from, when it began on one. */
    val preselectedAccountId: Long? = null,
    val usingDetectedLayout: Boolean = false,
    val candidates: List<ImportCandidate> = emptyList(),
    val outcome: ImportOutcome? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val sheet: SheetData?
        get() = workbook?.sheets?.getOrNull(selectedSheetIndex)

    val selectedCount: Int get() = candidates.count { it.isSelected && it.isImportable }
    val problemCount: Int get() = candidates.count { !it.isImportable }

    /** True when the sheet can be imported whole without any manual mapping. */
    val canAutoImport: Boolean get() = detectedLayout?.isUsable == true

    /** True when the sheet is a bank statement and can be read as it stands. */
    val canImportStatement: Boolean get() = detectedStatement != null

    /** How many rows the import would skip because they are already recorded. */
    val alreadyPresentCount: Int get() = candidates.count { it.isAlreadyPresent }
}
