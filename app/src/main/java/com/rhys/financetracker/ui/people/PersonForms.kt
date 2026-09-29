package com.rhys.financetracker.ui.people

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rhys.financetracker.core.money.Money
import com.rhys.financetracker.core.time.DateUtils
import com.rhys.financetracker.data.local.projection.AccountWithBalance
import com.rhys.financetracker.domain.income.PayRise
import com.rhys.financetracker.domain.model.AccountType
import com.rhys.financetracker.ui.components.AmountField
import com.rhys.financetracker.ui.components.DateField
import com.rhys.financetracker.ui.components.DropdownField
import com.rhys.financetracker.ui.components.LabelledTextField
import java.time.LocalDate

/*
 * The small forms a person is built from — an account, a loan, a pay rise —
 * shared by the first-run setup and each person's own page, so both ask the
 * same questions the same way.
 */

/** An account being typed in. */
data class AccountDraft(
    val type: AccountType = AccountType.CURRENT,
    val name: String = "",
    val balanceText: String = "",
    /** Set once the name is typed, after which the type no longer renames it. */
    val nameTouched: Boolean = false,
)

/** The kinds offered for an account; loans have their own form. */
val ACCOUNT_KINDS = listOf(
    AccountType.CURRENT,
    AccountType.SAVINGS,
    AccountType.CREDIT_CARD,
    AccountType.INVESTMENT,
    AccountType.PENSION,
    AccountType.OTHER,
)

@Composable
fun AccountDraftForm(
    draft: AccountDraft,
    onChange: (AccountDraft) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ACCOUNT_KINDS.forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    onClick = {
                        onChange(
                            draft.copy(
                                type = type,
                                name = if (draft.nameTouched) draft.name else "",
                            ),
                        )
                    },
                    label = { Text(type.displayName) },
                )
            }
        }
        LabelledTextField(
            label = "Account name",
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it, nameTouched = true)) },
            placeholder = draft.type.displayName,
            supportingText = "What the bank calls it, e.g. Nationwide FlexDirect or Start to Save.",
        )
        AmountField(
            label = if (draft.type == AccountType.CREDIT_CARD) "Owed on it today" else "In it today",
            value = draft.balanceText,
            onValueChange = { onChange(draft.copy(balanceText = it)) },
        )
        Button(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Add this account")
        }
    }
}

/** A loan being typed in. */
data class LoanDraft(
    val name: String = "",
    val owedText: String = "",
    val originalText: String = "",
    val monthlyText: String = "",
    val dayText: String = "1",
    val payFromAccountId: Long? = null,
)

@Composable
fun LoanDraftForm(
    draft: LoanDraft,
    payFrom: List<AccountWithBalance>,
    onChange: (LoanDraft) -> Unit,
    onAdd: () -> Unit,
    enabled: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabelledTextField(
            label = "What is it?",
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            placeholder = "Car loan, mortgage, catalogue…",
        )
        AmountField(
            label = "Still to pay",
            value = draft.owedText,
            onValueChange = { onChange(draft.copy(owedText = it)) },
        )
        AmountField(
            label = "Borrowed at the start (optional)",
            value = draft.originalText,
            onValueChange = { onChange(draft.copy(originalText = it)) },
        )
        AmountField(
            label = "Paid each month (optional)",
            value = draft.monthlyText,
            onValueChange = { onChange(draft.copy(monthlyText = it)) },
        )
        if (Money.parseOrNull(draft.monthlyText)?.let { it > 0L } == true) {
            LabelledTextField(
                label = "Day of the month it's paid",
                value = draft.dayText,
                onValueChange = { text ->
                    onChange(draft.copy(dayText = text.filter { it.isDigit() }.take(2)))
                },
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            )
            if (payFrom.isNotEmpty()) {
                DropdownField(
                    label = "Paid from",
                    options = payFrom,
                    selected = payFrom.firstOrNull { it.account.id == draft.payFromAccountId },
                    onSelect = { onChange(draft.copy(payFromAccountId = it.account.id)) },
                    optionLabel = { it.account.name },
                    placeholder = "Choose an account",
                )
                Text(
                    text = "Each month's payment comes off what is owed by itself. When it " +
                        "reaches nothing, the loan disappears and the payment stops.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text("Add this loan")
        }
    }
}

/** A pay rise being typed in. */
data class PayRiseDraft(
    val given: PayRise.Given = PayRise.Given.PERCENT,
    val valueText: String = "",
    val newNetText: String = "",
    val reason: String = "Yearly review",
    val effectiveDate: LocalDate = DateUtils.today(),
)

val PAY_RISE_REASONS = listOf("Yearly review", "Pay rise", "Promotion", "New job", "Pay cut")

@Composable
fun PayRiseForm(
    draft: PayRiseDraft,
    currentGrossMinor: Long?,
    onChange: (PayRiseDraft) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PAY_RISE_REASONS.forEach { reason ->
                FilterChip(
                    selected = draft.reason == reason,
                    onClick = { onChange(draft.copy(reason = reason)) },
                    label = { Text(reason) },
                )
            }
        }
        val options = if (currentGrossMinor == null) {
            listOf(PayRise.Given.NEW_PAY)
        } else {
            PayRise.Given.entries
        }
        DropdownField(
            label = "How was it given?",
            options = options,
            selected = draft.given.takeIf { it in options } ?: PayRise.Given.NEW_PAY,
            onSelect = { onChange(draft.copy(given = it)) },
            optionLabel = { it.displayName },
        )
        val given = draft.given.takeIf { it in options } ?: PayRise.Given.NEW_PAY
        LabelledTextField(
            label = when (given) {
                PayRise.Given.PERCENT -> "Rise (%)"
                PayRise.Given.AMOUNT -> "Extra a year (£)"
                PayRise.Given.NEW_PAY -> "New yearly pay before tax (£)"
            },
            value = draft.valueText,
            onValueChange = { onChange(draft.copy(valueText = it)) },
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        )
        draft.valueText.replace(",", "").toDoubleOrNull()
            ?.let { PayRise.newGross(currentGrossMinor, given, it) }
            ?.let { newGross ->
                Text(
                    text = "New pay before tax: ${Money.format(newGross)} a year",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        AmountField(
            label = "New take-home a year (optional)",
            value = draft.newNetText,
            onValueChange = { onChange(draft.copy(newNetText = it)) },
        )
        Text(
            text = "Leave take-home blank and it's estimated from what you take home now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DateField(
            label = "Starts from",
            date = draft.effectiveDate,
            onDateChange = { onChange(draft.copy(effectiveDate = it)) },
        )
    }
}
