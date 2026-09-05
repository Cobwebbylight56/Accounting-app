package com.rhys.financetracker.ui.importer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.data.importer.ColumnRole
import com.rhys.financetracker.data.importer.ImportCandidate
import com.rhys.financetracker.data.importer.ImportMapping
import com.rhys.financetracker.data.importer.ImportOutcome
import com.rhys.financetracker.data.importer.ImportTarget
import com.rhys.financetracker.data.importer.SheetData
import com.rhys.financetracker.data.importer.SpreadsheetImporter
import com.rhys.financetracker.data.importer.WorkbookData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) : ViewModel() {

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    /** Step 1: read the file the user picked. */
    fun openFile(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, error = null)
            when (val result = importer.readWorkbook(uri)) {
                is AppResult.Success -> {
                    val workbook = result.data
                    val firstSheet = workbook.sheets.firstOrNull()
                    _state.value = ImportState(
                        step = if (firstSheet == null) ImportStep.CHOOSE_FILE else ImportStep.MAP,
                        workbook = workbook,
                        selectedSheetIndex = 0,
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

    fun selectSheet(index: Int) {
        val workbook = _state.value.workbook ?: return
        val sheet = workbook.sheets.getOrNull(index) ?: return
        val target = _state.value.mapping?.target ?: ImportTarget.RECURRING_EXPENSE
        val newState = _state.value.copy(
            selectedSheetIndex = index,
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

    fun toggleCandidate(sourceRow: Int) {
        _state.value = _state.value.copy(
            candidates = _state.value.candidates.map { candidate ->
                if (candidate.sourceRow == sourceRow) {
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
        _state.value = _state.value.copy(step = ImportStep.MAP)
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

    /** Recomputes the preview whenever the mapping changes. */
    private fun refreshCandidates(newState: ImportState) {
        val sheet = newState.sheet
        val mapping = newState.mapping
        _state.value = if (sheet == null || mapping == null) {
            newState.copy(isBusy = false, candidates = emptyList())
        } else {
            newState.copy(
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
    val candidates: List<ImportCandidate> = emptyList(),
    val outcome: ImportOutcome? = null,
    val isBusy: Boolean = false,
    val error: String? = null,
) {
    val sheet: SheetData?
        get() = workbook?.sheets?.getOrNull(selectedSheetIndex)

    val selectedCount: Int get() = candidates.count { it.isSelected && it.isImportable }
    val problemCount: Int get() = candidates.count { !it.isImportable }
}
