package com.rhys.financetracker.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.result.AppResult
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.export.ExportManager
import com.rhys.financetracker.data.export.ExportedFile
import com.rhys.financetracker.data.export.ReportPrinter
import com.rhys.financetracker.data.local.entity.AccountEntity
import com.rhys.financetracker.data.local.entity.PersonEntity
import com.rhys.financetracker.data.repository.AccountRepository
import com.rhys.financetracker.data.repository.PeopleRepository
import com.rhys.financetracker.domain.model.ExportFormat
import com.rhys.financetracker.domain.model.PageOrientation
import com.rhys.financetracker.domain.report.Report
import com.rhys.financetracker.domain.report.ReportBuilder
import com.rhys.financetracker.domain.report.ReportPeriod
import com.rhys.financetracker.domain.report.ReportScope
import com.rhys.financetracker.domain.report.ReportType
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Builds, exports and prints reports.
 *
 * The report is rebuilt whenever the type, period or scope changes, and the
 * same [Report] object is what gets printed and exported, so what is on screen
 * and what comes out of the printer can never disagree.
 */
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val reportBuilder: ReportBuilder,
    private val exportManager: ExportManager,
    private val reportPrinter: ReportPrinter,
    accountRepository: AccountRepository,
    peopleRepository: PeopleRepository,
) : ViewModel() {

    private val reportType = MutableStateFlow(ReportType.MONTHLY_SPENDING)
    private val period = MutableStateFlow(ReportPeriod.month(DateUtils.currentYearMonth()))
    private val scope = MutableStateFlow(ReportScope.HOUSEHOLD)
    private val report = MutableStateFlow<Report?>(null)
    private val isBuilding = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val exported = MutableStateFlow<ExportedFile?>(null)
    private val orientation = MutableStateFlow(PageOrientation.PORTRAIT)

    val state: StateFlow<ReportsState> = combine(
        combine(reportType, period, scope) { type, currentPeriod, currentScope ->
            Triple(type, currentPeriod, currentScope)
        },
        combine(report, isBuilding, message) { current, building, text ->
            Triple(current, building, text)
        },
        accountRepository.observeActive(),
        peopleRepository.observeActive(),
        combine(exported, orientation) { file, page -> file to page },
    ) { selection, output, accounts, people, extras ->
        val (type, currentPeriod, currentScope) = selection
        val (currentReport, building, text) = output
        ReportsState(
            reportType = type,
            period = currentPeriod,
            scope = currentScope,
            report = currentReport,
            isBuilding = building,
            accounts = accounts,
            people = people,
            message = text,
            exportedFile = extras.first,
            orientation = extras.second,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsState())

    init {
        rebuild()
    }

    fun setType(type: ReportType) {
        reportType.value = type
        // A yearly report over a single month would be empty; widen the period.
        if (type == ReportType.YEARLY_SPENDING || type == ReportType.INCOME_VS_EXPENSES) {
            period.value = ReportPeriod.year(DateUtils.today().year)
        }
        rebuild()
    }

    fun setMonth(month: YearMonth) {
        period.value = ReportPeriod.month(month)
        rebuild()
    }

    fun setYear(year: Int) {
        period.value = ReportPeriod.year(year)
        rebuild()
    }

    fun setCustomPeriod(start: LocalDate, end: LocalDate) {
        period.value = ReportPeriod.custom(start, end)
        rebuild()
    }

    fun setScope(newScope: ReportScope) {
        scope.value = newScope
        rebuild()
    }

    fun setOrientation(newOrientation: PageOrientation) {
        orientation.value = newOrientation
    }

    fun rebuild() {
        viewModelScope.launch {
            isBuilding.value = true
            try {
                report.value = reportBuilder.build(reportType.value, period.value, scope.value)
            } catch (error: Exception) {
                message.value = error.message ?: "That report could not be built"
            } finally {
                isBuilding.value = false
            }
        }
    }

    fun export(format: ExportFormat) {
        val current = report.value ?: return
        viewModelScope.launch {
            when (
                val result =
                    exportManager.exportReportToCache(current, format, orientation.value)
            ) {
                is AppResult.Success -> {
                    exported.value = result.data
                    message.value = "${result.data.name} is ready"
                }
                is AppResult.Failure -> message.value = result.message
            }
        }
    }

    /** Produces the PDF and hands it straight to Android's print system. */
    fun print() {
        val current = report.value ?: return
        viewModelScope.launch {
            when (
                val result = exportManager.exportReportToCache(
                    current,
                    ExportFormat.PDF,
                    orientation.value,
                )
            ) {
                is AppResult.Success -> reportPrinter.print(
                    file = result.data.file,
                    jobName = "${current.title} — ${current.period.label}",
                    landscape = orientation.value == PageOrientation.LANDSCAPE,
                )
                is AppResult.Failure -> message.value = result.message
            }
        }
    }

    fun consumeExportedFile() {
        exported.value = null
    }

    fun clearMessage() {
        message.value = null
    }
}

data class ReportsState(
    val reportType: ReportType = ReportType.MONTHLY_SPENDING,
    val period: ReportPeriod = ReportPeriod.month(DateUtils.currentYearMonth()),
    val scope: ReportScope = ReportScope.HOUSEHOLD,
    val report: Report? = null,
    val isBuilding: Boolean = false,
    val accounts: List<AccountEntity> = emptyList(),
    val people: List<PersonEntity> = emptyList(),
    val message: String? = null,
    val exportedFile: ExportedFile? = null,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
)
