package com.rhys.financetracker.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.entity.RecurringRuleEntity
import com.rhys.financetracker.data.local.entity.TransactionEntity
import com.rhys.financetracker.domain.model.Frequency
import com.rhys.financetracker.domain.model.PaymentKind
import com.rhys.financetracker.domain.model.RecurrenceMode
import com.rhys.financetracker.domain.model.TransactionType
import com.rhys.financetracker.domain.recurrence.RegularSchedule
import java.time.format.TextStyle
import java.util.Locale

/**
 * On a payment's page: whether it happens regularly. Not yet — one tap to
 * say it is. Already — what kind, when, and the next one.
 */
@Composable
internal fun RegularPaymentCard(
    rule: RecurringRuleEntity?,
    isTransfer: Boolean,
    onSetUp: () -> Unit,
    onStop: () -> Unit,
) {
    if (rule == null || rule.isArchived) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (isTransfer) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventRepeat, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Happens regularly?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "A direct debit, standing order or savings move: set its day and the app adds it " +
                            "each time by itself, no statement needed.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSetUp) { Text("Set up") }
            }
        }
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventRepeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    rule.paymentKind?.displayName ?: "Regular payment",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                RegularSchedule.describe(rule.frequency, rule.startDate).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
            )
            val how = when {
                rule.isPaused -> "Paused"
                rule.mode == RecurrenceMode.REMIND_ONLY -> "you'll get a reminder"
                rule.isVariableAmount -> "added by itself, marked to check the amount"
                else -> "added by itself"
            }
            Text(
                if (rule.isPaused) how else "Next: ${DateUtils.format(rule.nextDueDate)} — $how",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSetUp) { Text("Change") }
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

/**
 * Making a payment regular, in one sheet: what it is, how often, which day,
 * and whether the app adds it by itself. The next date is shown as it is
 * chosen, so there is no guessing what will happen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RegularPaymentSheet(
    payment: TransactionEntity,
    rule: RecurringRuleEntity?,
    guess: PaymentKind,
    onDismiss: () -> Unit,
    onSave: (kind: PaymentKind, frequency: Frequency, day: Int, addByItself: Boolean, amountChanges: Boolean) -> Unit,
) {
    val current = rule?.takeUnless { it.isArchived }
    var kind by remember { mutableStateOf(current?.paymentKind ?: guess) }
    var frequency by remember {
        mutableStateOf(current?.frequency?.takeIf { it in RegularSchedule.CHOICES } ?: Frequency.MONTHLY)
    }
    var day by remember { mutableIntStateOf(current?.startDate?.dayOfMonth ?: payment.date.dayOfMonth) }
    var addByItself by remember { mutableStateOf(current?.mode != RecurrenceMode.REMIND_ONLY) }
    var amountChanges by remember { mutableStateOf(current?.isVariableAmount ?: false) }

    val kinds = if (payment.type == TransactionType.INCOME) {
        listOf(PaymentKind.MONEY_IN, PaymentKind.STANDING_ORDER)
    } else {
        PaymentKind.entries - PaymentKind.MONEY_IN
    }
    val next = remember(frequency, day, payment) {
        RegularSchedule.plan(
            rule = RecurringRuleEntity(
                name = payment.description,
                amountMinor = payment.amountMinor,
                type = payment.type,
                frequency = frequency,
                startDate = payment.date,
                nextDueDate = payment.date,
                accountId = payment.accountId,
            ),
            paid = payment.date,
            day = day,
            today = DateUtils.today(),
            doneUpTo = current?.lastGeneratedDate,
        ).nextDueDate
    }

    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    if (current == null) "Make it regular" else "Change regular payment",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${payment.description} · ${Money.format(payment.amountMinor)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Label("What is it?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { option ->
                    FilterChip(
                        selected = kind == option,
                        onClick = { kind = option },
                        label = { Text(option.displayName) },
                    )
                }
            }

            Label("How often?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegularSchedule.CHOICES.forEach { option ->
                    FilterChip(
                        selected = frequency == option,
                        onClick = { frequency = option },
                        label = { Text(option.displayName) },
                    )
                }
            }

            if (RegularSchedule.usesDayOfMonth(frequency)) {
                Label("Which day?")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { day = if (day <= 1) 31 else day - 1 }) {
                        Icon(Icons.Default.Remove, contentDescription = "Day before")
                    }
                    Text(
                        RegularSchedule.ordinal(day),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                    FilledTonalIconButton(onClick = { day = if (day >= 31) 1 else day + 1 }) {
                        Icon(Icons.Default.Add, contentDescription = "Day after")
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (frequency == Frequency.YEARLY) {
                            "of " + payment.date.month.getDisplayName(TextStyle.FULL, Locale.UK)
                        } else {
                            "of each month"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (day > 28) {
                    Text(
                        "In shorter months it goes on the last day.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Every " + payment.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.UK) +
                        ", counting on from this one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SwitchLine(
                title = "Add it by itself on the day",
                subtitle = if (addByItself) {
                    "No statement needed. When the statement comes in, it matches this up and puts any details right."
                } else {
                    "Not added — you'll get a reminder the day before instead."
                },
                checked = addByItself,
                onChange = { addByItself = it },
            )
            SwitchLine(
                title = "The amount can change",
                subtitle = "It's added marked \"to check\", so you can put the real amount in.",
                checked = amountChanges,
                onChange = { amountChanges = it },
            )

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    "Next one: ${DateUtils.format(next)} · ${Money.format(payment.amountMinor)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }

            Button(
                onClick = { onSave(kind, frequency, day, addByItself, amountChanges) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchLine(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
