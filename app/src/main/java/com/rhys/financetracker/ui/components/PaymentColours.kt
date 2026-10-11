package com.rhys.financetracker.ui.components

import androidx.compose.ui.graphics.Color
import com.rhys.financetracker.data.local.projection.TransactionWithDetails
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.ui.theme.FinanceColors

/**
 * The colour a payment's amount is shown in, wherever it's listed.
 *
 * Spending on a credit card is yellow, so it stands apart from spending
 * from the bank, which is red. Paying a card off is green, like money in —
 * whether it shows on the card or as the move from the bank.
 */
fun FinanceColors.amountOf(item: TransactionWithDetails, transferColor: Color = transfer): Color = when {
    item.isOnCredit -> card
    item.paysOffCredit -> income
    else -> when (item.transaction.type) {
        TransactionType.INCOME -> income
        TransactionType.EXPENSE -> expense
        TransactionType.TRANSFER -> transferColor
    }
}
