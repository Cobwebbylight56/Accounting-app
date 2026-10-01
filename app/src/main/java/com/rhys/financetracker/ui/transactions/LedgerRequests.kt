package com.rhys.financetracker.ui.transactions

import com.rhys.financetracker.data.local.dao.TransactionFilter
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What another screen wants the Money tab to open on.
 *
 * Money is a tab, and a tab's route carries no arguments: it is restored as
 * it was left. So a screen that sends someone there — Advice saying "you spent
 * more on Eating out" — leaves the filter here, and the Money tab picks it up
 * as it opens. Without it the tap landed on the whole unfiltered list and
 * what had been tapped was lost.
 */
@Singleton
class LedgerRequests @Inject constructor() {

    private val pending = MutableStateFlow<TransactionFilter?>(null)
    val requests: StateFlow<TransactionFilter?> = pending

    /** One category's payments in [month]; a null [categoryId] means the uncategorised ones. */
    fun openCategory(categoryId: Long?, month: YearMonth) {
        pending.value = TransactionFilter(
            categoryIds = categoryId?.let { setOf(it) }.orEmpty(),
            onlyUncategorised = categoryId == null,
            dateFrom = month.atDay(1),
            dateTo = month.atEndOfMonth(),
        )
    }

    fun consumed() {
        pending.value = null
    }
}
