package com.rhys.financetracker.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.ui.components.ColorDot
import com.rhys.financetracker.ui.components.ProgressBarRow
import com.rhys.financetracker.ui.components.colorFromHex
import com.rhys.financetracker.ui.theme.FinanceTheme
import kotlin.math.abs

/**
 * What opens when a slice of the spending chart is tapped.
 *
 * A pie chart on its own only answers "how much"; the useful question is
 * "on what, and is that normal?".  So this shows every entry behind the slice
 * and sets the total against the same category last month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryDetailSheet(
    detail: CategoryDetail,
    onDismiss: () -> Unit,
    onOpenTransaction: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = FinanceTheme.colors
    val accent = colorFromHex(detail.colorHex)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorDot(accent, size = 16.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(detail.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = DateUtils.formatMonth(detail.month),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = Money.format(detail.totalMinor),
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            Spacer(Modifier.height(16.dp))

            // --- against last month ------------------------------------
            if (detail.hasComparison) {
                val worse = detail.changeMinor > 0L
                Text(
                    text = buildString {
                        append(Money.format(abs(detail.changeMinor)))
                        append(if (worse) " more" else " less")
                        append(" than last month")
                        detail.changePercent?.let { append(" (${if (worse) "+" else ""}$it%)") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (worse) colors.negative else colors.positive,
                )
                Spacer(Modifier.height(10.dp))
                ProgressBarRow(
                    label = "Last month",
                    value = Money.format(detail.previousMonthTotalMinor),
                    fraction = fractionOf(detail.previousMonthTotalMinor, detail),
                    color = accent.copy(alpha = 0.5f),
                )
                ProgressBarRow(
                    label = "This month",
                    value = Money.format(detail.totalMinor),
                    fraction = fractionOf(detail.totalMinor, detail),
                    color = accent,
                )
            } else {
                Text(
                    text = "Nothing was spent on this last month, so there is nothing to " +
                        "compare against yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            Text(
                text = "${detail.transactions.size} " +
                    if (detail.transactions.size == 1) "entry" else "entries",
                style = MaterialTheme.typography.titleSmall,
            )

            if (detail.transactions.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "These figures come from regular payments that have not been " +
                        "recorded individually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    // Capped so the sheet never grows past the screen; the list
                    // scrolls inside it instead.
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(detail.transactions, key = { it.transaction.id }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTransaction(item.transaction.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.transaction.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        DateUtils.formatShort(item.transaction.date),
                                        item.accountName,
                                        item.personName,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = Money.format(item.transaction.amountMinor),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Both bars are drawn against whichever month was larger, so they compare fairly. */
private fun fractionOf(amountMinor: Long, detail: CategoryDetail): Float {
    val largest = maxOf(detail.totalMinor, detail.previousMonthTotalMinor)
    return if (largest <= 0L) 0f else (amountMinor.toFloat() / largest).coerceIn(0f, 1f)
}
