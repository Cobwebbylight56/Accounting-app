package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.domain.report.LeftOverExplained
import com.rhys.financetracker.domain.report.MonthLeftOver
import com.rhys.financetracker.ui.theme.FinanceTheme

/**
 * How "Left over a month, on average" was worked out: each month's money in,
 * spending, savings and loan payments, and the biggest payments counted as
 * spending — tap one to open it, and change it if it isn't spending.
 */
@Composable
internal fun LeftOverSheet(
    explained: LeftOverExplained,
    onOpenPayment: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Left over a month") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${Money.format(explained.averageMinor, showSign = true)} on average: what came in, less what was " +
                        "spent, put aside and paid off loans, over ${explained.months.size} " +
                        if (explained.months.size == 1) "month." else "months.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                explained.months.forEach { MonthBlock(it) }
                if (explained.biggest.isNotEmpty()) {
                    HorizontalDivider(color = FinanceTheme.colors.cardEdge)
                    Text("Biggest payments counted as spending", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "A move between your own accounts, or paying off a card, isn't spending. If one of these is, " +
                            "tap it and change it to a transfer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    explained.biggest.forEach { payment ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenPayment(payment.transaction.id) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    payment.transaction.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOfNotNull(
                                        DateUtils.formatShort(payment.transaction.date),
                                        payment.accountName,
                                        payment.categoryName,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                Money.format(payment.transaction.amountMinor),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun MonthBlock(month: MonthLeftOver) {
    val colors = FinanceTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DateUtils.formatMonth(month.month), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                Money.format(month.leftMinor, showSign = true),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (month.leftMinor < 0L) colors.negative else colors.positive,
            )
        }
        Figure("Money in", month.inMinor)
        Figure("Spent", month.outMinor)
        if (month.savedMinor != 0L) Figure("Put aside", month.savedMinor)
        if (month.loansMinor != 0L) Figure("Paid off loans", month.loansMinor)
    }
}

@Composable
private fun Figure(label: String, amountMinor: Long) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(Money.format(amountMinor), style = MaterialTheme.typography.bodySmall)
    }
}
