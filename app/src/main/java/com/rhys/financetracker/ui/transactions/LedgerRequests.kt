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
    fun openCategory(categoryId: Long?, month: YearMonth) =
        openCategoryBetween(categoryId, month.atDay(1), month.atEndOfMonth())

    /** One category's payments between two dates — a report's period, say. */
    fun openCategoryBetween(categoryId: Long?, from: java.time.LocalDate, to: java.time.LocalDate) {
        pending.value = TransactionFilter(
            categoryIds = categoryId?.let { setOf(it) }.orEmpty(),
            onlyUncategorised = categoryId == null,
            dateFrom = from,
            dateTo = to,
        )
    }

    /** A search for [text] in [month] — one payee's payments, say. */
    fun openSearch(text: String, month: YearMonth) {
        pending.value = TransactionFilter(
            text = text,
            dateFrom = month.atDay(1),
            dateTo = month.atEndOfMonth(),
        )
    }

    /** Every payment matching [text], whenever it was — one subscription's history, say. */
    fun openSearchAll(text: String) {
        pending.value = TransactionFilter(text = text)
    }

    /** Everything on one account, in and out. */
    fun openAccount(accountId: Long) {
        pending.value = TransactionFilter(accountIds = setOf(accountId))
    }

    /** Payments marked to check. */
    fun openToCheck() {
        pending.value = TransactionFilter(onlyUnconfirmed = true)
    }

    fun consumed() {
        pending.value = null
    }
}
