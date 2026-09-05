package com.rhys.financetracker.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.repository.InsightRepository
import com.rhys.financetracker.domain.insight.InsightReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

/**
 * Supplies the advice screen.
 *
 * The report is rebuilt whenever the month changes, and also whenever the
 * transaction table does, so recording something and coming straight back shows
 * advice that already accounts for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val insightRepository: InsightRepository,
    transactionRepository: com.rhys.financetracker.data.repository.TransactionRepository,
) : ViewModel() {

    private val month = MutableStateFlow(DateUtils.currentYearMonth())
    private val isLoading = MutableStateFlow(true)

    /**
     * Recomputed on any change to the ledger.  `observeRecent` is used purely as
     * a change signal — it is the cheapest query that fires whenever anything
     * financial moves.
     */
    private val report: StateFlow<InsightReport> =
        combine(month, transactionRepository.observeRecent(1)) { current, _ -> current }
            .flatMapLatest { current ->
                flow {
                    isLoading.value = true
                    emit(insightRepository.buildReport(current))
                    isLoading.value = false
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightReport.EMPTY)

    val state: StateFlow<InsightsState> = combine(report, isLoading, month) { built, loading, current ->
        InsightsState(
            report = built,
            isLoading = loading,
            month = current,
            isCurrentMonth = current == DateUtils.currentYearMonth(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsState())

    fun showPreviousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun showNextMonth() {
        val next = month.value.plusMonths(1)
        if (!next.isAfter(DateUtils.currentYearMonth())) month.value = next
    }
}

data class InsightsState(
    val report: InsightReport = InsightReport.EMPTY,
    val isLoading: Boolean = true,
    val month: YearMonth = DateUtils.currentYearMonth(),
    val isCurrentMonth: Boolean = true,
)
